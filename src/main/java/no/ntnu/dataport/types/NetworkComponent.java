package no.ntnu.dataport.types;

public class NetworkComponent {
    private DeviceType type;
    private String eui;
    private Position position;

    public NetworkComponent(DeviceType type, String eui, Position position) {
        this.type = type;
        this.eui = eui;
        this.position = position;
    }

    public DeviceType getType() {
        return type;
    }

    public void setType(DeviceType type) {
        this.type = type;
    }

    public String getEui() {
        return eui;
    }

    public void setEui(String eui) {
        this.eui = eui;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }
}
