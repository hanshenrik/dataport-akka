package no.ntnu.dataport.types;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;
import scala.concurrent.duration.FiniteDuration;


public class SensorData {
    private final String eui;
    private final transient String airtableID;
    private String city;
    private String appEui;

    private Position position;
    private FiniteDuration timeout;

    private DeviceState status;
    private double co2;
    private double batteryLevel;
    private DateTime lastSeen;
    private Messages.Observation lastObservation;

    public SensorData(String eui, String airtableID, String appEui, String city, Position position, FiniteDuration timeout) {
        this.eui = eui;
        this.airtableID = airtableID;
        this.appEui = appEui;
        this.city = city;
        this.position = position;
        this.timeout = timeout;
    }

    public String getAirtableID() {
        return airtableID;
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

    public double getCo2() {
        return co2;
    }

    public void setCo2(double co2) {
        this.co2 = co2;
    }

    public double getBatteryLevel() {
        return batteryLevel;
    }

    public void setBatteryLevel(double batteryLevel) {
        this.batteryLevel = batteryLevel;
    }
}
