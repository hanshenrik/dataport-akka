package no.ntnu.dataport;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Date;

public class MqttActor extends UntypedActor implements MqttCallback {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    /**
     * Create Props for an actor of this type.
     * @param broker    The broker address to be passed to this actor’s constructor.
     * @param topic     The topic the actor should subscribe to.
     * @param qos       The quality of service requirements for the MQTT connection.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String broker, final String topic, final int qos) {
        return Props.create(new Creator<MqttActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public MqttActor create() throws Exception {
                return new MqttActor(broker, topic, qos);
            }
        });
    }

    final String broker;
    final String topic;
    final int qos;
    final String clientId;
    final String content;

    public MqttActor(String broker, String topic, int qos) {
        log.info("Constructor called with broker: {}, topic: {}, qos: {}", broker, topic, qos);
        this.broker     = broker;
        this.topic      = topic;
        this.qos        = qos;
        this.clientId   = "client-" + new Date().getTime();
        this.content    = "TTN actor started successfully!";

        MemoryPersistence persistence = new MemoryPersistence();
        try {
            MqttClient sampleClient = new MqttClient(broker, clientId, persistence);

            // Set the callback function to be able to receive messages.
            sampleClient.setCallback(this);

            MqttConnectOptions connectionOptions = new MqttConnectOptions();
            connectionOptions.setCleanSession(true);
            // TODO: take these as (optional?) parameters. Needed for staging.thethingsnetwork.org!
//            connectionOptions.setUserName("70B3D57ED000091D");
//            connectionOptions.setPassword("ytpiqGLn5fYjlmkMvC95sim02Je6BeB+7dxn4MU4/Bg=".toCharArray());

            log.info("Connecting to broker: {}", broker);
            sampleClient.connect(connectionOptions);

            sampleClient.subscribe(topic);
            log.info("Subscribed to topic: {}", topic);

//            sampleClient.publish(topic, new MqttMessage(content.getBytes()));
//            log.info("Published to topic: {}", topic);
        } catch(MqttException me) {
            log.info("reason "+me.getReasonCode());
            log.info("msg "+me.getMessage());
            log.info("loc "+me.getLocalizedMessage());
            log.info("cause "+me.getCause());
            log.info("excep "+me);
            me.printStackTrace();
        }
    }

    @Override
    public void onReceive(Object message) {
        // Do something with if someone sends a message to me..?
    }

    @Override
    public void connectionLost(Throwable cause) {

    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        log.info("MQTT Received: '{}' on topic '{}'", message, topic);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }
}

