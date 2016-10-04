package no.ntnu.dataport.actors;

import akka.actor.AbstractFSM;
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.japi.Creator;
import no.ntnu.dataport.enums.DBActorState;
import no.ntnu.dataport.enums.DeviceType;
import no.ntnu.dataport.types.Messages;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Pong;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DBActor extends AbstractFSM<DBActorState, Set<String>> {

    /**
     *
     * @param url       The URL to the server running the InfluxDB. Must include port! E.g. 'http://myhost.com:8086'.
     * @param username  The username for the InfluxDB.
     * @param password  The password for the InfluxDB.
     * @param dbName    The name of the DB used in the InfluxDB.
     * @return          a Props for creating this actor, which can then be further configured
     *                  (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String url, final String username, final String password, final String dbName) {
        return Props.create(new Creator<DBActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public DBActor create() throws Exception {
                return new DBActor(url, username, password, dbName);
            }
        });
    }
    ActorRef mediator;

    private final String url;
    private final String username;
    private final String password;
    private String dbName;
    public InfluxDB influxDB;
    public String siteGraphsTopic;
    public String forecastTopic;
    public Set<String> currentDevicesMonitored;

    public DBActor(String url, String username, String password, String dbName) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.dbName = dbName;
        this.siteGraphsTopic = "dataport/site/graphs";
        this.forecastTopic = "dataport/forecast";
        this.currentDevicesMonitored = new HashSet<>();
        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.influxDB = InfluxDBFactory.connect(url, username, password);

        // Flush every 2000 Points, at least every 100ms
        this.influxDB.enableBatch(24, 100, TimeUnit.MILLISECONDS);

        try {
            Pong pong = influxDB.ping();
            log().info("InfluxDB responded to ping in {} ms", pong.getResponseTime());

            self().tell(pong, self());
        }
        catch (Exception e) {
            e.printStackTrace();
            self().tell(PoisonPill.getInstance(), self());
        }
    }

    public void handler(DBActorState from, DBActorState to) {
        if (from != to) {
            log().info("Going from {} to {}", from, to);
        }
    }

    {
        startWith(DBActorState.UNINITIALIZED, new HashSet<>());

        when(DBActorState.UNINITIALIZED,
                matchEvent(Pong.class, (event, data) -> {
                    mediator.tell(new DistributedPubSubMediator.Subscribe(siteGraphsTopic, self()), self());
                    mediator.tell(new DistributedPubSubMediator.Subscribe(forecastTopic, self()), self());
                    return goTo(DBActorState.INITIALIZED);
                }));

        when(DBActorState.INITIALIZED,
                matchEvent(Messages.NetworkGraphMessage.class,
                        (networkGraphMessage, data) -> {
                            networkGraphMessage.graph.values().stream().filter(device -> !stateData().contains(device.getEui())).filter(device -> device.getType() == DeviceType.SENSOR).forEach(device -> {
                                String internalSensorReceptionTopic = "dataport/site/" + sender().path().name() + "/sensor/" + device.getEui() + "/events/reception";
                                mediator.tell(new DistributedPubSubMediator.Subscribe(internalSensorReceptionTopic, self()), self());
                                currentDevicesMonitored.add(device.getEui());
                            });
                            return stay().using(currentDevicesMonitored); }
                ).event(DistributedPubSubMediator.SubscribeAck.class,
                        (subscribeAck, data) -> {
                            log().info("Now subscribing to {}", subscribeAck.subscribe().topic());
                            return stay(); }
                ).event(Messages.Observation.class,
                        (observation, data) -> {
                            Point point = Point.measurement("ctt_observation")
                                    .time(observation.metadata.server_time.getMillis(), TimeUnit.MILLISECONDS)
                                    .tag("device_eui", observation.eui)
                                    .tag("gateway_eui", observation.metadata.gateway_eui)
                                    .tag("city", sender().path().parent().name())
                                    .addField("co2", observation.data.co2)
                                    .addField("no2", observation.data.no2)
                                    .addField("pm1", observation.data.pm1)
                                    .addField("pm2", observation.data.pm2)
                                    .addField("pm10", observation.data.pm10)
                                    .addField("temperature", observation.data.temperature)
                                    .addField("humidity", observation.data.humidity)
                                    .addField("pressure", observation.data.pressure)
                                    .addField("battery_level", observation.data.batteryLevel)
                                    .addField("rssi", observation.metadata.rssi)
                                    .addField("frequency", observation.metadata.frequency)
                                    .addField("coding_rate", observation.metadata.codingrate)
                                    .addField("crc", observation.metadata.crc)
                                    .addField("lsnr", observation.metadata.lsnr)
                                    .addField("channel", observation.metadata.channel)
                                    .build();

                            log().info("Writing to InfluxDB, observation point: {}", point.toString());

                            // Write to remote InfluxDB
                            influxDB.write(dbName, "autogen", point);

                            return stay(); }
                ).event(Messages.ForecastMessage.class,
                        (forecast, data) -> {
                            Point point = Point.measurement("weather_forecast")
                                    .time(forecast.timestamp.getMillis(), TimeUnit.MILLISECONDS)
                                    .tag("city", forecast.city)
                                    .addField("temperature", forecast.temperature)
                                    .addField("precipitation", forecast.precipitation)
                                    .addField("cloudiness", forecast.cloudiness)
                                    .addField("daylight_in_millis", forecast.daylightInMillis)
                                    .build();

                            log().info("Writing to InfluxDB, forecast point: {}", point.toString());

                            // Write to remote InfluxDB
                            influxDB.write(dbName, "autogen", point);

                            return stay(); }
                ).event(Point.class,
                        (point, data) -> {
                            // TODO: make all actors just send points here?
                            log().info("Writing to InfluxDB, point: {}", point.toString());

                            // Write to remote InfluxDB
                            influxDB.write(dbName, "autogen", point);

                            return stay();
                        }));

        onTransition(this::handler);

        whenUnhandled(
                matchAnyEvent((event, data) -> {
                    log().error("Unhandled event {} in state {}", event, stateName());
                    return stay();
                })
        );

        initialize();
    }
}
