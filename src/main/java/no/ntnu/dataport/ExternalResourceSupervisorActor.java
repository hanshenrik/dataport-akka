package no.ntnu.dataport;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.DeciderBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffOptions;
import akka.pattern.BackoffSupervisor;
import no.ntnu.dataport.DataportMain.*;
import org.eclipse.paho.client.mqttv3.MqttException;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

import static akka.actor.SupervisorStrategy.*;


public class ExternalResourceSupervisorActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private static final int MAX_NUMBER_OF_ACTOR_RESTARTS = 5;

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
        Props dataportProps = MqttActor.props("tcp://dataport.item.ntnu.no:1883", "hh-test", 0, null, null);
        Props croftNodeProps = MqttActor.props("tcp://croft.thethings.girovito.nl:1883", "nodes/02031902/packets", 0, null, null);
        Props croftGatewayProps = MqttActor.props("tcp://croft.thethings.girovito.nl:1883", "gateways/1DEE192E3D82B8E4/status", 0, null, null);
//        Props stagingProps = MqttActor.props("tcp://staging.thethingsnetwork.org:1883", "+/devices/+/up", 0, "user", "password");
//        Props stagingProps = MqttActor.props("BAD ADDRESS", "+/devices/+/up", 0, "user", "password");

//        final ActorRef mqttDataport = getContext().actorOf(dataportProps, "dataport");
//        final ActorRef mqttTTNCroftNodes = getContext().actorOf(croftNodeProps, "ttn-croft-nodes");
//        final ActorRef mqttTTNCroftGateways = getContext().actorOf(croftGatewayProps, "ttn-croft-gateways");
//        final ActorRef mqttStaging = getContext().actorOf(stagingProps, "ttn-staging");

//        mqttTTNCroftNodes.tell(new MqttConnectMessage(), getSelf());
//        mqttTTNCroftGateways.tell(new MqttConnectMessage(), getSelf());
//        mqttStaging.tell(new MqttConnectMessage(), getSelf());


        BackoffOptions dataportBackoffOptions= Backoff.onFailure(
                dataportProps,
                "dataport",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
            ).withSupervisorStrategy(mqttActorStrategy);

        BackoffOptions croftNodeBackoffOptions = Backoff.onFailure(
                croftNodeProps,
                "croftNode",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);

        BackoffOptions croftGatewayBackoffOptions = Backoff.onFailure(
                croftGatewayProps,
                "croftGateway",
                Duration.create(3, TimeUnit.SECONDS),
                Duration.create(2, TimeUnit.MINUTES),
                0.2 // add 20% "noise" to vary the intervals slightly
        ).withSupervisorStrategy(mqttActorStrategy);

        final Props dataportSupervisorProps = BackoffSupervisor.props(dataportBackoffOptions);
        final Props croftNodeSupervisorProps = BackoffSupervisor.props(croftNodeBackoffOptions);
        final Props croftGatewaySupervisorProps = BackoffSupervisor.props(croftGatewayBackoffOptions);

        getContext().actorOf(dataportSupervisorProps, "dataportSupervisor");
        getContext().actorOf(croftNodeSupervisorProps, "croftNodeSupervisor");
        getContext().actorOf(croftGatewaySupervisorProps, "croftGatewaySupervisor");
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
