package no.ntnu.dataport;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
//import akka.japi.Function;
import akka.japi.Creator;
import akka.japi.Function;
import akka.japi.pf.DeciderBuilder;
import akka.pattern.Backoff;
import akka.pattern.BackoffSupervisor;
import no.ntnu.dataport.DataportMain.*;
import org.eclipse.paho.client.mqttv3.MqttException;
import scala.concurrent.duration.Duration;

import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import static akka.actor.SupervisorStrategy.Directive;
import static akka.actor.SupervisorStrategy.restart;
import static akka.actor.SupervisorStrategy.resume;
import static akka.actor.SupervisorStrategy.stop;
import static akka.actor.SupervisorStrategy.escalate;


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

        final ActorRef mqttDataport = getContext().actorOf(dataportProps, "dataport");
        final ActorRef mqttTTNCroftNodes = getContext().actorOf(croftNodeProps, "ttn-croft-nodes");
        final ActorRef mqttTTNCroftGateways = getContext().actorOf(croftGatewayProps, "ttn-croft-gateways");
//        final ActorRef mqttStaging = getContext().actorOf(stagingProps, "ttn-staging");

        mqttDataport.tell(new MqttConnectMessage(), getSelf());
        mqttDataport.tell(new MqttDisconnectMessage(), getSelf());
        mqttTTNCroftNodes.tell(new MqttConnectMessage(), getSelf());
        mqttTTNCroftGateways.tell(new MqttConnectMessage(), getSelf());
//        mqttStaging.tell(new MqttConnectMessage(), getSelf());

        getContext().system().scheduler().schedule(
                Duration.Zero(),
                Duration.create(10, TimeUnit.SECONDS),
                mqttDataport,
                new MqttConnectionStatusMessage(),
                getContext().dispatcher(),
                getSelf());
    }

    @Override
    public void onReceive(Object message) throws Throwable {
        log.info("Received {} from {}", message, getSender());
    }

    private static SupervisorStrategy strategy =
        // TODO: this time range and maxRetries does not seem to work...
        new OneForOneStrategy(
            MAX_NUMBER_OF_ACTOR_RESTARTS,
            Duration.create(1, TimeUnit.MINUTES),
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
//                    notifyConsumerFailure(getSender(), e);
                    return restart();
                }).
                match(ActorKilledException.class, e -> {
                    System.out.println("ActorKilledException: " + e.getMessage());
//                    notifyConsumerFailure(getSender(), e);
                    return restart();
                }).
                matchAny(o -> {
                    System.out.println("Unexpected failure: " + o.getMessage());
                    return escalate();
                }).build());

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return strategy;
    }

}
