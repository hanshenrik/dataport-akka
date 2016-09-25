package no.ntnu.dataport;

import akka.actor.*;
import no.ntnu.dataport.types.ApplicationParameters;
import no.ntnu.dataport.types.Messages;
import no.ntnu.dataport.types.Position;
import no.ntnu.dataport.utils.SecretStuff;

import java.util.ArrayList;
import java.util.List;

public class DataportMain {

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");

        Props externalResourceSupervisorProps = Props.create(ExternalResourceSupervisorActor.class);
        final ActorRef externalResourceSupervisor = system.actorOf(externalResourceSupervisorProps, "externalResourceSupervisor");

        Props dbProps = DBActor.props(SecretStuff.INFLUXDB_URL, SecretStuff.INFLUXDB_USERNAME, SecretStuff.INFLUXDB_PASSWORD);
        final ActorRef db = system.actorOf(dbProps, "influxActor");

        // Create a list off the TTN applications we want to monitor
        List<ApplicationParameters> applications = new ArrayList<>();
        applications.add(new ApplicationParameters(
                "trondheim",
                SecretStuff.TRONDHEIM_APP_EUI,
                SecretStuff.TRONDHEIM_APP_KEY,
                new Position(63.430515, 10.395053)));
        applications.add(new ApplicationParameters(
                "vejle",
                SecretStuff.VEJLE_APP_EUI,
                SecretStuff.VEJLE_APP_KEY,
                new Position(55.711311, 9.536354)));

        // UGLY. This is to make sure the ExternalResourceSupervisor is created before we tell it which applications to create
        // TODO: Implement some message queue so it can queue messages if not fully created yet!
        try {
            Thread.sleep(1000);
        } catch (Exception e) {

        }

        for (ApplicationParameters params : applications) {
            system.actorOf(SiteActor.props(params.name, params.appEui, params.position), params.name);
            externalResourceSupervisor.tell(new Messages.MonitorApplicationMessage(
                    params.name,
                    params.appEui,
                    params.appKey,
                    params.position), ActorRef.noSender());
        }
    }
}
