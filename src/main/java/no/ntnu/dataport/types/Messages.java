package no.ntnu.dataport.types;

import org.joda.time.DateTime;

import java.util.List;
import java.util.Map;

public class Messages {
    public static final class ForecastMessage {
        public String city;
        public double altitude;
        public double latitude;
        public double longitude;
        public DateTime sunrise;
        public DateTime sunset;
        public DateTime timestamp;
        public int temperature;
        public double precipitation;
        public String cloudiness;
        public long daylightInMillis;

        public ForecastMessage(String city, double altitude, double latitude, double longitude, DateTime sunrise, DateTime sunset,
                               DateTime timestamp, int temperature, double precipitation, String cloudiness) {
            this.city = city;
            this.altitude = altitude;
            this.latitude = latitude;
            this.longitude = longitude;
            this.sunrise = sunrise;
            this.sunset = sunset;
            this.timestamp = timestamp;
            this.temperature = temperature;
            this.precipitation = precipitation;
            this.cloudiness = cloudiness;

            this.daylightInMillis = sunset.getMillisOfDay() - sunrise.getMillisOfDay();
        }
    }

    public static final class NetworkGraphMessage {
        public final Map<String, NetworkComponent> graph;
        public NetworkGraphMessage(Map<String, NetworkComponent> graph) {
            this.graph = graph;
        }
    }

    public static final class MonitorApplicationMessage {
        public final String name;
        public final String appEui;
        public final String appKey;
        public final Position position;

        public MonitorApplicationMessage(String name, String appEui, String appKey, Position position) {
            this.name = name;
            this.appEui = appEui;
            this.appKey = appKey;
            this.position = position;
        }
    }

    public static final class Observation {
        public final String eui;
        public final CTT2Observation.Metadata metadata;
        public final Data data;

        public Observation(String eui, CTT2Observation.Metadata metadata, Data data) {
            this.eui = eui;
            this.metadata = metadata;
            this.data = data;
        }
    }

    public static class Data {
        public Float co2;
        public Float no2;
        public Float temperature;
        public Float humidity;
        public Float pressure;
        public Float pm1;
        public Float pm2;
        public Float pm10;
        public int batteryLevel;

        public Data(Float co2, Float no2, Float temperature, Float humidity, Float pressure, Float pm1, Float pm2, Float pm10, int batteryLevel) {
            this.co2 = co2;
            this.no2 = no2;
            this.temperature = temperature;
            this.humidity = humidity;
            this.pressure = pressure;
            this.pm1 = pm1;
            this.pm2 = pm2;
            this.pm10 = pm10;
            this.batteryLevel = batteryLevel;
        }

        public Data(float co2, float no2, float temperature, float humidity, float pressure, int batteryLevel) {
            this(co2, no2, temperature, humidity, pressure, null, null, null, batteryLevel);
        }
    }

    public static final class CTT2Observation {
        public String payload;
        public float co2;
        public float no2;
        public float temperature;
        public float humidity;
        public float preassure;
        public float pm1;
        public float pm2;
        public float pm10;
        public int batteryLevel;
        public String dev_eui;
        public List<Metadata> metadata;

        public static class Metadata {
            public String gateway_eui;
            public DateTime server_time;
            public String datarate;
            public double frequency;
            public double rssi;
            public double lsnr;
            public String codingrate;
            public String modulation;
            public int channel;
            public int rfchain;
            public int crc;
            public double altitude;
            public double longitude;
            public double latitude;
        }
    }
}
