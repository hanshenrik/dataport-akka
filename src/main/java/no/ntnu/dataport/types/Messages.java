package no.ntnu.dataport.types;

import com.google.gson.annotations.SerializedName;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;

import java.io.Serializable;
import java.util.List;

public class Messages {
    public static final class MqttConnectionStatusMessage implements Serializable {}

    public static final class MqttSubscribeMessage {
        public final String topic;

        public MqttSubscribeMessage(String topic) {
            this.topic = topic;
        }
    }
    public static final class MqttPublishMessage {
        public final String topic;
        public final MqttMessage mqttMessage;

        public MqttPublishMessage(String topic, MqttMessage mqttMessage){
            this.topic = topic;
            this.mqttMessage = mqttMessage;
        }
    }
    public static final class NetworkGraphMessage {
        public final String graph;
        public final String topic;
        public NetworkGraphMessage(String graph, String topic) {
            this.graph = graph;
            this.topic = topic;
        }
    }

    public static final class SubscribeToInternalTopicMessage {
        public final String topic;
        public SubscribeToInternalTopicMessage(String topic) {
            this.topic = topic;
        }
    }

    public static final class MonitorApplicationMessage {
        public final String name;
        public final String appEui;
        public final String appKey;
        public final Position position;

        public MonitorApplicationMessage(String name, String appEui, String appKey, Position position) {
            this.name = name;
            this.appEui = appEui;
            this.appKey = appKey;
            this.position = position;
        }
    }

    public static final class Observation {
        public final String nodeEui;
        public final String gatewayEui;
        public final DateTime time;
        public final double frequency;
        public final double rssi;
        public final double snr;
        public final String dataRate;
        public transient final String data;

        public Observation(String nodeEui, String gatewayEui, DateTime time, double frequency, String dataRate,
                           double rssi, double snr, String data) {
            this.nodeEui = nodeEui;
            this.gatewayEui = gatewayEui;
            this.time = time;
            this.frequency = frequency;
            this.dataRate = dataRate;
            this.rssi = rssi;
            this.snr = snr;
            this.data = data;
        }
    }

    public static final class StagingObservation {
        public String payload;
        public float co2;
        public float no2;
        public float temperature;
        public float humidity;
        public float preassure;
        public float pm1;
        public float pm2;
        public float pm10;
        public int batteryLevel;
        public String dev_eui;
        public List<Metadata> metadata;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }

        public String getDev_eui() {
            return dev_eui;
        }

        public void setDev_eui(String dev_eui) {
            this.dev_eui = dev_eui;
        }

        public List<Metadata> getMetadata() {
            return metadata;
        }

        public void setMetadata(List metadata) {
            this.metadata = metadata;
        }

        public float getCo2() {
            return co2;
        }

        public void setCo2(float co2) {
            this.co2 = co2;
        }

        public float getNo2() {
            return no2;
        }

        public void setNo2(float no2) {
            this.no2 = no2;
        }

        public float getTemperature() {
            return temperature;
        }

        public void setTemperature(float temperature) {
            this.temperature = temperature;
        }

        public float getHumidity() {
            return humidity;
        }

        public void setHumidity(float humidity) {
            this.humidity = humidity;
        }

        public float getPreassure() {
            return preassure;
        }

        public void setPreassure(float preassure) {
            this.preassure = preassure;
        }

        public float getPm1() {
            return pm1;
        }

        public void setPm1(float pm1) {
            this.pm1 = pm1;
        }

        public float getPm2() {
            return pm2;
        }

        public void setPm2(float pm2) {
            this.pm2 = pm2;
        }

        public float getPm10() {
            return pm10;
        }

        public void setPm10(float pm10) {
            this.pm10 = pm10;
        }

        public int getBatteryLevel() {
            return batteryLevel;
        }

        public void setBatteryLevel(int batteryLevel) {
            this.batteryLevel = batteryLevel;
        }

        public static class Metadata {
            public String gateway_eui;
            public String datarate;
            public double frequency;
            public double rssi;
            public double lsnr;
            public String codingrate;
            public String modulation;
            public int channel;
            public int rfchain;
            public int crc;
            public double altitude;
            public double longitude;
            public double latitude;

            public String getGateway_eui() {
                return gateway_eui;
            }

            public void setGateway_eui(String gateway_eui) {
                this.gateway_eui = gateway_eui;
            }

            public String getDatarate() {
                return datarate;
            }

            public void setDatarate(String datarate) {
                this.datarate = datarate;
            }

            public double getFrequency() {
                return frequency;
            }

            public void setFrequency(double frequency) {
                this.frequency = frequency;
            }

            public double getRssi() {
                return rssi;
            }

            public void setRssi(double rssi) {
                this.rssi = rssi;
            }

            public double getLsnr() {
                return lsnr;
            }

            public void setLsnr(double lsnr) {
                this.lsnr = lsnr;
            }

            public String getCodingrate() {
                return codingrate;
            }

            public void setCodingrate(String codingrate) {
                this.codingrate = codingrate;
            }

            public String getModulation() {
                return modulation;
            }

            public void setModulation(String modulation) {
                this.modulation = modulation;
            }

            public int getChannel() {
                return channel;
            }

            public void setChannel(int channel) {
                this.channel = channel;
            }

            public int getRfchain() {
                return rfchain;
            }

            public void setRfchain(int rfchain) {
                this.rfchain = rfchain;
            }

            public int getCrc() {
                return crc;
            }

            public void setCrc(int crc) {
                this.crc = crc;
            }

            public double getAltitude() {
                return altitude;
            }

            public void setAltitude(double altitude) {
                this.altitude = altitude;
            }

            public double getLongitude() {
                return longitude;
            }

            public void setLongitude(double longitude) {
                this.longitude = longitude;
            }

            public double getLatitude() {
                return latitude;
            }

            public void setLatitude(double latitude) {
                this.latitude = latitude;
            }
        }
    }
}
