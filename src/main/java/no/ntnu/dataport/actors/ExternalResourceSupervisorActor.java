package no.ntnu.dataport.actors;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffOptions;
import akka.pattern.BackoffSupervisor;
import no.ntnu.dataport.types.Messages;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.MqttException;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static akka.actor.SupervisorStrategy.*;


public class ExternalResourceSupervisorActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    private List<ActorRef> monitoredApplications;

    public static Props props() {
        return Props.create(new Creator<ExternalResourceSupervisorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ExternalResourceSupervisorActor create() throws Exception {
                return new ExternalResourceSupervisorActor();
            }
        });
    }

    public ExternalResourceSupervisorActor() {
        this.monitoredApplications = new ArrayList<>();

        // Props for the (unsupported, but still going strong) TTN gateway status broker
        Props ttnGatewayStatusBrokerProps = GatewayStatusMqttActor.props("tcp://croft.thethings.girovito.nl:1883");
        BackoffOptions ttnGatewayStatusBrokerBackoffOptions = Backoff.onFailure(
                ttnGatewayStatusBrokerProps,
                "ttnGatewayStatusBroker",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);
        final Props ttnGatewayStatusBrokerSupervisorProps = BackoffSupervisor.props(ttnGatewayStatusBrokerBackoffOptions);


        // Props for the broker that publishes status and reception messages
        Props dataportBrokerProps = PublishingMqttActor.props("tcp://dataport.item.ntnu.no:1883", null, null);
        BackoffOptions dataportBrokerBackoffOptions= Backoff.onFailure(
                dataportBrokerProps,
                "dataportBroker",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
            ).withSupervisorStrategy(mqttActorStrategy);
        final Props dataportBrokerSupervisorProps = BackoffSupervisor.props(dataportBrokerBackoffOptions);


        // Props for the DB where data is dumped
        Props dbProps = DBActor.props(SecretStuff.INFLUXDB_URL, SecretStuff.INFLUXDB_USERNAME, SecretStuff.INFLUXDB_PASSWORD, SecretStuff.INFLUXDB_DBNAME);
        BackoffOptions dbActorBackoffOptions = Backoff.onFailure(
                dbProps,
                "influxDBActor",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        );
        final Props dbSupervisorProps = BackoffSupervisor.props(dbActorBackoffOptions);


        // Props for the actor retrieving weather forecast data from api.met.no
        final Props weatherForecastProps = WeatherForecastActor.props();


        // Props for the actor retrieving uv forecast data from api.met.no
        final Props uvForecastProps = UVForecastActor.props();


        // Props for the actor retrieving sunrise/sunset times from api.met.no
        final Props sunForecastProps = SunForecastActor.props();


        // Start all the actors
        context().actorOf(ttnGatewayStatusBrokerSupervisorProps, "ttnGatewayStatusBrokerSupervisor");
        context().actorOf(dataportBrokerSupervisorProps, "dataportBrokerSupervisor");
        context().actorOf(dbSupervisorProps, "influxDBActorSupervisor");
        context().actorOf(weatherForecastProps, "weatherForecast");
        context().actorOf(uvForecastProps, "uvForecast");
        context().actorOf(sunForecastProps, "sunForecast");
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        log.debug("Received {} from {}", message, getSender());
        if (message instanceof Messages.MonitorApplicationMessage) {
            String actorName = "ttn-" + ((Messages.MonitorApplicationMessage) message).name + "-broker";
            Props applicationBrokerProps = ApplicationMqttActor.props(
                    "tcp://staging.thethingsnetwork.org:1883",
                    ((Messages.MonitorApplicationMessage) message).appEui,
                    ((Messages.MonitorApplicationMessage) message).appKey);

            BackoffOptions applicationBrokerBackoffOptions = Backoff.onFailure(
                    applicationBrokerProps,
                    actorName,
                    Duration.create(3, TimeUnit.SECONDS),
                    Duration.create(2, TimeUnit.MINUTES),
                    0.2 // add 20% "noise" to vary the intervals slightly
            ).withSupervisorStrategy(mqttActorStrategy);

            final Props supervisorProps = BackoffSupervisor.props(applicationBrokerBackoffOptions);

            ActorRef app = getContext().actorOf(supervisorProps, actorName + "-supervisor");

            monitoredApplications.add(app);
        }
        else {
            unhandled(message);
        }
    }

    /**
     * Supervision strategy for all Actors extending MqttFSMBase.
     */
    private OneForOneStrategy mqttActorStrategy =
        new OneForOneStrategy(
            DeciderBuilder.
                match(ArithmeticException.class, e -> {
                    System.out.println("ArithmeticException: " + e.getMessage());
                    return resume();
                }).
                match(NullPointerException.class, e -> {
                    System.out.println("NullPointerException: " + e.getMessage());
                    return restart();
                }).
                match(ActorInitializationException.class, e -> {
                    System.out.println("ActorInitException: " + e.getMessage());
                    return restart();
                }).
                match(MqttException.class, e -> {
                    System.out.println("MqttException: " + e.getMessage());
                    return restart();
                }).
                match(ActorKilledException.class, e -> {
                    System.out.println("ActorKilledException: " + e.getMessage());
                    return restart();
                }).
                matchAny(o -> {
                    System.out.println("Unexpected failure: " + o.getMessage());
                    return escalate();
                }).build());
}
