package no.ntnu.dataport.types;

public class NetworkComponent {
    private DeviceType type;
    private String eui;
    private Position position;
    private DeviceState status;

    public NetworkComponent(DeviceType type, String eui, Position position, DeviceState status) {
        this.type = type;
        this.eui = eui;
        this.position = position;
        this.status = status;
    }
}
