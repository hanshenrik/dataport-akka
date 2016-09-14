package no.ntnu.dataport.types;

public class ApplicationParameters {
    public final String name;
    public final String appEui;
    public final String appKey;
    public final Position position;

    public ApplicationParameters(String name, String appEui, String appKey, Position position) {
        this.name = name;
        this.appEui = appEui;
        this.appKey = appKey;
        this.position = position;
    }
}
