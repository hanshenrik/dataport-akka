package no.ntnu.dataport;

import akka.actor.*;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.cluster.pubsub.DistributedPubSubMessage;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import no.ntnu.dataport.types.Messages.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

public class MqttActor extends MqttFSMBase implements MqttCallbackExtended {
    /**
     * Create Props for an actor of this type.
     * @param broker    The broker address to be passed to this actor’s constructor.
     * @param qos       The quality of service requirements for the MQTT connection.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String broker, final int qos, final String username, final String password) {
        return Props.create(new Creator<MqttActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public MqttActor create() throws Exception {
                return new MqttActor(broker, qos, username, password);
            }
        });
    }

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();

    // DEV
    private static boolean INTERNAL_FAULT_HANDLING = false;

    final String broker;
    final int qos;
    final String clientId;
    final String content;
    final String username;
    final String password;
    final MqttConnectOptions connectionOptions;

    @Override
    public void preStart() {

    }

    public MqttActor(String broker, int qos, String username, String password) throws MqttException {
        log.debug("Constructor called with broker: {}, username: {}, password: {}", broker, username, password);
        this.broker     = broker;
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
            log.debug("Username and password provided. Add them to connectionOptions");
            connectionOptions.setUserName(username);
            connectionOptions.setPassword(password.toCharArray());
        }

        connect();
    }

    @Override
    public void onReceive(Object message) throws Exception {
        log.debug("Got: {} from {}", message, getSender());
        if (message instanceof MqttConnectionStatusMessage) {
            getSender().tell(getState(), getSelf());
        }
        else if (message instanceof MqttSubscribeMessage) {
            getMqttClient().subscribe(((MqttSubscribeMessage) message).topic);
            log.debug("Now subscribing to topic {}", ((MqttSubscribeMessage) message).topic);
        }
        else if (message instanceof MqttException && getSender() == getSelf()) {
            throw (MqttException) message;
        }
        else if (message instanceof SubscribeToInternalTopicMessage) {
            mediator.tell(new DistributedPubSubMediator.Subscribe(((SubscribeToInternalTopicMessage) message).topic, self()), self());
        }
        else if (message instanceof DistributedPubSubMediator.SubscribeAck) {
            log.debug("### ACK for subscribing");
        }
        else if (message instanceof MqttPublishMessage) {
            getMqttClient().publish(((MqttPublishMessage) message).topic, ((MqttPublishMessage) message).mqttMessage);
        }
        else if (message instanceof NetworkGraphMessage) {
            String topic = ((NetworkGraphMessage) message).topic;
            MqttMessage mqttMessage = new MqttMessage(((NetworkGraphMessage) message).graph.getBytes());

            mqttMessage.setRetained(true);
            getMqttClient().publish(topic, mqttMessage);
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
            } catch(MqttException me) {
                switch (me.getReasonCode()) {
                    case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                        log.error("REASON_CODE_SERVER_CONNECT_ERROR: {}", me.getMessage());
                        break;
                    default:
                        log.error(me, "Unhandled exception!!");
                }
                setState(State.DISCONNECTED);
            }

        }
        else {
            getMqttClient().connect(connectionOptions);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        setState(State.DISCONNECTED);
        getContext().parent().tell("I lost my MQTT connection!", getSelf());
        // TODO: Can we get this to just throw the exception, so that it is handled in the supervisors
        // SupervisorStragegy? Sending an excplicit mqttMessage for this failure is probably not the cleanest
        // approach...
        // TODO: Set state to TRYING_TO_CONNECT
        // TODO: Tell parent i changed state (always do this?)
        if (INTERNAL_FAULT_HANDLING) {
            log.error(cause, "Lost MQTT connection! MqttClient's automatic reconnect kicking in");
            // do nothing, Paho automatic retry should be set to true
        } else {
            // Send exception as mqttMessage to self and throw it there, since this method doesn't allow throwing exceptions
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
        String internalTopic = "external/" + topic;
        log.debug("MQTT Received on topic {} message: {}. Publishing on internal topic {}", topic, message, internalTopic);
        mediator.tell(new DistributedPubSubMediator.Publish(internalTopic, message), getSelf());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }

    @Override
    protected void transition(State old, State next) {
        getContext().parent().tell("Changed from state: "+old+" to "+next, getSelf());
    }
}

