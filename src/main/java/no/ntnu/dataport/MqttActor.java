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

public class MqttActor extends MqttFSMBase implements MqttCallbackExtended {
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

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();

    // DEV
    private static boolean INTERNAL_FAULT_HANDLING = false;

    final String broker;
    final String topic;
    final int qos;
    final String clientId;
    final String content;
    final String username;
    final String password;
    final MqttConnectOptions connectionOptions;
//    MqttClient mqttClient;

    @Override
    public void preStart() {
        log.info("Yo!");
    }

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
        MqttClient mqttClient = new MqttClient(broker, clientId, persistence);

        // Set the callback function to be able to receive messages.
        mqttClient.setCallback(this);

        init(mqttClient);

        this.connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
        if (INTERNAL_FAULT_HANDLING) {
            connectionOptions.setAutomaticReconnect(true);
        }
        if (username != null && password != null) {
            log.info("Username and password provided. Add them to connectionOptions");
            connectionOptions.setUserName(username);
            connectionOptions.setPassword(password.toCharArray());
        }

        connect();
    }

    @Override
    public void onReceive(Object message) throws Exception {
        log.info("Got: {} from {}", message, getSender());
        if (message instanceof DataportMain.MqttConnectionStatusMessage) {
            getSender().tell(getState(), getSelf());
        }
        else if (message instanceof MqttException && getSender() == getSelf()) {
            throw (MqttException) message;
        }
        else {
            unhandled(message);
        }
    }

    private void disconnect() throws MqttException {
        getMqttClient().disconnect();
        setState(State.DISCONNECTED);
        log.info("Disconnected from MQTT broker");
    }

    private void connect() throws MqttException {
        setState(State.CONNECTING);
        log.info("Connecting to broker: {}", broker);
        if (INTERNAL_FAULT_HANDLING) {
            try {
                getMqttClient().connect(connectionOptions);
                getMqttClient().subscribe(topic);
                log.info("Subscribed to topic: {}", topic);
                // TODO: Set state to CONNECTED
            } catch(MqttException me) {
                switch (me.getReasonCode()) {
                    case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                        log.error("REASON_CODE_SERVER_CONNECT_ERROR: {}", me.getMessage());
                        break;
                    default:
                        log.error(me, "Unhandled exception!!");
                }
            }        // TODO: Set state to DISCONNECTED

        }
        else {
            getMqttClient().connect(connectionOptions);
            getMqttClient().subscribe(topic);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        setState(State.DISCONNECTED);
        getContext().parent().tell("I lost my MQTT connection!", getSelf());
        // TODO: Can we get this to just throw the exception, so that it is handled in the supervisors
        // SupervisorStragegy? Sending an excplicit message for this failure is probably not the cleanest
        // approach...
        // TODO: Set state to TRYING_TO_CONNECT
        // TODO: Tell parent i changed state (always do this?)
        if (INTERNAL_FAULT_HANDLING) {
            log.error(cause, "Lost MQTT connection! MqttClient's automatic reconnect kicking in");
            // do nothing, Paho automatic retry should be set to true
        } else {
            // Send exception as message to self and throw it there, since this method doesn't allow throwing exceptions
            getSelf().tell(new MqttException(cause), getSelf());
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        setState(State.CONNECTED);
        // TODO: set state to connected again
        if (reconnect) {
            getContext().parent().tell("I RECONNETED to my MQTT broker!", getSelf());
        } else {
            getContext().parent().tell("I connected to my MQTT broker for the first time", getSelf());
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
//        getMqttClient().publish(topic, new MqttMessage(content.getBytes()));
//        log.info("Published to topic: {}", topic);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }

    @Override
    protected void transition(State old, State next) {
        getContext().parent().tell("Changed from state: "+old+" to "+next, getSelf());
    }
}
