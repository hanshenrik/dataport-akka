package no.ntnu.dataport.actors;

import akka.actor.UntypedActor;
import no.ntnu.dataport.enums.MqttActorState;
import org.eclipse.paho.client.mqttv3.MqttClient;

import static no.ntnu.dataport.enums.MqttActorState.DISCONNECTED;

public abstract class MqttFSMBase extends UntypedActor {

    private MqttActorState state = DISCONNECTED;
    private MqttClient mqttClient;

    /*
     * Then come all the mutator methods:
     */
    protected void init(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    protected void setState(MqttActorState s) {
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

    protected MqttActorState getState() {
        return state;
    }

    /*
     * And finally the callbacks (only one in this example: react to state change)
     */
    abstract protected void transition(MqttActorState old, MqttActorState next);
}
