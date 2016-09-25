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
import no.ntnu.dataport.enums.DeviceState;
import no.ntnu.dataport.types.*;
import no.ntnu.dataport.utils.Haversine;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.Option;
import scala.concurrent.duration.FiniteDuration;

public class GatewayActor extends AbstractFSM<DeviceState, GatewayData> {

    /**
     *
     * @param eui           The eui of the gateway.
     * @param airtableID    The record ID in Airtable for the gateway.
     * @param appEui        The TTN AppEui.
     * @param city          The name of the city the gateway is located in.
     * @param position      The location of the city
     * @param timeout       The duration before an alert is sent if no status message is received from the gateway.
     * @return              a Props for creating this actor, which can then be further configured
     *                      (e.g. calling `.withDispatcher()` on it)
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

        // Tell the mediator I am interested in all MQTT messages sent from my digital twin
        mediator.tell(new DistributedPubSubMediator.Subscribe(receiveStatusTopic, self()), self());
    }

    public void handler(DeviceState from, DeviceState to) {
        if (from != to) {
            log().info("Going from {} to {}", from, to);
        }
    }

    {
        startWith(DeviceState.UNINITIALIZED, null);

        when(DeviceState.UNINITIALIZED,
                matchEvent(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> goTo(DeviceState.UNKNOWN).using(initialData))
        );

        when(DeviceState.UNKNOWN,
                matchEvent(MqttMessage.class,
                        (message, data) ->
                        {
                            // Update Airtable
                            Unirest.patch("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + stateData().getCity() + "/" + stateData().getAirtableID())
                                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                                .header("Content-Type", "application/json")
                                .header("accept", "application/json")
                                .body(new JsonNode("{fields: {status: " + DeviceState.OK + "}}"))
                                .asJson();

                            stateData().setLastSeen(DateTime.now());
                            stateData().setStatus(DeviceState.OK);

                            // Publish my status to all interested to show I am alive
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.OK).using(stateData());
                        }
                ).event(Position.class,
                        (message, data) -> {
                            // Calculate distance to sensor sending observation
                            int distance = (int) Haversine.distance(message, stateData().getPosition());

                            stateData().setMaxObservedRange(distance);
                            stateData().setLastSeen(DateTime.now());
                            stateData().setStatus(DeviceState.OK);

                            // Publish my status to all interested to show I am alive
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.OK).using(stateData());
                        })
        );

        when(DeviceState.OK, null, // timeout duration is set in the constructor
                matchEventEquals(StateTimeout(),
                        (event, data) -> {
                            // Update Airtable
                            Unirest.patch("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + stateData().getCity() + "/" + stateData().getAirtableID())
                                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                                .header("Content-Type", "application/json")
                                .header("accept", "application/json")
                                .body(new JsonNode("{fields: {status: " + DeviceState.UNKNOWN + "}}"))
                                .asJson();

                            // Update my state
                            stateData().setStatus(DeviceState.UNKNOWN);

                            // Send alert to Slack
                            SlackApi api = new SlackApi(SecretStuff.SLACK_API_WEBHOOK);
                            api.call(new SlackMessage("Timeout! Gateway "+data.getEui()+" in "+data.getCity() + " has been inactive for "+stateData().getTimeout()));

                            // Publish my status to all interested to show I timed out
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.UNKNOWN);
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            stateData().setLastSeen(DateTime.now());

                            // Publish my status to all interested to show I am alive
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return stay().using(stateData());
                        }).event(Position.class,
                        (message, data) -> {
                            // Calculate distance to sensor sending observation
                            int distance = (int) Haversine.distance(message, stateData().getPosition());

                            // Update my state
                            stateData().setMaxObservedRange(distance);
                            stateData().setLastSeen(DateTime.now());

                            // Publish my status to all interested to show I got a reception message
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return stay();
                        }));

        whenUnhandled(
                matchAnyEvent((event, data) -> {
                    log().warning("Unable to handle {} in state {}, but I'll continue as normal.", event, stateName());
                    return stay();
                }));

        onTransition(this::handler);

        initialize();
    }
}

