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
            log().info("Going from {} to {}", from, to);

            // Tell all interested that I am changing my state
            mediator.tell(new DistributedPubSubMediator.Publish(internalStatusPublishTopic, nextStateData()), self());
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
                                .asJson().getStatus();

                            Observation observation = convertToObservation(message);

                            stateData().setLastObservation(observation);
                            stateData().setLastSeen(observation.metadata.server_time);
                            stateData().setStatus(DeviceState.OK);

                            // Tell the gateway where I am so it can calculate maxObservedRange
                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().metadata.gateway_eui)
                                    .tell(stateData().getPosition(), self());

                            // Publish my reception to all interested
                            mediator.tell(new DistributedPubSubMediator.Publish(internalReceptionPublishTopic, stateData().getLastObservation()), self());

                            return goTo(DeviceState.OK).using(stateData());
                        }
                ).event(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> stay())
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

                            stateData().setStatus(DeviceState.UNKNOWN);

                            return goTo(DeviceState.UNKNOWN).using(stateData());
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            Observation observation = convertToObservation(message);

                            stateData().setLastObservation(observation);
                            stateData().setLastSeen(observation.metadata.server_time);

                            // Tell the gateway where I am so it can calculate maxObservedRange
                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().metadata.gateway_eui)
                                    .tell(stateData().getPosition(), self());

                            // Publish my reception to all interested
                            mediator.tell(new DistributedPubSubMediator.Publish(internalReceptionPublishTopic, stateData().getLastObservation()), self());

                            return stay().using(stateData());
                        }));

        onTransition(this::handler);

        initialize();
    }

    private float hex8BytesToFloat(String hex) {
        Long value = Long.parseLong(hex, 16);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.asLongBuffer().put(value);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.asFloatBuffer().get();
    }

    private int hex2BytesToInt(String hex) {
        return Integer.parseInt(hex, 16);
    }

    private Observation convertToObservation(MqttMessage message) {
        CTT2Observation CTT2Observation = gson.fromJson(new String(message.getPayload()), CTT2Observation.class);

        String payloadBase64 = CTT2Observation.payload;
        byte[] payloadBytes = Base64.decodeBase64(payloadBase64);
        String payloadHexWithHeader = Hex.encodeHexString(payloadBytes);
        String payloadHexOnlyData = payloadHexWithHeader.substring(36); // Skip the header
        float co2 = hex8BytesToFloat(payloadHexOnlyData.substring(2, 10));
        float no2 = hex8BytesToFloat(payloadHexOnlyData.substring(12, 20));
        float temp = hex8BytesToFloat(payloadHexOnlyData.substring(22, 30));
        float hum = hex8BytesToFloat(payloadHexOnlyData.substring(32, 40));
        float pres = hex8BytesToFloat(payloadHexOnlyData.substring(42, 50));
        int bat;

        Data data;
        if (payloadHexOnlyData.length() > 55) {
            float pm1 = hex8BytesToFloat(payloadHexOnlyData.substring(52, 60));
            float pm2 = hex8BytesToFloat(payloadHexOnlyData.substring(62, 70));
            float pm10 = hex8BytesToFloat(payloadHexOnlyData.substring(72, 80));
            bat = hex2BytesToInt(payloadHexOnlyData.substring(82, 84));
            data = new Data(co2, no2, temp, hum, pres, pm1, pm2, pm10, bat);
        }
        else {
            bat = hex2BytesToInt(payloadHexOnlyData.substring(52, 54));
            data = new Data(co2, no2, temp, hum, pres, bat);
        }
        return new Observation(CTT2Observation.dev_eui, CTT2Observation.metadata.get(0), data);
    }
}
