package no.ntnu.dataport.actors;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackAttachment;
import net.gpedro.integrations.slack.SlackMessage;
import no.ntnu.dataport.enums.MqttActorState;
import no.ntnu.dataport.enums.DeviceType;
import no.ntnu.dataport.types.Messages;
import no.ntnu.dataport.types.NetworkComponent;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GatewayStatusMqttActor extends MqttFSMBase implements MqttCallbackExtended {

    /**
     * Create Props for an actor of this type.
     * @param broker    The broker address to be passed to this actor’s constructor.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String broker) {
        return Props.create(new Creator<GatewayStatusMqttActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public GatewayStatusMqttActor create() throws Exception {
                return new GatewayStatusMqttActor(broker);
            }
        });
    }

    final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    final ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();
    final Pattern pattern = Pattern.compile("dataport/site/(.*?)/(sensor|gateway)/(.*?)/events/(reception|status)");


    final String broker;
    final String siteGraphsTopic;
    final MqttConnectOptions connectionOptions;
    final SlackApi slackAPI;
    final SlackMessage slackConnectionLostMessage;
    final SlackMessage slackReconnectedMessage;
    Set<String> currentDevicesMonitored;

    public GatewayStatusMqttActor(String broker) throws MqttException {
        this.broker = broker;
        this.currentDevicesMonitored = new HashSet<>();
        this.siteGraphsTopic = "dataport/site/graphs";

        this.slackAPI = new SlackApi(SecretStuff.SLACK_API_WEBHOOK);
        this.slackConnectionLostMessage = new SlackMessage("").addAttachments(new SlackAttachment()
                .setFallback("Lost connection to MQTT broker " + broker + ". I'll let you know when its back up.")
                .setTitle("Lost connection to MQTT broker " + broker)
                .setText("I'll let you know when its back up.")
                .setColor("danger"));
        this.slackReconnectedMessage = new SlackMessage("").addAttachments(new SlackAttachment()
                .setFallback("Reconnected to MQTT broker " + broker)
                .setTitle("Reconnected to MQTT broker " + broker)
                .setColor("good"));

        MqttClientPersistence persistence = new MemoryPersistence();


        MqttClient mqttClient = new MqttClient(broker, MqttClient.generateClientId(), persistence);

        // Set the callback function to be able to receive messages.
        mqttClient.setCallback(this);

        init(mqttClient);

        this.connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
        connectionOptions.setAutomaticReconnect(true);

        connect();
    }

    @Override
    public void onReceive(Object message) throws MqttException {
        log.debug("Got: {} from {}", message, getSender());
        if (message instanceof DistributedPubSubMediator.SubscribeAck) {
            String topic = ((DistributedPubSubMediator.SubscribeAck) message).subscribe().topic();
            log.info("SubscribeAck on topic: {}", topic);
            Matcher matcher = pattern.matcher(topic);
            if (matcher.matches()) {
                currentDevicesMonitored.add(matcher.group(3));
            }
        }
        if (getState() == MqttActorState.CONNECTED) {
            if (message instanceof Messages.NetworkGraphMessage) {

                // Make sure I am subscribing to all components present in the network
                for (NetworkComponent device : ((Messages.NetworkGraphMessage) message).graph.values()) {
                    if (!currentDevicesMonitored.contains(device.getEui())) {
                        if (device.getType() == DeviceType.GATEWAY) {
                            String externalTopic = "gateways/" + device.getEui() + "/status";
                            getMqttClient().subscribe(externalTopic);
                        }
                    }
                }
            }
        }
        else {
            log.error("Can't handle {} when I'm in state {}. It will not be handled!", message.getClass(), getState());
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

    /* Paho implementation */
    @Override
    public void connectionLost(Throwable cause) {
        setState(MqttActorState.CONNECTING);
        log.info("Damn! I lost my MQTT connection. Paho's automatic reconnect with backoff kicking in because of: "+cause.getStackTrace());

        // Send alert to Slack
        slackAPI.call(slackConnectionLostMessage);
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        setState(MqttActorState.CONNECTED);
        if (reconnect) {
            log.info("Phew! I reconnected to my MQTT broker");

            // Send alert to Slack
            slackAPI.call(slackReconnectedMessage);
        } else {
            log.info("Yeah! I connected to my MQTT broker for the first time");
        }

        mediator.tell(new DistributedPubSubMediator.Subscribe(siteGraphsTopic, self()), self());
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
}
