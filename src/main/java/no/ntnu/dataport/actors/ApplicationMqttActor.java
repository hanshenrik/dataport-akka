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
import no.ntnu.dataport.DataportMain;
import no.ntnu.dataport.enums.MqttActorState;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;


public class ApplicationMqttActor extends MqttFSMBase implements MqttCallbackExtended {
    /**
     * Create Props for an actor of this type.
     * @param broker    The broker address this actor will connect to, tcp://staging.thethingsnetwork.org:1883 for TTN.
     * @param appEui    The appEui for the TTN application.
     * @param appKey    The appKey for the TTN application.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String broker, final String appEui, final String appKey) {
        return Props.create(new Creator<ApplicationMqttActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ApplicationMqttActor create() throws Exception {
                return new ApplicationMqttActor(broker, appEui, appKey);
            }
        });
    }

    final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    final ActorRef mediator = DistributedPubSub.get(getContext().system()).mediator();

    final String broker;
    final String appEui;
    final String appKey;
    final String applicationDevicesUpTopic;
    final MqttConnectOptions connectionOptions;
    final SlackApi slackAPI;
    final SlackMessage slackConnectionLostMessage;
    final SlackMessage slackReconnectedMessage;
    private final String slackAPIWebhook;

    public ApplicationMqttActor(String broker, String appEui, String appKey) throws MqttException {
        this.slackAPIWebhook = DataportMain.properties.getProperty("SLACK_API_WEBHOOK");
        this.broker = broker;
        this.appEui = appEui;
        this.appKey = appKey;
        this.applicationDevicesUpTopic = appEui + "/devices/+/up";

        this.slackAPI = new SlackApi(slackAPIWebhook);
        this.slackConnectionLostMessage = new SlackMessage("").addAttachments(new SlackAttachment()
                .setFallback("Lost connection to MQTT broker " + broker + " subscribing to application " + appEui + ". I'll let you know when its back up.")
                .setTitle("Lost connection to MQTT broker " + broker + " subscribing to application " + appEui)
                .setText("I'll let you know when its back up.")
                .setColor("danger"));
        this.slackReconnectedMessage = new SlackMessage("").addAttachments(new SlackAttachment()
                .setFallback("Reconnected to MQTT broker " + broker + " subscribing to application " + appEui)
                .setTitle("Reconnected to MQTT broker " + broker + " subscribing to application " + appEui)
                .setColor("good"));

        MqttClientPersistence persistence = new MemoryPersistence();

        MqttClient mqttClient = new MqttClient(broker, MqttClient.generateClientId(), persistence);

        // Set the callback function to be able to receive messages.
        mqttClient.setCallback(this);

        init(mqttClient);

        this.connectionOptions = new MqttConnectOptions();
        connectionOptions.setCleanSession(true);
        connectionOptions.setAutomaticReconnect(true);
        if (appEui != null && appKey != null) {
            log.debug("AppEui and AppKey provided. Add them to connectionOptions as username and password");
            connectionOptions.setUserName(appEui);
            connectionOptions.setPassword(appKey.toCharArray());
        }

        connect();
    }

    @Override
    public void onReceive(Object message) {
        log.debug("Got: {} from {}", message, getSender());
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
        log.error("Damn! I lost my MQTT connection. Paho's automatic reconnect with backoff kicking in because of: "+cause.getStackTrace());

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

        try {
            getMqttClient().subscribe(applicationDevicesUpTopic);
            log.info("Now subscribing to topic {}", applicationDevicesUpTopic);
        } catch (MqttException e) {
            log.error("Not able to subscribe to topic {}", applicationDevicesUpTopic);
            e.printStackTrace();
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
}
