package no.ntnu.dataport;

import akka.actor.*;
import akka.actor.dsl.Creators;
import akka.pattern.Backoff;
import akka.pattern.BackoffSupervisor;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

public class DataportMain {

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");

        Props externalResourceSupervisorProps = Props.create(ExternalResourceSupervisorActor.class);
        final ActorRef externalResourceSupervisor = system.actorOf(externalResourceSupervisorProps, "externalResourceSupervisor");

        // UGLY. This is to make sure MqttActors are created before we tell them which topics to subscribe to.
        // TODO: Implement some message queue at MqttActors so they queue messages if their not in correct state!
        try {
            Thread.sleep(5000);
        } catch (Exception e) {

        }
        final ActorRef trondheim = system.actorOf(SiteActor.props("trondheim", 63.430515, 10.395053), "site-trondheim");
        final ActorRef vejle = system.actorOf(SiteActor.props("vejle", 55.711311, 9.536354), "site-vejle");
    }
}
