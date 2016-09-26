package no.ntnu.dataport.types;

import no.ntnu.dataport.enums.DeviceState;
import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;


public class SensorData {
    private transient String city;
    private transient String appEui;
    private transient FiniteDuration timeout;

    private final String eui;
    private Position position;
    private DeviceState status;
    private DateTime lastSeen;
    private Messages.Observation lastObservation;

    public SensorData(String eui, String appEui, String city, Position position, FiniteDuration timeout) {
        this.eui = eui;
        this.appEui = appEui;
        this.city = city;
        this.position = position;
        this.timeout = timeout;

        this.status = DeviceState.UNKNOWN;
    }

    public DeviceState getStatus() {
        return status;
    }

    public void setStatus(DeviceState status) {
        this.status = status;
    }

    public String getEui() {
        return eui;
    }

    public String getAppEui() {
        return appEui;
    }

    public String getCity() {
        return city;
    }

    public Position getPosition() {
        return position;
    }

    public FiniteDuration getTimeout() {
        return timeout;
    }

    public DateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(DateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public Messages.Observation getLastObservation() {
        return lastObservation;
    }

    public void setLastObservation(Messages.Observation lastObservation) {
        this.lastObservation = lastObservation;
    }
}
