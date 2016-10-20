package no.ntnu.dataport.actors;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffOptions;
import akka.pattern.BackoffSupervisor;
import no.ntnu.dataport.DataportMain;
import no.ntnu.dataport.types.Messages;
import no.ntnu.dataport.types.Position;
import org.eclipse.paho.client.mqttv3.MqttException;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static akka.actor.SupervisorStrategy.*;


public class ExternalResourceSupervisorActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    private List<ActorRef> monitoredApplications;
    private List<ActorRef> forecastActors;

    public static Props props() {
        return Props.create(new Creator<ExternalResourceSupervisorActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public ExternalResourceSupervisorActor create() throws Exception {
                return new ExternalResourceSupervisorActor();
            }
        });
    }

    private final String influxDBURL;
    private final String influxDBUsername;
    private final String influxDBPassword;
    private final String influxDBName;

    public ExternalResourceSupervisorActor() {
        this.influxDBURL        = DataportMain.properties.getProperty("INFLUXDB_URL");
        this.influxDBUsername   = DataportMain.properties.getProperty("INFLUXDB_USERNAME");
        this.influxDBPassword   = DataportMain.properties.getProperty("INFLUXDB_PASSWORD");
        this.influxDBName       = DataportMain.properties.getProperty("INFLUXDB_DBNAME");

        this.monitoredApplications = new ArrayList<>();
        this.forecastActors = new ArrayList<>();

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
        Props dbProps = DBActor.props(influxDBURL, influxDBUsername, influxDBPassword, influxDBName);
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


        // Create all the actors
        context().actorOf(ttnGatewayStatusBrokerSupervisorProps, "ttnGatewayStatusBrokerSupervisor");
        context().actorOf(dataportBrokerSupervisorProps, "dataportBrokerSupervisor");
        context().actorOf(dbSupervisorProps, "influxDBActorSupervisor");

        // Add forecast actors to list, will be created per city when I receive message to monitor application in a city
        ActorRef weatherForecastActor = context().actorOf(weatherForecastProps, "weatherForecast");
        ActorRef uvForecastActor = context().actorOf(uvForecastProps, "uvForecast");
        ActorRef sunForecastActor = context().actorOf(sunForecastProps, "sunForecast");
        forecastActors.add(weatherForecastActor);
        forecastActors.add(uvForecastActor);
        forecastActors.add(sunForecastActor);
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        log.debug("Received {} from {}", message, getSender());
        if (message instanceof Messages.MonitorApplicationMessage) {
            String city = ((Messages.MonitorApplicationMessage) message).name;
            String appEui = ((Messages.MonitorApplicationMessage) message).appEui;
            String appKey = ((Messages.MonitorApplicationMessage) message).appKey;
            Position position = ((Messages.MonitorApplicationMessage) message).position;

            String actorName = "ttn-" + city + "-broker";
            Props applicationBrokerProps = ApplicationMqttActor.props(
                    "tcp://staging.thethingsnetwork.org:1883", appEui, appKey);

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

            // Tell each forecast actor to fetch data for this city
            for (ActorRef forecastActor : forecastActors) {
                forecastActor.tell(new Messages.GetForecastForCityMessage(city, position), self());
            }

        }
        else {
            unhandled(message);
        }
    }

    /**
     * Supervision strategy for all Actors dealing with MQTT connections
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
