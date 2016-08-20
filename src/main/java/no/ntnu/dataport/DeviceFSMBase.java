package no.ntnu.dataport;

import akka.actor.UntypedActor;
import org.eclipse.paho.client.mqttv3.MqttClient;

public abstract class DeviceFSMBase extends UntypedActor {

    protected enum State {
        OK, UNKNOWN;
    }

    private State state = State.UNKNOWN;

    protected void init() {

    }

    protected void setState(State s) {
        if (state != s) {
            transition(state, s);
            state = s;
        }
    }

    protected boolean isInitialized() {
        return true;
    }

    protected State getState() {
        return state;
    }

    abstract protected void transition(State old, State next);
}
