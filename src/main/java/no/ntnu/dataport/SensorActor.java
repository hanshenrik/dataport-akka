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
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackMessage;
import no.ntnu.dataport.types.*;
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;

public class SensorActor extends AbstractFSM<DeviceState, SensorData> {

    /**
     * @param eui       The EUI of the gateway
     * @param appEui    The EUI of the application this actor representation of the sensor belongs to
     * @param position  The position of the gateway, given as latitude and longitude
     * @return a Props for creating this actor, which can then be further configured
     * (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String eui, String appEui, String city, Position position, FiniteDuration timeout) {
        return Props.create(new Creator<SensorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public SensorActor create() throws Exception {
                return new SensorActor(eui, appEui, city, position, timeout);
            }
        });
    }

    SensorData initialData;
    ActorRef mediator;
    FiniteDuration timeout;
    Gson gson;

    public SensorActor(final String eui, String appEui, String city, Position position, FiniteDuration timeout) {
        log().info("Constructor called with timeout: {}, eui: {}, lat: {}, lon: {}", timeout, eui, position);
        this.initialData = new SensorData(eui, appEui, city, position, timeout);
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.gson = Converters.registerDateTime(new GsonBuilder()).create();
        this.timeout = timeout;

        String internalTopic = "nodes/" + eui + "/packets";
        mediator.tell(new DistributedPubSubMediator.Subscribe(internalTopic, self()), self());
        internalTopic = appEui + "/devices/" + eui + "/up";
        mediator.tell(new DistributedPubSubMediator.Subscribe(internalTopic, self()), self());
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
                            context().parent().tell(String.format("Sensor going from %s to %s", stateName(), DeviceState.OK), self());

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

                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().gatewayEui)
                                    .tell(stateData().getPosition(), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                    new MqttPublishMessage("dataport/site/"+stateData().getCity()+"/sensor/"+
                                            stateData().getEui()+"/events/status",
                                            new MqttMessage(gson.toJson(stateData().withState(DeviceState.OK)).getBytes())), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                    new MqttPublishMessage("dataport/site/"+stateData().getCity()+"/sensor/"+
                                            stateData().getEui()+"/events/reception",
                                            new MqttMessage(gson.toJson(stateData().getLastObservation()).getBytes())), self());
                            return goTo(DeviceState.OK).using(stateData().withLastSeen(DateTime.now()));
                            // TODO: don't use .now(), make JSON message into object before sending internally, add getTimestamp or something
                        }
                ).event(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> {
                            context().parent().tell("Sensor now subscribing to another internal topic", self());
                            return stay();
                        })
        );

        when(DeviceState.OK, timeout,
                matchEventEquals(StateTimeout(),
                        (event, data) -> {
                            SlackApi api = new SlackApi(SecretStuff.SLACK_API_WEBHOOK);
                            api.call(new SlackMessage("Timeout! Sensor "+data.getEui()+" in "+data.getCity() + " has been inactive for "+timeout));

                            mediator.tell(new DistributedPubSubMediator.Publish("timeouts", "I timed out! I was last seen: "+
                                    stateData().getLastSeen()), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                    new Messages.MqttPublishMessage("dataport/site/"+stateData().getCity()+"/sensor/"+
                                            stateData().getEui()+"/events/status",
                                            new MqttMessage(gson.toJson(stateData().withState(DeviceState.UNKNOWN)).getBytes())), self());
                            return goTo(DeviceState.UNKNOWN);
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            context().parent().tell("Got data in expected time, staying OK", self());

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

                            context().system().actorSelection("/user/"+stateData().getCity()+"/"+stateData().getLastObservation().gatewayEui)
                                    .tell(stateData().getPosition(), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                    new Messages.MqttPublishMessage("dataport/site/"+stateData().getCity()+"/sensor/"+
                                            stateData().getEui()+"/events/status",
                                            new MqttMessage(gson.toJson(stateData()).getBytes())), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportBrokerSupervisor/dataportBroker").tell(
                                    new Messages.MqttPublishMessage("dataport/site/"+stateData().getCity()+"/sensor/"+
                                            stateData().getEui()+"/events/reception",
                                            new MqttMessage(gson.toJson(stateData().getLastObservation()).getBytes())), self());
                            return stay().using(stateData().withLastSeen(DateTime.now()));
                        }));

        initialize();
    }

    private StagingObservation convertToStagingObservation(MqttMessage message) {
        System.out.println(new String(message.getPayload()));
        return gson.fromJson(new String(message.getPayload()), StagingObservation.class);
    }

    private Observation convertToObservation(MqttMessage message) {
        System.out.println(new String(message.getPayload()));
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
