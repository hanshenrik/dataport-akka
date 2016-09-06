package no.ntnu.dataport;

import akka.actor.*;
import no.ntnu.dataport.types.Position;
import no.ntnu.dataport.utils.SecretStuff;

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

        final ActorRef trondheim = system.actorOf(SiteActor.props("trondheim", "+", new Position(63.430515, 10.395053)), "trondheim");
        final ActorRef vejle = system.actorOf(SiteActor.props("vejle", SecretStuff.VEJLE_APP_EUI, new Position(55.711311, 9.536354)), "vejle");
    }
}
