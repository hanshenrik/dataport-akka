package no.ntnu.dataport;

import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class MqttActorTest {
    static ActorSystem system;
    private static final String DEFAULT_DURATION = "1 second";

    public static @DataPoints
    String[] broker = {"tcp://dataport.item.ntnu.no:1883", "tcp://croft.thethings.girovito.nl:1883"};

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    @Theory
    public void notConnectedOnCreation(String broker) {
        new JavaTestKit(system) {{
            Props props = MqttActor.props(broker, "+", 0, null, null);
            final ActorRef mqttRef = system.actorOf(props);

            // Ask for the connection status
            mqttRef.tell(new DataportMain.MqttConnectionStatusMessage(), getRef());
            // Await the correct response
            expectMsgEquals(duration(DEFAULT_DURATION), false);
        }};
    }

    @Theory
    public void mqttConnectMessageResultsInMqttConnection(String broker) {
        new JavaTestKit(system) {{
            Props props = MqttActor.props(broker, "#", 0, null, null);
            final ActorRef mqttRef = system.actorOf(props);

            // Tell the actor to connect
            mqttRef.tell(new DataportMain.MqttConnectMessage(), getRef());
            // Ask for the connection status
            mqttRef.tell(new DataportMain.MqttConnectionStatusMessage(), getRef());
            // Await the correct response
            expectMsgEquals(duration(DEFAULT_DURATION), true);
        }};
    }

    @Theory
    public void mqttDisonnectMessageResultsInNotConnected(String broker) {
        new JavaTestKit(system) {{
            Props props = MqttActor.props(broker, "#", 0, null, null);
            final ActorRef mqttRef = system.actorOf(props);

            // Tell the actor to connect
            mqttRef.tell(new DataportMain.MqttConnectMessage(), getRef());
            // Ask for the connection status
            mqttRef.tell(new DataportMain.MqttConnectionStatusMessage(), getRef());
            // Await the correct response
            expectMsgEquals(duration(DEFAULT_DURATION), true);

            // Tell the actor to disconnect
            mqttRef.tell(new DataportMain.MqttDisconnectMessage(), getRef());
            // Ask for the connection status
            mqttRef.tell(new DataportMain.MqttConnectionStatusMessage(), getRef());

            // Check that the actor is not connected to the broker anymore
            expectMsgEquals(duration(DEFAULT_DURATION), false);
        }};
    }
}