package no.ntnu.dataport.types;

import org.eclipse.paho.client.mqttv3.MqttMessage;

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

    public static final class ObservationMessage implements Serializable {
        public final MqttMessage observation;
        public ObservationMessage(MqttMessage observation) {
            this.observation = observation;
        }
    }
}
