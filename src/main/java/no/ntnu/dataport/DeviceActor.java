package no.ntnu.dataport;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;

public class DeviceActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    /**
     * Create Props for an actor of this type.
     *
     * @param eui       The EUI of the gateway
     * @param latitude  The latitude of the gateway
     * @param longitude The longitude of the gateway
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
    }

    @Override
    public void onReceive(Object message) {

    }
}
