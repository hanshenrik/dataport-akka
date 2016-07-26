package no.ntnu.dataport;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;
import akka.actor.Inbox;
import akka.actor.Props;

public class DataportMain {

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");

        // Create the 'ttn' actor
        final ActorRef ttn = system.actorOf(TTNActor.props("tcp://dataport.item.ntnu.no:1883", "hh-test", 0), "ttn");

        // Create the "actor-in-a-box"
        //final Inbox inbox = Inbox.create(system);

        // Tell the 'greeter' to change its 'greeting' message
        //greeter.tell(new WhoToGreet("akka"), ActorRef.noSender());
        //ttn.tell(new MqttConfigMessage(("tcp://dataport.item.ntnu.no:1883")), ActorRef.noSender());

        // Ask the 'greeter for the latest 'greeting'
        // Reply should go to the "actor-in-a-box"
        //inbox.send(greeter, new Greet());
        //inbox.send(ttn, new MqttConnectMessage());

        ActorRef subscriber1 = system.actorOf(Props.create(Subscriber.class), "subscriber1");
        ActorRef publisher1 = system.actorOf(Props.create(Publisher.class), "publisher1");
        publisher1.tell("hello", null);
    }

}