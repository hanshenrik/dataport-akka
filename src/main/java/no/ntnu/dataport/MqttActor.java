package no.ntnu.dataport;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

public class MqttActor extends UntypedActor implements MqttCallback {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    // activate the extension
    ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();

    /**
     * Create Props for an actor of this type.
     * @param broker    The broker address to be passed to this actor’s constructor.
     * @param topic     The topic the actor should subscribe to.
     * @param qos       The quality of service requirements for the MQTT connection.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String broker, final String topic, final int qos, final String username, final String password) {
        return Props.create(new Creator<MqttActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public MqttActor create() throws Exception {
                return new MqttActor(broker, topic, qos, username, password);
            }
        });
    }

    final String broker;
    final String topic;
    final int qos;
    final String clientId;
    final String content;
    final String username;
    final String password;
    MqttClient mqttClient;

    public MqttActor(String broker, String topic, int qos, String username, String password) throws MqttException {
        log.info("Constructor called with broker: {}, topic: {}, username: {}, password: {}", broker, topic, username, password);
        this.broker     = broker;
        this.topic      = topic;
        this.qos        = qos;
        this.username   = username;
        this.password   = password;
        this.clientId   = "client-" + UUID.randomUUID().toString();
        this.content    = "MQTT actor started successfully!";

        MemoryPersistence persistence = new MemoryPersistence();
        this.mqttClient = new MqttClient(broker, clientId, persistence);
    }

    @Override
    public void onReceive(Object message) throws MqttException {
        if (message instanceof DataportMain.MqttConnectMessage) {
            connect();
        }
        if (message instanceof DataportMain.MqttDisconnectMessage) {
            disconnect();
        }
        if (message instanceof DataportMain.MqttConnectionStatusMessage) {
            // TODO: when implemented as FSM, this should be generalized to ask for the actor state
            // and send back the enum holding the state, I think..
            getSender().tell(isConnected(), getSelf());
        }
        else {
            unhandled(message);
        }
    }

    private void disconnect() throws MqttException {
        mqttClient.disconnect();
    }

    private boolean connect() throws MqttException {
        // Set the callback function to be able to receive messages.
        mqttClient.setCallback(this);

        MqttConnectOptions connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
        if (username != null && password != null) {
            log.info("Username and password provided. Add them to connectionOptions");
            connectionOptions.setUserName(username);
            connectionOptions.setPassword(password.toCharArray());
        }

        log.info("Connecting to broker: {}", broker);
        mqttClient.connect(connectionOptions);

        mqttClient.subscribe(topic);
        log.info("Subscribed to topic: {}", topic);
        return mqttClient.isConnected();
//        } catch(MqttException me) {
//            log.info("reason "+me.getReasonCode());
//            log.info("msg "+me.getMessage());
//            log.info("loc "+me.getLocalizedMessage());
//            log.info("cause "+me.getCause());
//            log.info("excep "+me);
//            me.printStackTrace();
//        }
    }

    private boolean isConnected() {
        return mqttClient.isConnected();
    }

    @Override
    public void connectionLost(Throwable cause) {

    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        log.info("MQTT Received: '{}' on topic '{}'", message, topic);
        String in = message.toString();
        // TODO: Maybe do some filtering with the input?
        String out = in.toUpperCase();
        mediator.tell(new DistributedPubSubMediator.Publish(topic, out), getSelf());
        // OBS! Do not publish to same MQTT broker on same topic, as this will cause a loop!
        // Better to tell another actor a message was received, and let this actor publish to any relevant
        // MQTT topics it might be connected to?
//        mqttClient.publish(topic, new MqttMessage(content.getBytes()));
//        log.info("Published to topic: {}", topic);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }
}

