package no.ntnu.dataport;

import akka.actor.UntypedActor;
import org.eclipse.paho.client.mqttv3.MqttClient;

public abstract class MqttFSMBase extends UntypedActor {

    /*
     * This is the mutable state of this state machine.
     */
    protected enum State {
        CONNECTED, CONNECTING, DISCONNECTED;
    }

    private State state = State.DISCONNECTED;
    private MqttClient mqttClient;

    /*
     * Then come all the mutator methods:
     */
    protected void init(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    protected void setState(State s) {
        if (state != s) {
            transition(state, s);
            state = s;
        }
    }

    /*
     * Here are the interrogation methods:
     */
    protected MqttClient getMqttClient() {
        return mqttClient;
    }

    protected State getState() {
        return state;
    }

    /*
     * And finally the callbacks (only one in this example: react to state change)
     */
    abstract protected void transition(State old, State next);
}
