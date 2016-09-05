package no.ntnu.dataport;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffOptions;
import akka.pattern.BackoffSupervisor;
import no.ntnu.dataport.utils.SecretStuff;
import org.eclipse.paho.client.mqttv3.MqttException;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

import static akka.actor.SupervisorStrategy.*;


public class ExternalResourceSupervisorActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

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
        // TODO: have as list/map instead, not variables
        Props dataportBrokerProps = MqttActor.props("tcp://dataport.item.ntnu.no:1883", 0, null, null);
        Props ttnCroftBrokerProps = MqttActor.props("tcp://croft.thethings.girovito.nl:1883", 0, null, null);
        Props ttnStagingBrokerProps = MqttActor.props("tcp://staging.thethingsnetwork.org:1883", 0, SecretStuff.VEJLE_APP_EUI,
                SecretStuff.VEJLE_APP_KEY);

        BackoffOptions dataportBrokerBackoffOptions= Backoff.onFailure(
                dataportBrokerProps,
                "dataportBroker",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
            ).withSupervisorStrategy(mqttActorStrategy);

        BackoffOptions ttnCroftBrokerBackoffOptions = Backoff.onFailure(
                ttnCroftBrokerProps,
                "ttnCroftBroker",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);

        BackoffOptions ttnStagingBrokerBackoffOptions = Backoff.onFailure(
                ttnStagingBrokerProps,
                "ttnStagingBroker",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);

        final Props dataportBrokerSupervisorProps = BackoffSupervisor.props(dataportBrokerBackoffOptions);
        final Props ttnCroftBrokerSupervisorProps = BackoffSupervisor.props(ttnCroftBrokerBackoffOptions);
        final Props ttnStagingBrokerSupervisorProps = BackoffSupervisor.props(ttnStagingBrokerBackoffOptions);

        getContext().actorOf(dataportBrokerSupervisorProps, "dataportBrokerSupervisor");
        getContext().actorOf(ttnCroftBrokerSupervisorProps, "ttnCroftBrokerSupervisor");
        getContext().actorOf(ttnStagingBrokerSupervisorProps, "ttnStagingBrokerSupervisor");
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        log.info("Received {} from {}", message, getSender());
    }

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
