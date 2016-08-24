package no.ntnu.dataport.types;

import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;

public class GatewayData {
    private final String eui;
    private String city;
    private double latitude;
    private double longitude;
    private FiniteDuration timeout;
    private DeviceState status;

    private DateTime lastSeen;
    private double maxObservedRange;

    public GatewayData(String eui, String city, double latitude, double longitude, FiniteDuration timeout) {
        this.maxObservedRange = 2000;
        this.eui = eui;
        this.city = city;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeout = timeout;
    }

    public double getMaxObservedRange() {
        return maxObservedRange;
    }

    public GatewayData withMaxObservedRange(double maxObservedRange) {
        this.maxObservedRange = maxObservedRange;
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

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public FiniteDuration getTimeout() {
        return timeout;
    }
}
