package no.ntnu.dataport;

import akka.actor.AbstractFSM;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.japi.Creator;
import com.fatboyindustrial.gsonjodatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackMessage;
import no.ntnu.dataport.types.*;
import no.ntnu.dataport.utils.Haversine;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.Option;
import scala.concurrent.duration.FiniteDuration;

public class GatewayActor extends AbstractFSM<DeviceState, GatewayData> {

    /**
     * @param eui       The EUI of the gateway
     * @param appEui    The EUI of the application this actor representation of the gateway belongs to
     * @param position  The position of the gateway, given as latitude and longitude
     * @return a Props for creating this actor, which can then be further configured
     * (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String eui, final String airtableID, String appEui, String city, Position position, FiniteDuration timeout) {
        return Props.create(new Creator<GatewayActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public GatewayActor create() throws Exception {
                return new GatewayActor(eui, airtableID, appEui, city, position, timeout);
            }
        });
    }

    GatewayData initialData;
    ActorRef mediator;
    String internalStatusPublishTopic;
    String receiveStatusTopic;
    Gson gson;

    public GatewayActor(final String eui, final String airtableID, String appEui, String city, Position position,
                        FiniteDuration timeout) {
        this.initialData = new GatewayData(eui, airtableID, appEui, city, position, timeout);
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.gson = Converters.registerDateTime(new GsonBuilder()).create();
        this.internalStatusPublishTopic = "dataport/site/" + city + "/gateway/" + eui + "/events/status";
        this.receiveStatusTopic = "external/gateways/" + eui + "/status";
        setStateTimeout(DeviceState.OK, Option.apply(timeout));

        mediator.tell(new DistributedPubSubMediator.Subscribe(receiveStatusTopic, self()), self());
    }

    public void handler(DeviceState from, DeviceState to) {
        if (from != to) {
            // TODO: instead of doing mediator.tell in all state changes, do it here with a StateChangeMessage or something
            log().info("Going from {} to {}", from, to);
//            mediator.tell();
        }
    }

    {
        startWith(DeviceState.UNINITIALIZED, null);

        when(DeviceState.UNINITIALIZED,
                matchEvent(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> {
                            return goTo(DeviceState.UNKNOWN).using(initialData);
                        })
        );

        when(DeviceState.UNKNOWN,
                matchEvent(MqttMessage.class,
                        (message, data) ->
                        {
                            Unirest.patch("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + stateData().getCity() + "/" + stateData().getAirtableID())
                                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                                .header("Content-Type", "application/json")
                                .header("accept", "application/json")
                                .body(new JsonNode("{fields: {status: " + DeviceState.OK + "}}"))
                                .asJson();

                            // TODO: don't use .now(), make JSON message into object before sending internally, add getTimestamp or something
                            stateData().setLastSeen(DateTime.now());
                            stateData().setStatus(DeviceState.OK);

                            // Tell all interested that I am changing my state
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.OK).using(stateData());
                        }
                ).event(Position.class,
                        (message, data) -> {
                            log().debug("New observation received at gateway!");
                            double distance = (int) Haversine.distance(message, stateData().getPosition());
                            log().debug("Distance calculated to be: {}", distance);

                            stateData().setMaxObservedRange(distance);
                            stateData().setLastSeen(DateTime.now());

                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.OK).using(stateData());
                        })
        );

        when(DeviceState.OK, null, // timeout duration is set in the constructor
                matchEventEquals(StateTimeout(),
                        (event, data) -> {
                            Unirest.patch("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + stateData().getCity() + "/" + stateData().getAirtableID())
                                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                                .header("Content-Type", "application/json")
                                .header("accept", "application/json")
                                .body(new JsonNode("{fields: {status: " + DeviceState.UNKNOWN + "}}"))
                                .asJson();

                            SlackApi api = new SlackApi(SecretStuff.SLACK_API_WEBHOOK);
                            api.call(new SlackMessage("Timeout! Gateway "+data.getEui()+" in "+data.getCity() + " has been inactive for "+stateData().getTimeout()));

                            stateData().setStatus(DeviceState.UNKNOWN);

                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.UNKNOWN);
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            log().debug("Got data in expected time, staying in OK state");

                            stateData().setLastSeen(DateTime.now());

                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return stay().using(stateData());
                        }).event(Position.class,
                        (message, data) -> {
                            log().debug("New observation from position {} sent to gateway!", message);
                            double distance = (int) Haversine.distance(message, stateData().getPosition());
                            log().debug("Distance calculated to be: {}", distance);

                            stateData().setMaxObservedRange(distance);
                            stateData().setLastSeen(DateTime.now());

                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return stay();
                        }));

        onTransition(this::handler);

        initialize();
    }
}

