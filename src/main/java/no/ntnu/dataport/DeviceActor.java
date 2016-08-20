package no.ntnu.dataport;

import akka.actor.AbstractFSM;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.japi.Creator;
import no.ntnu.dataport.types.DeviceData;
import no.ntnu.dataport.types.DeviceState;
import no.ntnu.dataport.types.DeviceType;
import org.joda.time.DateTime;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class DeviceActor extends AbstractFSM<DeviceState, DeviceData> {

    /**
     * Create Props for an actor of this type.
     *
     * @param type      The type of device, enum defined in DeviceType
     * @param eui       The EUI of the device
     * @param latitude  The latitude of the device
     * @param longitude The longitude of the device
     * @return a Props for creating this actor, which can then be further configured
     * (e.g. calling `.withDispatcher()` on it)
     */

    public static Props props(final DeviceType type, final String eui, final double latitude, final double longitude) {
        return Props.create(new Creator<DeviceActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public DeviceActor create() throws Exception {
                return new DeviceActor(type, eui, latitude, longitude);
            }
        });
    }

    final DeviceType type;
    final String eui;
    final double latitude;
    final double longitude;
    final FiniteDuration timeout = Duration.create(20, TimeUnit.SECONDS);

    public DeviceActor(DeviceType type, String eui, double latitude, double longitude) {
        log().info("Constructor called with type: {}, eui: {}, lat: {}, lon: {}", type, eui, latitude, longitude);
        this.type = type;
        this.eui = eui;
        this.latitude = latitude;
        this.longitude = longitude;

        ActorRef mediator = DistributedPubSub.get(context().system()).mediator();
        // TODO: When migrating to staging environment, this should be removed, as topic structure should be identical
        // for sensors and gateways
        switch (type) {
            case SENSOR:
                mediator.tell(new DistributedPubSubMediator.Subscribe("nodes/" + eui + "/packets", self()), self());
                break;
            case GATEWAY:
                mediator.tell(new DistributedPubSubMediator.Subscribe("gateways/" + eui + "/status", self()), self());
                break;
            default:
        }
    }

    {
        startWith(DeviceState.UNINITIALIZED, new DeviceData(null));

        when(DeviceState.UNINITIALIZED,
                matchEvent(DistributedPubSubMediator.SubscribeAck.class,
                        (event, data) -> {
                            context().parent().tell(String.format("Now subscribing, going from state %s to %s", DeviceState.UNINITIALIZED, DeviceState.UNKNOWN), self());
                            return goTo(DeviceState.UNKNOWN);
                        })
        );

        when(DeviceState.UNKNOWN,
                matchEvent(String.class,
                        DeviceData.class,
                        (message, lastSeen) ->
                        {
                            context().parent().tell(String.format("Going from %s to %s", stateName(), DeviceState.ALIVE), self());
                            return goTo(DeviceState.ALIVE).using(new DeviceData(DateTime.now()));
                        }
                )
        );

        when(DeviceState.ALIVE, timeout,
                matchEventEquals(StateTimeout(),
                        (event, data) -> {
                            context().parent().tell("I timed out! I was last seen: "+data.lastSeen, self());
                            return goTo(DeviceState.UNKNOWN);
                        }).event(String.class,
                        (event, data) -> {
                            context().parent().tell("Got data in expected time, staying ALIVE", self());
                            return stay().using(new DeviceData(DateTime.now()));
                }));

        initialize();
    }
}
