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
import no.ntnu.dataport.types.*;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class GatewayActor extends AbstractFSM<DeviceState, GatewayData> {
    /**
     * @param eui       The EUI of the gateway
     * @param latitude  The latitude of the gateway
     * @param longitude The longitude of the gateway
     * @return a Props for creating this actor, which can then be further configured
     * (e.g. calling `.withDispatcher()` on it)
     */

    public static Props props(final String eui, String city, double latitude, double longitude,
                              FiniteDuration timeout) {
        return Props.create(new Creator<GatewayActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public GatewayActor create() throws Exception {
                return new GatewayActor(eui, city, latitude, longitude, timeout);
            }
        });
    }

    GatewayData initialData;
    ActorRef mediator;
    static FiniteDuration STATIC_TIMEOUT = Duration.create(20, TimeUnit.SECONDS);
    Gson gson;

    public GatewayActor(final String eui, String city, double latitude, double longitude, FiniteDuration timeout) {
        log().info("Constructor called with type: {}, eui: {}, lat: {}, lon: {}", city, eui, latitude, longitude);
        this.initialData = new GatewayData(eui, city, latitude, longitude, timeout);
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.gson = Converters.registerDateTime(new GsonBuilder()).create();

        String internalTopic = "gateways/" + eui + "/status";
        mediator.tell(new DistributedPubSubMediator.Subscribe(internalTopic, self()), self());
    }

    {
        startWith(DeviceState.UNINITIALIZED, null);

        when(DeviceState.UNINITIALIZED,
                matchEvent(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> {
                            context().parent().tell(String.format("Gateway now subscribing, going from state %s to %s",
                                    DeviceState.UNINITIALIZED, DeviceState.UNKNOWN), self());
                            return goTo(DeviceState.UNKNOWN).using(initialData);
                        })
        );

        when(DeviceState.UNKNOWN,
                matchEvent(MqttMessage.class,
                        (message, data) ->
                        {
                            context().parent().tell(String.format("Gateway going from %s to %s", stateName(), DeviceState.OK), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportSupervisor/dataport").tell(
                                    new Messages.MqttPublishMessage("dataport/site/"+stateData().getCity()+"/gateway/"+
                                            stateData().getEui()+"/events/status",
                                            new MqttMessage(gson.toJson(stateData().withState(DeviceState.OK)).getBytes())), self());
                            return goTo(DeviceState.OK).using(stateData().withLastSeen(DateTime.now()));
                            // TODO: don't use .now(), make JSON message into object before sending internally, add getTimestamp or something
                        }
                )
        );

        when(DeviceState.OK, STATIC_TIMEOUT,
                matchEventEquals(StateTimeout(),
                        (event, data) -> {
                            mediator.tell(new DistributedPubSubMediator.Publish("timeouts", "I timed out! I was last seen: "+
                                    stateData().getLastSeen()), self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportSupervisor/dataport").tell(
                                    new Messages.MqttPublishMessage("dataport/site/"+stateData().getCity()+"/gateway/"+
                                            stateData().getEui()+"/events/status",
                                            new MqttMessage(gson.toJson(stateData().withState(DeviceState.UNKNOWN)).getBytes())), self());
                            return goTo(DeviceState.UNKNOWN);
                        }).event(MqttMessage.class,
                        (message, data) -> {
                            context().parent().tell("Got data in expected time, staying OK", self());
                            context().system().actorSelection("/user/externalResourceSupervisor/dataportSupervisor/dataport").tell(
                                    new Messages.MqttPublishMessage("dataport/site/"+stateData().getCity()+"/gateway/"+
                                            stateData().getEui()+"/events/status",
                                            new MqttMessage(gson.toJson(stateData().withLastSeen(DateTime.now())).getBytes())), self());
                            return stay().using(stateData().withLastSeen(DateTime.now()));
                        }));

        initialize();
    }
}

