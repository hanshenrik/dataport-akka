package no.ntnu.dataport.types;

import no.ntnu.dataport.enums.DeviceState;
import no.ntnu.dataport.enums.DeviceType;
import org.joda.time.DateTime;

public class NetworkComponent {
    private DeviceType type;
    private String eui;
    private Position position;
    private DeviceState status;
    private DateTime lastSeen;
    private Double maxObservedRange;

    public NetworkComponent(DeviceType type, String eui, Position position, DeviceState status) {
        this.type = type;
        this.eui = eui;
        this.position = position;
        this.status = status;
    }

    public DeviceType getType() {
        return type;
    }

    public String getEui() {
        return eui;
    }

    public NetworkComponent withStatus(DeviceState status) {
        this.status = status;
        return this;
    }

    public NetworkComponent withLastSeen(DateTime lastSeen) {
        this.lastSeen = lastSeen;
        return this;
    }

    public NetworkComponent withMaxObservedRange(double maxObservedRange) {
        this.maxObservedRange = maxObservedRange;
        return this;
    }
}
