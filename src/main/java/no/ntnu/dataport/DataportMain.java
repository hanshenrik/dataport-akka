package no.ntnu.dataport;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import no.ntnu.dataport.actors.ExternalResourceSupervisorActor;
import no.ntnu.dataport.actors.SiteActor;
import no.ntnu.dataport.types.ApplicationParameters;
import no.ntnu.dataport.types.Messages;
import no.ntnu.dataport.types.Position;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class DataportMain {

    public static Properties properties;

    public static void main(String[] args) {
        final ActorSystem system = ActorSystem.create("DataportActorSystem");
        LoggingAdapter log = Logging.getLogger(system, "DataportMain");

        properties = new Properties();
        try {
            InputStream is = DataportMain.class.getResourceAsStream("/application.properties");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            properties.load(reader);
        }
        catch (Exception e) {
            log.error("Failed to load application.properties -- did you remember to add it?");
            log.error("Cannot start system without properties, shutting down...");
            system.shutdown();
            return;
        }

        Props externalResourceSupervisorProps = Props.create(ExternalResourceSupervisorActor.class);
        final ActorRef externalResourceSupervisor = system.actorOf(externalResourceSupervisorProps, "externalResourceSupervisor");

        // Create a list off the TTN applications we want to monitor
        List<ApplicationParameters> applications = new ArrayList<>();
        applications.add(new ApplicationParameters(
                "Trondheim",
                properties.getProperty("TRONDHEIM_APP_EUI"),
                properties.getProperty("TRONDHEIM_APP_KEY"),
                new Position(63.430515, 10.395053)));
        applications.add(new ApplicationParameters(
                "Vejle",
                properties.getProperty("VEJLE_APP_EUI"),
                properties.getProperty("VEJLE_APP_KEY"),
                new Position(55.711311, 9.536354)));

        for (ApplicationParameters params : applications) {
            system.actorOf(SiteActor.props(params.name, params.appEui, params.position), params.name);

            // Create actors that listens to messages from the application's sensors from TTNs broker
            externalResourceSupervisor.tell(new Messages.MonitorApplicationMessage(
                    params.name,
                    params.appEui,
                    params.appKey,
                    params.position), ActorRef.noSender());
        }
    }
}
