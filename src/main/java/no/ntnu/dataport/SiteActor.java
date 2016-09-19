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
import com.google.gson.Gson;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import no.ntnu.dataport.types.*;
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.utils.SecretStuff;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.ArrayList;
import java.util.List;
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
    final String networkGraphTopic;

    private final Cancellable periodicNetworkGraphMessage;

    @Override
    public void postStop() {
        periodicNetworkGraphMessage.cancel();
    }

    public SiteActor(String name, String appEui, Position position) {
        log.info("Constructor called with name: {}, lat: {}, lon: {}", name, position.lat, position.lon);
        this.name = name;
        this.networkGraphTopic = "dataport/site/" + name + "/graph";
        this.appEui = appEui;
        this.position = position;
        this.mediator = DistributedPubSub.get(context().system()).mediator();

        // Tell the Dataport MQTTActor to listen to the network graph topic for this site
        getContext().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                new Messages.SubscribeToInternalTopicMessage(networkGraphTopic), self());

        this.periodicNetworkGraphMessage = getContext().system().scheduler().schedule(
                Duration.create(5, TimeUnit.SECONDS),
                Duration.create(60, TimeUnit.SECONDS),
                new Runnable() {
                    @Override
                    public void run() {
                        mediator.tell(new DistributedPubSubMediator.Publish(networkGraphTopic, getNetworkGraphMessage()), self());
                    }
                },
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
            DeviceState status;
            for (Object device : devices) {
                airtableID = ((JSONObject) device).getString("id");
                JSONObject fields = ((JSONObject) device).getJSONObject("fields");
                eui = fields.getString("eui");
                type = fields.getString("type");
                pos = new Position(fields.getDouble("latitude"), fields.getDouble("longitude"));
                timeout = Duration.create(fields.getInt("timeout"), TimeUnit.SECONDS);
                try {
                    status = DeviceState.valueOf(fields.getString("status"));
                } catch (JSONException e) {
                    status = DeviceState.UNKNOWN;
                    log.info("Device {} didn't have status in Airtable, setting status to {}", eui, status);
                }
                switch (type.toLowerCase()) {
                    case "gateway":
                        // Create gateway actor
                        getContext().actorOf(GatewayActor.props(eui, airtableID, appEui, this.name, pos, timeout), eui);

                        String externalGatewayStatusTopic = "gateways/" + eui + "/status";
                        String internalGatewayStatusTopic = "dataport/site/" + name + "/gateway/" + eui + "/events/status";

                        // Know that it exists, so we can keep an updated network graph
                        networkComponents.add(new NetworkComponent(DeviceType.GATEWAY, eui, pos, status));

                        // Tell the GatewayStatusBroker to listen to status messages from this gateway
                        context().system().actorSelection("/user/externalResourceSupervisor/ttnGatewayStatusBrokerSupervisor/ttnGatewayStatusBroker").tell(
                                new MqttSubscribeMessage(externalGatewayStatusTopic), self());

                        // Tell the DataportBroker to listen to the gateway status messages
                        getContext().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                new Messages.SubscribeToInternalTopicMessage(internalGatewayStatusTopic), self());
                        break;
                    case "sensor":
                        // Create sensor actor
                        context().actorOf(SensorActor.props(eui, airtableID, appEui, this.name, pos, timeout), eui);

                        String externalSensorReceptionTopic = appEui + "/devices/" + eui + "/up";
                        String internalSensorStatusTopic = "dataport/site/" + name + "/sensor/" + eui + "/events/status";
                        String internalSensorReceptionTopic = "dataport/site/" + name + "/sensor/" + eui + "/events/reception";

                        // Know that it exists, so we can keep an updated network graph
                        networkComponents.add(new NetworkComponent(DeviceType.SENSOR, eui, pos, status));

                        // Tell the GatewayStatusBroker to listen to events from this sensor
                        context().system().actorSelection("/user/externalResourceSupervisor/ttn-"+name+"-broker-supervisor/ttn-"+name+"-broker").tell(
                                new MqttSubscribeMessage(externalSensorReceptionTopic), self());

                        // Tell the DataportBroker to listen to the sensor status messages
                        getContext().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                new Messages.SubscribeToInternalTopicMessage(internalSensorStatusTopic), self());

                        // Tell the DataportBroker to listen to the sensor reception messages
                        getContext().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                new Messages.SubscribeToInternalTopicMessage(internalSensorReceptionTopic), self());
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

    private String getNetworkGraph() {
        return new Gson().toJson(networkComponents);
    }

    private NetworkGraphMessage getNetworkGraphMessage() {
        return new NetworkGraphMessage(getNetworkGraph(), networkGraphTopic);
    }

    @Override
    public void onReceive(Object message) {
        log.info("Received: {} from {}", message, getSender());
        // TODO: Handle adding of new devices through messages here, instead of reading from file!
    }
}
