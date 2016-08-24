package no.ntnu.dataport.types;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;


public class SensorData {
    private final String eui;
    private String city;
    private double latitude;
    private double longitude;
    private FiniteDuration timeout;

    private DeviceState status;
    private DateTime lastSeen;
    private MqttMessage lastObservation;

    public SensorData(String eui, String city, double latitude, double longitude, FiniteDuration timeout) {
        this.eui = eui;
        this.city = city;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timeout = timeout;
    }

    public SensorData withState(DeviceState state) {
        this.status = state;
        return this;
    }

    public String getEui() {
        return eui;
    }

    public String getCity() {
        return city;
    }

    public SensorData withCity(String city) {
        this.city = city;
        return this;
    }

    public double getLatitude() {
        return latitude;
    }

    public SensorData withLatitude(double latitude) {
        this.latitude = latitude;
        return this;
    }

    public double getLongitude() {
        return longitude;
    }

    public SensorData withLongitude(double longitude) {
        this.longitude = longitude;
        return this;
    }

    public FiniteDuration getTimeout() {
        return timeout;
    }

    public SensorData withTimeout(FiniteDuration timeout) {
        this.timeout = timeout;
        return this;
    }

    public DateTime getLastSeen() {
        return lastSeen;
    }

    public SensorData withLastSeen(DateTime lastSeen) {
        this.lastSeen = lastSeen;
        return this;
    }

    public MqttMessage getLastObservation() {
        return lastObservation;
    }

    public SensorData withLastObservation(MqttMessage observation) {
        this.lastObservation = observation;
        return this;
    }
}
