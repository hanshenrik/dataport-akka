package no.ntnu.dataport.types;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.joda.time.DateTime;

import java.io.Serializable;

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
        public final String dataRate;
        public final double rssi;
        public final String data;

        public Observation(String nodeEui, String gatewayEui, DateTime time, double frequency, String dataRate, double rssi, String data) {
            this.nodeEui = nodeEui;
            this.gatewayEui = gatewayEui;
            this.time = time;
            this.frequency = frequency;
            this.dataRate = dataRate;
            this.rssi = rssi;
            this.data = data;
        }
    }
}
