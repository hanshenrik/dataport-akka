package no.ntnu.dataport.types;

import no.ntnu.dataport.enums.DeviceState;
import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;

public class GatewayData {
    private final transient String airtableID;
    private transient String city;
    private transient String appEui;
    private transient FiniteDuration timeout;

    private final String eui;
    private Position position;
    private DeviceState status;
    private DateTime lastSeen;
    private int maxObservedRange;

    public GatewayData(String eui, String airtableID, String appEui, String city, Position position, FiniteDuration timeout) {
        this.eui = eui;
        this.airtableID = airtableID;
        this.appEui = appEui;
        this.city = city;
        this.position = position;
        this.timeout = timeout;
        this.maxObservedRange = 0;
    }

    public String getAirtableID() {
        return airtableID;
    }

    public int getMaxObservedRange() {
        return maxObservedRange;
    }

    public void setMaxObservedRange(int maxObservedRange) {
        if (maxObservedRange > this.maxObservedRange) {
            this.maxObservedRange = maxObservedRange;
        }
    }

    public DateTime getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(DateTime lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getEui() {
        return eui;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }


    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public FiniteDuration getTimeout() {
        return timeout;
    }

    public DeviceState getStatus() {
        return status;
    }

    public void setStatus(DeviceState status) {
        this.status = status;
    }
}
