package no.ntnu.dataport;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import com.fatboyindustrial.gsonjodatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import no.ntnu.dataport.enums.DeviceState;
import no.ntnu.dataport.enums.DeviceType;
import no.ntnu.dataport.types.*;
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.utils.SecretStuff;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SiteActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    /**
     * Create Props for an actor of this type.
     * @param name      The name of the city
     * @param appEui    The TTN appEui for the application
     * @param position  The position of the city, given as latitude and longitude
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String name, final String appEui, final Position position) {
        return Props.create(new Creator<SiteActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public SiteActor create() throws Exception {
                return new SiteActor(name, appEui, position);
            }
        });
    }

    ActorRef mediator;
    final String name;
    final String appEui;
    final Position position;
    List<NetworkComponent> networkComponents = new ArrayList<>();
    Map<String, NetworkComponent> networkComponentMap = new HashMap<>();
    final String networkGraphTopic;
    final String siteStatsTopic;

    private final Cancellable periodicNetworkGraphMessage;
    private final Gson gson;

    @Override
    public void postStop() {
        periodicNetworkGraphMessage.cancel();
    }

    public SiteActor(String name, String appEui, Position position) {
        log.debug("Constructor called with name: {}, lat: {}, lon: {}", name, position.lat, position.lon);
        this.name = name;
        this.networkGraphTopic = "dataport/site/graphs";
        this.siteStatsTopic = "dataport/site/" + name + "/stats";
        this.appEui = appEui;
        this.position = position;
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.gson = Converters.registerDateTime(new GsonBuilder()).create();

        this.periodicNetworkGraphMessage = getContext().system().scheduler().schedule(
                Duration.create(5, TimeUnit.SECONDS),
                Duration.create(20, TimeUnit.SECONDS),
                () -> mediator.tell(new DistributedPubSubMediator.Publish(networkGraphTopic, getNetworkGraphMessage()), self()),
                getContext().dispatcher());

        try {
            HttpResponse<JsonNode> jsonResponse = Unirest.get("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + name)
                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                .header("accept", "application/json")
                .asJson();
            JSONArray devices = jsonResponse.getBody().getObject().getJSONArray("records");

            String airtableID, eui, type;
            Position pos;
            FiniteDuration timeout;
            DeviceState status = DeviceState.UNKNOWN;
            for (Object device : devices) {
                airtableID = ((JSONObject) device).getString("id");
                JSONObject fields = ((JSONObject) device).getJSONObject("fields");
                eui = fields.getString("eui");
                type = fields.getString("type");
                timeout = Duration.create(fields.getInt("timeout"), TimeUnit.SECONDS);

                try {
                    pos = new Position(fields.getDouble("latitude"), fields.getDouble("longitude"));
                } catch (JSONException e) {
                    log.error("Device {} didn't have position in Aritable, it will not be created!", eui);
                    continue;
                }

//                try {
//                    status = DeviceState.valueOf(fields.getString("status"));
//                } catch (JSONException e) {
//                    status = DeviceState.UNKNOWN;
//                    log.warning("Device {} didn't have status in Airtable, setting status to {}", eui, status);
//                }

                switch (type.toLowerCase()) {
                    case "gateway":
                        // Create gateway actor
                        getContext().actorOf(GatewayActor.props(eui, airtableID, appEui, this.name, pos, timeout), eui);

                        String internalGatewayStatusTopic = "dataport/site/" + name + "/gateway/" + eui + "/events/status";

                        // Know that it exists, so we can keep an updated network graph
                        networkComponents.add(new NetworkComponent(DeviceType.GATEWAY, eui, pos, status));
                        networkComponentMap.put(eui, new NetworkComponent(DeviceType.GATEWAY, eui, pos, status));

                        // Subscribe to status updates from the gateway
                        mediator.tell(new DistributedPubSubMediator.Subscribe(internalGatewayStatusTopic, self()), self());
                        break;
                    case "sensor":
                        // Create sensor actor
                        context().actorOf(SensorActor.props(eui, airtableID, appEui, this.name, pos, timeout), eui);

                        String internalSensorStatusTopic = "dataport/site/" + name + "/sensor/" + eui + "/events/status";

                        // Know that it exists, so we can keep an updated network graph
                        networkComponents.add(new NetworkComponent(DeviceType.SENSOR, eui, pos, status));
                        networkComponentMap.put(eui, new NetworkComponent(DeviceType.SENSOR, eui, pos, status));

                        // Subscribe to status updates from the sensor
                        mediator.tell(new DistributedPubSubMediator.Subscribe(internalSensorStatusTopic, self()), self());
                        break;
                    default:
                        log.warning("Unknown device type: {}", type);
                }
            }
        }
        catch (UnirestException e) {
            e.printStackTrace();
        }
    }

    private NetworkGraphMessage getNetworkGraphMessage() {
        return new NetworkGraphMessage(networkComponentMap);
    }

    private void updateNetworkComponent(String eui, NetworkComponent updatedNetworkComponent) {
        networkComponentMap.put(eui, updatedNetworkComponent);
    }

    @Override
    public void onReceive(Object message) {
        log.debug("Received: {} from {}", message, getSender());
        if (message instanceof SensorData) {
            String eui = ((SensorData) message).getEui();
            DeviceState status = ((SensorData) message).getStatus();
            DateTime lastSeen = ((SensorData) message).getLastSeen();
            updateNetworkComponent(eui, networkComponentMap.get(eui).withStatus(status).withLastSeen(lastSeen));
        }
        else if (message instanceof GatewayData) {
            String eui = ((GatewayData) message).getEui();
            DeviceState status = ((GatewayData) message).getStatus();
            DateTime lastSeen = ((GatewayData) message).getLastSeen();
            double maxObservedRange = ((GatewayData) message).getMaxObservedRange();
            updateNetworkComponent(eui, networkComponentMap.get(eui).withStatus(status).withLastSeen(lastSeen).withMaxObservedRange(maxObservedRange));
        }
    }
}
