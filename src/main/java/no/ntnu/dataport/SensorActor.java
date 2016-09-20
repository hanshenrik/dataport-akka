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
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.Option;
import scala.concurrent.duration.FiniteDuration;

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
    Gson gson;
    String externalReceiveTopic;
    String internalStatusPublishTopic;
    String internalReceptionPublishTopic;

    public SensorActor(final String eui, final String airtableID, String appEui, String city, Position position, FiniteDuration timeout) {
        this.initialData = new SensorData(eui, airtableID, appEui, city, position, timeout);
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.gson = Converters.registerDateTime(new GsonBuilder()).create();
        this.externalReceiveTopic = "external/" + appEui + "/devices/" + eui + "/up";
        this.internalStatusPublishTopic = "dataport/site/" + city + "/sensor/" + eui + "/events/status";
        this.internalReceptionPublishTopic = "dataport/site/" + city + "/sensor/" + eui + "/events/reception";

        setStateTimeout(DeviceState.OK, Option.apply(timeout));

        // Tell the mediator I am interested in all MQTT messages sent from my digital twin
        mediator.tell(new DistributedPubSubMediator.Subscribe(externalReceiveTopic, self()), self());
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
                            context().parent().tell(String.format("Sensor now subscribing, going from state %s to %s",
                                    DeviceState.UNINITIALIZED, DeviceState.UNKNOWN), self());
                            return goTo(DeviceState.UNKNOWN).using(initialData);
                        })
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
                                .asJson().getStatus();

                            if (stateData().getAppEui().equals("+")) {
                                stateData().setLastObservation(convertToObservation(message));
                            }
                            else {
                                StagingObservation stagingObservation = convertToStagingObservation(message);
                                stateData().setLastObservation(convertToObservation(stagingObservation));
                            }
                            stateData().withLastSeen(DateTime.now());
                            stateData().setBatteryLevel(-1);
                            stateData().setCo2(-1);

                            // Tell the gateway where I am so it can calculate maxObservedRange
                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().gatewayEui)
                                    .tell(stateData().getPosition(), self());

                            // Tell all interested that I am changing my state
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic,
                                new MqttPublishMessage(internalStatusPublishTopic,
                                    new MqttMessage(gson.toJson(stateData().withState(DeviceState.OK)).getBytes()))), self());

                            // Publish my reception to all interested
                            mediator.tell(new DistributedPubSubMediator.Publish(internalReceptionPublishTopic,
                                new MqttPublishMessage(internalReceptionPublishTopic,
                                    new MqttMessage(gson.toJson(stateData().getLastObservation()).getBytes()))), self());

                            return goTo(DeviceState.OK).using(stateData().withLastSeen(DateTime.now()));
                            // TODO: don't use .now(), make JSON message into object before sending internally, add getTimestamp or something
                        }
                ).event(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> {
                            context().parent().tell("Sensor now subscribing to another internal topic", self());
                            return stay();
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
                            api.call(new SlackMessage("Timeout! Sensor "+data.getEui()+" in "+data.getCity() + " has been inactive for "+stateData().getTimeout()));

                            // Tell all interested that I am changing my state
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic,
                                new MqttPublishMessage(internalStatusPublishTopic,
                                    new MqttMessage(gson.toJson(stateData().withState(DeviceState.UNKNOWN)).getBytes()))), self());

                            return goTo(DeviceState.UNKNOWN).using(stateData().withState(DeviceState.UNKNOWN));
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            if (stateData().getAppEui().equals("+")) {
                                stateData().setLastObservation(convertToObservation(message));
                            }
                            else {
                                StagingObservation stagingObservation = convertToStagingObservation(message);
                                stateData().setLastObservation(convertToObservation(stagingObservation));
                            }
                            stateData().withLastSeen(DateTime.now());
                            stateData().setBatteryLevel(-1);
                            stateData().setCo2(-1);

                            // Tell the gateway where I am so it can calculate maxObservedRange
                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().gatewayEui)
                                    .tell(stateData().getPosition(), self());

                            // Tell all interested that I am changing my state
                            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic,
                                    new MqttPublishMessage(internalStatusPublishTopic,
                                            new MqttMessage(gson.toJson(stateData()).getBytes()))), self());

                            // Publish my reception to all interested
                            mediator.tell(new DistributedPubSubMediator.Publish(internalReceptionPublishTopic,
                                    new MqttPublishMessage(internalReceptionPublishTopic,
                                            new MqttMessage(gson.toJson(stateData().getLastObservation()).getBytes()))), self());

                            return stay().using(stateData().withLastSeen(DateTime.now()));
                        }));

        onTransition(this::handler);

        initialize();
    }

    private StagingObservation convertToStagingObservation(MqttMessage message) {
        return gson.fromJson(new String(message.getPayload()), StagingObservation.class);
    }

    private Observation convertToObservation(MqttMessage message) {
        return gson.fromJson(new String(message.getPayload()), Observation.class);
    }

    private Observation convertToObservation(StagingObservation so) {
        return new Observation(
                so.dev_eui,
                so.metadata.get(0).gateway_eui,
                DateTime.now(),
                so.metadata.get(0).frequency,
                so.metadata.get(0).datarate,
                so.metadata.get(0).rssi,
                so.metadata.get(0).lsnr, // TODO: is lsnr and snr the same?
                so.payload);
    }
}
