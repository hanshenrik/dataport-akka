package no.ntnu.dataport;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffOptions;
import akka.pattern.BackoffSupervisor;
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
        Props dataportProps = MqttActor.props("tcp://dataport.item.ntnu.no:1883", 0, null, null);
        Props ttnCroftProps = MqttActor.props("tcp://croft.thethings.girovito.nl:1883", 0, null, null);
        Props stagingProps = MqttActor.props("tcp://staging.thethingsnetwork.org:1883", 0, "70B3D57ED0000B79", "urqQDLAgV/G9JWI8/N88inCuUWDyYqwvOfoq2MeQc7k=");

        BackoffOptions dataportBackoffOptions= Backoff.onFailure(
                dataportProps,
                "dataport",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
            ).withSupervisorStrategy(mqttActorStrategy);

        BackoffOptions ttnCroftBackoffOptions = Backoff.onFailure(
                ttnCroftProps,
                "ttnCroft",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);

        BackoffOptions stagingGatewayBackoffOptions = Backoff.onFailure(
                stagingProps,
                "staging",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);

        final Props dataportSupervisorProps = BackoffSupervisor.props(dataportBackoffOptions);
        final Props ttnCroftSupervisorProps = BackoffSupervisor.props(ttnCroftBackoffOptions);
        final Props stagingGatewaySupervisorProps = BackoffSupervisor.props(stagingGatewayBackoffOptions);

        getContext().actorOf(dataportSupervisorProps, "dataportSupervisor");
        getContext().actorOf(ttnCroftSupervisorProps, "ttnCroftSupervisor");
        getContext().actorOf(stagingGatewaySupervisorProps, "stagingSupervisor");
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
