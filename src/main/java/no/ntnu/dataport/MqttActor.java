package no.ntnu.dataport;

import akka.actor.*;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

public class MqttActor extends UntypedActor implements MqttCallbackExtended {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    // activate the extension
    ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();

    @Override
    public void preStart() {
        log.info("Yo!");
    }

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
    final MqttConnectOptions connectionOptions;
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

        // Set the callback function to be able to receive messages.
        mqttClient.setCallback(this);

        this.connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
        connectionOptions.setAutomaticReconnect(true);
        if (username != null && password != null) {
            log.info("Username and password provided. Add them to connectionOptions");
            connectionOptions.setUserName(username);
            connectionOptions.setPassword(password.toCharArray());
        }
    }

    @Override
    public void onReceive(Object message) throws Exception {
//        log.info("Got: {} from {}", message, getSender());
        if (message instanceof DataportMain.MqttConnectMessage) {
            connect();
        }
        else if (message instanceof DataportMain.MqttDisconnectMessage) {
            disconnect();
        }
        else if (message instanceof DataportMain.MqttConnectionStatusMessage) {
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
        // TODO: Set state to DISCONNECTED
    }

    private void connect() throws MqttException {
        log.info("Connecting to broker: {}", broker);
        try {
            mqttClient.connect(connectionOptions);
            mqttClient.subscribe(topic);
            log.info("Subscribed to topic: {}", topic);
            // TODO: Set state to CONNECTED
        } catch(MqttException me) {
            switch (me.getReasonCode()) {
                case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                    log.error("REASON_CODE_SERVER_CONNECT_ERROR: {}", me.getMessage());
                    break;
                default:
                    throw me;
            }
        }
    }

    // TODO: Don't use this when FSM, use state instead
    private boolean isConnected() {
        return mqttClient.isConnected();
    }

    @Override
    public void connectionLost(Throwable cause) {
        // TODO: Can we get this to just throw the exception, so that it is handled in the supervisors
        // SupervisorStragegy? Sending an excplicit message for this failure is probably not the cleanest
        // approach...
        log.error(cause, "Lost MQTT connection! MqttClient's automatic reconnect kicking in");
        // TODO: Set state to TRYING_TO_CONNECT
        // TODO: Tell parent i changed state (always do this?)

//        getSelf().tell(Kill.getInstance(), getSelf());
//        getContext().parent().tell(new MqttException(cause), getSender());
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            // TODO: set state to connected again
            getContext().parent().tell("I'm connected again!", getSelf());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
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

