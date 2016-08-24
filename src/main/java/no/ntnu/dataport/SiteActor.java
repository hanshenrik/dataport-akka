package no.ntnu.dataport;

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import com.google.gson.Gson;
import no.ntnu.dataport.types.DeviceType;
import no.ntnu.dataport.types.Messages.*;
import no.ntnu.dataport.types.NetworkComponent;
import no.ntnu.dataport.types.Position;
import scala.concurrent.duration.Duration;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class SiteActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    /**
     * Create Props for an actor of this type.
     * @param name      The name of the city
     * @param latitude  The latitude of the city
     * @param longitude The longitude of the city
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final String name, final double latitude, final double longitude) {
        return Props.create(new Creator<SiteActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public SiteActor create() throws Exception {
                return new SiteActor(name, latitude, longitude);
            }
        });
    }

    final String name;
    final double latitude;
    final double longitude;
    List<NetworkComponent> networkComponents = new ArrayList<>();

    public SiteActor(String name, double latitude, double longitude) {
        log.info("Constructor called with name: {}, lat: {}, lon: {}", name, latitude, longitude);
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        try {
            ClassLoader classLoader = this.getClass().getClassLoader();
            File file = new File(classLoader.getResource("devices-" + name + ".csv").getFile());
            Scanner scanner = new Scanner(file);
            String[] device;

            while (scanner.hasNextLine()) {
                // TODO: add validation check
                device = scanner.nextLine().split(",");
                String eui = device[1];
                double lat = Double.parseDouble(device[2]);
                double lon = Double.parseDouble(device[3]);
                switch (device[0]) {
                    case "gateway":
                        // Create gateway actor
                        getContext().actorOf(GatewayActor.props(eui, this.name, lat, lon, Duration.create(20, TimeUnit.SECONDS)), eui);
                        context().system().actorSelection("/user/externalResourceSupervisor/ttnCroftSupervisor/ttnCroft").tell(
                                new MqttSubscribeMessage("gateways/" + eui + "/status"), self());
                        networkComponents.add(new NetworkComponent(DeviceType.GATEWAY, eui, new Position(lat, lon)));
                        break;
                    case "sensor":
                        // Create sensor actor
                        context().actorOf(SensorActor.props(eui, this.name, lat, lon, Duration.create(20, TimeUnit.SECONDS)), eui);
                        context().system().actorSelection("/user/externalResourceSupervisor/ttnCroftSupervisor/ttnCroft").tell(
                                new MqttSubscribeMessage("nodes/" + eui + "/packets"), self());
                        networkComponents.add(new NetworkComponent(DeviceType.SENSOR, eui, new Position(lat, lon)));
                        break;
                    default:
                        log.warning("Unknown device type: {}", device[0]);
                }
            }

            scanner.close();
        }
        catch (FileNotFoundException e) {
            log.error(e, "File not found");
        }

        // TODO: do this on a regular basis. Will that send the graph message (retained) to all connected clients?
        String graph = getNetworkGraph();
        NetworkGraphMessage networkGraphMessage = new NetworkGraphMessage(graph, this.name);
        context().system().actorSelection("/user/externalResourceSupervisor/dataportSupervisor/dataport")
                .tell(networkGraphMessage, self());
    }

    public String getNetworkGraph() {
        return new Gson().toJson(networkComponents);
    }

    @Override
    public void onReceive(Object message) {
        log.info("Received: {} from {}", message, getSender());
        // TODO: Handle adding of new devices through messages here, instead of reading from file!
    }
}
