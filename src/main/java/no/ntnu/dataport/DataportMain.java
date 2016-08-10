package no.ntnu.dataport;

import akka.actor.*;

import java.io.Serializable;

public class DataportMain {

    public static class MqttConnectMessage implements Serializable {}
    public static class MqttDisconnectMessage implements Serializable {}
    public static class MqttConnectionStatusMessage implements Serializable {}

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");

        // Create MQTT actors
        Props dataportProps = MqttActor.props("tcp://dataport.item.ntnu.no:1883", "hh-test", 0, null, null);
        Props croftNodeProps = MqttActor.props("tcp://croft.thethings.girovito.nl:1883", "nodes/17F979AC/packets", 0, null, null);
        Props croftGatewayProps = MqttActor.props("tcp://croft.thethings.girovito.nl:1883", "gateways/1DEE026E0BBE6E66/status", 0, null, null);
        Props stagingProps = MqttActor.props("tcp://staging.thethingsnetwork.org:1883", "+/devices/+/up", 0, "user", "password");
        final ActorRef mqttDataport = system.actorOf(dataportProps, "dataport");
        final ActorRef mqttTTNCroftNodes = system.actorOf(croftNodeProps, "ttn-croft-nodes");
        final ActorRef mqttTTNCroftGateways = system.actorOf(croftGatewayProps, "ttn-croft-gateways");
        final ActorRef mqttStaging = system.actorOf(stagingProps, "ttn-staging");
        mqttDataport.tell(new MqttConnectMessage(), ActorRef.noSender());
        mqttDataport.tell(new MqttDisconnectMessage(), ActorRef.noSender());
        mqttTTNCroftNodes.tell(new MqttConnectMessage(), ActorRef.noSender());
        mqttTTNCroftGateways.tell(new MqttConnectMessage(), ActorRef.noSender());
        mqttStaging.tell(new MqttConnectMessage(), ActorRef.noSender());

        // Create the "actor-in-a-box"
        //final Inbox inbox = Inbox.create(system);

        // Tell the 'greeter' to change its 'greeting' message
        //greeter.tell(new WhoToGreet("akka"), ActorRef.noSender());

        // Ask the 'greeter for the latest 'greeting'
        // Reply should go to the "actor-in-a-box"
        //inbox.send(greeter, new Greet());
        //inbox.send(ttn, new MqttConnectMessage());

        final ActorRef trondheim = system.actorOf(SiteActor.props("trondheim", 63.430515, 10.395053), "site-trondheim");
        final ActorRef vejle = system.actorOf(SiteActor.props("vejle", 55.711311, 9.536354), "site-vejle");

//        final ActorRef subscriber1 = system.actorOf(Props.create(Subscriber.class), "subscriber1");
//        final ActorRef publisher1 = system.actorOf(Props.create(Publisher.class), "publisher1");
//        publisher1.tell("hello", null);
    }

}