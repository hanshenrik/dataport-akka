package no.ntnu.dataport;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Inbox;
import akka.actor.Props;

public class DataportMain {

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");

        // Create MQTT actors
        final ActorRef ttn = system.actorOf(MqttActor.props("tcp://dataport.item.ntnu.no:1883", "hh-test", 0), "ttn");
        final ActorRef ttnCroft = system.actorOf(MqttActor.props("tcp://croft.thethings.girovito.nl:1883", "nodes/+/packets", 0), "ttn-croft");
//        final ActorRef ttnStaging = system.actorOf(MqttActor.props("tcp://staging.thethingsnetwork.org:1883", "+/devices/+/up", 0), "ttn-staging");

        // Create the "actor-in-a-box"
        //final Inbox inbox = Inbox.create(system);

        // Tell the 'greeter' to change its 'greeting' message
        //greeter.tell(new WhoToGreet("akka"), ActorRef.noSender());
        //ttn.tell(new MqttConfigMessage(("tcp://dataport.item.ntnu.no:1883")), ActorRef.noSender());

        // Ask the 'greeter for the latest 'greeting'
        // Reply should go to the "actor-in-a-box"
        //inbox.send(greeter, new Greet());
        //inbox.send(ttn, new MqttConnectMessage());

        final ActorRef trondheim = system.actorOf(SiteActor.props("trondheim", 63.430515, 10.395053), "site-trondheim");
        final ActorRef vejle = system.actorOf(SiteActor.props("vejle", 55.711311, 9.536354), "site-vejle");

        final ActorRef subscriber1 = system.actorOf(Props.create(Subscriber.class), "subscriber1");
        final ActorRef publisher1 = system.actorOf(Props.create(Publisher.class), "publisher1");
        publisher1.tell("hello", null);
    }

}