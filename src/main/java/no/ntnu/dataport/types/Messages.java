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
        public final String city;
        public NetworkGraphMessage(String graph, String city) {
            this.graph = graph;
            this.city = city;
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
        public final String data;

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
        }
    }
}
