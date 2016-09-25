package no.ntnu.dataport.actors;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import com.fatboyindustrial.gsonjodatime.Converters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import no.ntnu.dataport.enums.MqttActorState;
import no.ntnu.dataport.types.GatewayData;
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.types.NetworkComponent;
import no.ntnu.dataport.types.SensorData;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PublishingMqttActor extends MqttFSMBase implements MqttCallbackExtended {
    /**
     * Create Props for an actor of this type.
     * @param broker    The broker address to be passed to this actor’s constructor.
     * @param username  The username if the broker needs that. Use 'null' if not protected
     * @param password  The password if the broker needs that. Use 'null' if not protected
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String broker, final String username, final String password) {
        return Props.create(new Creator<PublishingMqttActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public PublishingMqttActor create() throws Exception {
                return new PublishingMqttActor(broker, username, password);
            }
        });
    }

    final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    final ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();
    final Pattern pattern = Pattern.compile("dataport/site/(.*?)/(sensor|gateway)/(.*?)/events/(reception|status)");
    final Gson gson;

    final String broker;
    final String username;
    final String password;
    final String siteGraphsTopic;
    final String systemStatusTopic;
    final MqttConnectOptions connectionOptions;
    Set<String> currentDevicesMonitored;

    public PublishingMqttActor(String broker, String username, String password) throws MqttException {
        this.broker     = broker;
        this.username   = username;
        this.password   = password;
        this.connectionOptions = new MqttConnectOptions();
        this.siteGraphsTopic = "dataport/site/graphs";
        this.systemStatusTopic = "dataport/system/status";
        this.currentDevicesMonitored = new HashSet<>();
        this.gson = Converters.registerDateTime(new GsonBuilder()).create();
        MqttClientPersistence persistence = new MemoryPersistence();

        MqttClient mqttClient = new MqttClient(broker, MqttClient.generateClientId(), persistence);

        // Set the callback function to be able to receive messages.
        mqttClient.setCallback(this);

        init(mqttClient);

        connectionOptions.setCleanSession(true);
        connectionOptions.setAutomaticReconnect(true);
        if (username != null && password != null) {
            log.debug("Username and password provided. Add them to connectionOptions");
            connectionOptions.setUserName(username);
            connectionOptions.setPassword(password.toCharArray());
        }

        connect();
    }

    @Override
    public void onReceive(Object message) throws MqttException {
//        log.info("Got: {} from {}", message, getSender());
        if (getState() == MqttActorState.CONNECTED) {
            if (message instanceof Observation) {
                String topic = "dataport/site/" + sender().path().parent().name() + "/sensor/" + sender().path().name() + "/events/reception";
                MqttMessage mqttMessage = new MqttMessage(gson.toJson(message).getBytes());
                getMqttClient().publish(topic, mqttMessage);
            }
            else if (message instanceof SensorData) {
                String topic = "dataport/site/" + sender().path().parent().name() + "/sensor/" + sender().path().name() + "/events/status";
                MqttMessage mqttMessage = new MqttMessage(gson.toJson(message).getBytes());
                getMqttClient().publish(topic, mqttMessage);
            }
            else if (message instanceof GatewayData) {
                String topic = "dataport/site/" + sender().path().parent().name() + "/gateway/" + sender().path().name() + "/events/status";
                MqttMessage mqttMessage = new MqttMessage(gson.toJson(message).getBytes());
                getMqttClient().publish(topic, mqttMessage);
            }
            else if (message instanceof NetworkGraphMessage) {

                // Make sure I am subscribing to all components present in the network.
                for (NetworkComponent device : ((NetworkGraphMessage) message).graph.values()) {
                    if (!currentDevicesMonitored.contains(device.getEui())) {
                        switch (device.getType()) {
                            case GATEWAY:
                                String internalGatewayStatusTopic = "dataport/site/" + sender().path().name() + "/gateway/" + device.getEui() + "/events/status";
                                mediator.tell(new DistributedPubSubMediator.Subscribe(internalGatewayStatusTopic, self()), self());
                                break;
                            case SENSOR:
                                String internalSensorStatusTopic = "dataport/site/" + sender().path().name() + "/sensor/" + device.getEui() + "/events/status";
                                String internalSensorReceptionTopic = "dataport/site/" + sender().path().name() + "/sensor/" + device.getEui() + "/events/reception";
                                mediator.tell(new DistributedPubSubMediator.Subscribe(internalSensorStatusTopic, self()), self());
                                mediator.tell(new DistributedPubSubMediator.Subscribe(internalSensorReceptionTopic, self()), self());
                                break;
                            default:
                                log.error("Unknown type {}, won't subscribe or publish anything", device.getType());
                        }
                    }
                }

                // Publish the network graph message to my MQTT Broker
                String externalPublishTopic = "dataport/site/" + sender().path().name() + "/graph";
                MqttMessage mqttMessage = new MqttMessage(gson.toJson(((NetworkGraphMessage) message).graph.values()).getBytes());
                mqttMessage.setRetained(true);
                getMqttClient().publish(externalPublishTopic, mqttMessage);
            }
        }
        else {
            log.error("Can't handle {} when I'm in state {}. It will not be handled!", message.getClass(), getState());
            unhandled(message);
        }

        if (message instanceof DistributedPubSubMediator.SubscribeAck) {
            String topic = ((DistributedPubSubMediator.SubscribeAck) message).subscribe().topic();
            log.info("SubscribeAck on topic: {}", topic);
            Matcher matcher = pattern.matcher(topic);
            if (matcher.matches()) {
                currentDevicesMonitored.add(matcher.group(3));
            }
        }
        else if (message instanceof DistributedPubSubMediator.UnsubscribeAck) {
            String topic = ((DistributedPubSubMediator.UnsubscribeAck) message).unsubscribe().topic();
            log.info("UnsubscribeAck on topic: {}", topic);
            return;
        }
        else if (message instanceof MqttException && getSender() == getSelf()) {
            throw (MqttException) message;
        }
        else {
            unhandled(message);
        }
    }

    @Override
    protected void transition(MqttActorState old, MqttActorState next) {
        log.info("Going from {} to {}", old, next);
    }

    private void connect() throws MqttException {
        setState(MqttActorState.CONNECTING);
        getMqttClient().connect(connectionOptions);

        log.info("Connecting to broker: {}", broker);
    }

    @Override
    public void connectionLost(Throwable cause) {
        setState(MqttActorState.CONNECTING);

        mediator.tell(new DistributedPubSubMediator.Unsubscribe(siteGraphsTopic, self()), self());

        log.info("Damn! I lost my MQTT connection. Paho's automatic reconnect with backoff kicking in because of: "+cause.getStackTrace());
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        setState(MqttActorState.CONNECTED);
        if (reconnect) {
            log.info("Yeah! I reconnected to my MQTT broker");
        } else {
            log.info("Yeah! I connected to my MQTT broker for the first time");
        }

        mediator.tell(new DistributedPubSubMediator.Subscribe(siteGraphsTopic, self()), self());
        mediator.tell(new DistributedPubSubMediator.Subscribe(systemStatusTopic, self()), self());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        log.error("Received MQTT message from broker, but I should not subscribe to any topics, so this shouldn't have happened! Won't do anything with this message");
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }
}
