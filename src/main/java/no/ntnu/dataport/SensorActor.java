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
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.utils.SecretStuff;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import scala.Option;
import scala.concurrent.duration.FiniteDuration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static no.ntnu.dataport.utils.ConvertUtils.convertToObservation;


public class SensorActor extends AbstractFSM<DeviceState, SensorData> {

    /**
     * @param eui       The EUI of the gateway
     * @param appEui    The EUI of the application this actor representation of the sensor belongs to
     * @param position  The position of the gateway, given as latitude and longitude
     * @return a Props for creating this actor, which can then be further configured
     * (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String eui, final String airtableID, String appEui, String city, Position position, FiniteDuration timeout) {
        return Props.create(new Creator<SensorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public SensorActor create() throws Exception {
                return new SensorActor(eui, airtableID, appEui, city, position, timeout);
            }
        });
    }

    SensorData initialData;
    ActorRef mediator;
    String externalReceiveTopic;
    String internalStatusPublishTopic;
    String internalReceptionPublishTopic;

    public SensorActor(final String eui, final String airtableID, String appEui, String city, Position position, FiniteDuration timeout) {
        this.initialData = new SensorData(eui, airtableID, appEui, city, position, timeout);
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.externalReceiveTopic = "external/" + appEui + "/devices/" + eui + "/up";
        this.internalStatusPublishTopic = "dataport/site/" + city + "/sensor/" + eui + "/events/status";
        this.internalReceptionPublishTopic = "dataport/site/" + city + "/sensor/" + eui + "/events/reception";

        setStateTimeout(DeviceState.OK, Option.apply(timeout));

        // Tell the mediator I am interested in all MQTT messages sent from my digital twin
        mediator.tell(new DistributedPubSubMediator.Subscribe(externalReceiveTopic, self()), self());
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
                            // Update the Airtable
                            Unirest.patch("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + stateData().getCity() + "/" + stateData().getAirtableID())
                                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                                .header("Content-Type", "application/json")
                                .header("accept", "application/json")
                                .body(new JsonNode("{fields: {status: " + DeviceState.OK + "}}"))
                                .asJson();

                            // Create an Observation object
                            Observation observation = convertToObservation(message);

                            // Update my state
                            stateData().setLastObservation(observation);
                            stateData().setLastSeen(observation.metadata.server_time);
                            stateData().setStatus(DeviceState.OK);

                            // Tell the gateway where I am so it can calculate maxObservedRange
                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().metadata.gateway_eui)
                                    .tell(stateData().getPosition(), self());

                            // Publish my reception to all interested
                            mediator.tell(new DistributedPubSubMediator.Publish(internalReceptionPublishTopic, stateData().getLastObservation()), self());

                            // Publish my status to all interested to show I am alive
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.OK).using(stateData());
                        }
                ).event(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> stay())
        );

        when(DeviceState.OK, null, // timeout duration is set in the constructor
                matchEventEquals(StateTimeout(),
                        (event, data) -> {
                            // Update my state
                            stateData().setStatus(DeviceState.UNKNOWN);

                            // Update the Airtable
                            Unirest.patch("https://api.airtable.com/v0/" + SecretStuff.AIRTABLE_BASE_ID + "/" + stateData().getCity() + "/" + stateData().getAirtableID())
                                .header("Authorization", "Bearer " + SecretStuff.AIRTABLE_API_KEY)
                                .header("Content-Type", "application/json")
                                .header("accept", "application/json")
                                .body(new JsonNode("{fields: {status: " + DeviceState.UNKNOWN + "}}"))
                                .asJson();

                            // Send alert to Slack
                            SlackApi api = new SlackApi(SecretStuff.SLACK_API_WEBHOOK);
                            api.call(new SlackMessage("Timeout! Sensor "+data.getEui()+" in "+data.getCity() + " has been inactive for "+stateData().getTimeout()));

                            // Publish my status to all interested to show I timed out
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return goTo(DeviceState.UNKNOWN).using(stateData());
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            Observation observation = convertToObservation(message);

                            // Update my state
                            stateData().setLastObservation(observation);
                            stateData().setLastSeen(observation.metadata.server_time);

                            // Tell the gateway where I am so it can calculate maxObservedRange
                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().metadata.gateway_eui)
                                    .tell(stateData().getPosition(), self());

                            // Publish my reception to all interested
                            mediator.tell(new DistributedPubSubMediator.Publish(internalReceptionPublishTopic, stateData().getLastObservation()), self());

                            // Publish my status to all interested to show I am alive
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, stateData()), self());

                            return stay().using(stateData());
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
