package no.ntnu.dataport.types;

import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;

public class GatewayData {
    private final String eui;
    private final transient String airtableID;
    private String city;
    private String appEui;
    private Position position;
    private FiniteDuration timeout;
    private DeviceState status;

    private DateTime lastSeen;
    private double maxObservedRange;

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

    public double getMaxObservedRange() {
        return maxObservedRange;
    }

    public GatewayData withMaxObservedRange(double maxObservedRange) {
        if (maxObservedRange > this.maxObservedRange) {
            this.maxObservedRange = maxObservedRange;
        }
        return this;
    }

    public GatewayData withState(DeviceState state) {
        this.status = state;
        return this;
    }

    public DateTime getLastSeen() {
        return lastSeen;
    }

    public GatewayData withLastSeen(DateTime lastSeen) {
        this.lastSeen = lastSeen;
        return this;
    }

    public String getEui() {
        return eui;
    }

    public String getCity() {
        return city;
    }

    public GatewayData withCity(String city) {
        this.city = city;
        return this;
    }


    public Position getPosition() {
        return position;
    }

    public GatewayData withPosition(Position position) {
        this.position = position;
        return this;
    }

    public FiniteDuration getTimeout() {
        return timeout;
    }
}
