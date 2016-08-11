package no.ntnu.dataport;

import akka.actor.*;
import akka.actor.dsl.Creators;
import akka.pattern.Backoff;
import akka.pattern.BackoffSupervisor;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public class DataportMain {

    public static class MqttConnectMessage implements Serializable {}
    public static class MqttDisconnectMessage implements Serializable {}
    public static class MqttConnectionStatusMessage implements Serializable {}

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");

        Props externalResourceSupervisorProps = Props.create(ExternalResourceSupervisorActor.class);
        final ActorRef externalResourceSupervisor = system.actorOf(externalResourceSupervisorProps, "externalResourceSupervisor");

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