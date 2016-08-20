package no.ntnu.dataport.types;

import org.joda.time.DateTime;

public class DeviceData {
    public DateTime lastSeen;
    public DeviceData(DateTime lastSeen) {
        this.lastSeen = lastSeen;
    }
}
