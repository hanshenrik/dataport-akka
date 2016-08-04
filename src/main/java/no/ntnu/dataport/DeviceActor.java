package no.ntnu.dataport;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;

public class DeviceActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

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

    public DeviceActor(DeviceType type, String eui, double latitude, double longitude) {
        log.info("Constructor called with type: {}, eui: {}, lat: {}, lon: {}", type, eui, latitude, longitude);
        this.type = type;
        this.eui = eui;
        this.latitude = latitude;
        this.longitude = longitude;

        ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();
        // TODO: When migrating to staging environment, this should be removed, as topic structure should be identical
        // for sensors and gateways
        switch (type) {
            case SENSOR:
                mediator.tell(new DistributedPubSubMediator.Subscribe("nodes/"+eui+"/packets", getSelf()), getSelf());
                break;
            case GATEWAY:
                mediator.tell(new DistributedPubSubMediator.Subscribe("gateways/"+eui+"/status", getSelf()), getSelf());
                break;
            default:
        }
    }

    public void onReceive(Object message) {
        if (message instanceof String) {
            log.info("Got: {}", message);
        }
        else if (message instanceof DistributedPubSubMediator.SubscribeAck) {
            log.info("Got: DistributedPubSubMediator.SubscribeAck, so is now subscribing.");
        }
        else {
            unhandled(message);
        }
    }
}
