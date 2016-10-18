package no.ntnu.dataport.actors;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import no.ntnu.dataport.types.Messages;
import no.ntnu.dataport.types.Position;
import org.influxdb.dto.Point;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.filter.ElementFilter;
import org.jdom2.input.SAXBuilder;
import org.joda.time.DateTime;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UVForecastActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public static Props props() {
        return Props.create(new Creator<UVForecastActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public UVForecastActor create() throws Exception {
                return new UVForecastActor();
            }
        });
    }

    ActorRef mediator;
    private final Cancellable getAndPublishUVDataTimeout;
    private final String forecastTopic;
    private Map<String, Position> closestForecastPositionToCityMap;

    @Override
    public void postStop() {
        getAndPublishUVDataTimeout.cancel();
    }

    public UVForecastActor() {
        log.info("UVForecasttActor created!");

        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.forecastTopic = "dataport/forecast/uv";
        this.closestForecastPositionToCityMap = new HashMap<>();

        this.getAndPublishUVDataTimeout = getContext().system().scheduler().schedule(
                Duration.create(3, TimeUnit.MINUTES),
                Duration.create(12, TimeUnit.HOURS),
                this::getAndPublishUVForecast,
                getContext().dispatcher());
    }

    private void getAndPublishUVForecast() {
        Pattern pattern = Pattern.compile("http://.*?time=(.*?);.*?content_type=text%2Fxml");
        Map<String, DateTime> uvForecastURLsToCheck = new HashMap<>();
        String listOfAvailableUVForecastURLsURL = "http://api.met.no/weatherapi/uvforecast/1.0/available";

        try {

            // The available forecast URLs are provided on one page. First retrieve these URLs, then query them
            SAXBuilder jdomBuilder = new SAXBuilder();
            Document jdomDocument = jdomBuilder.build(listOfAvailableUVForecastURLsURL);
            Element rootElement = jdomDocument.getRootElement();
            Iterator<Element> it = rootElement.getDescendants(new ElementFilter("uri"));

            while (it.hasNext()) {
                Element uriElement = it.next();
                String url = uriElement.getTextTrim();
                Matcher matcher = pattern.matcher(url);
                if (matcher.matches()) {
                    DateTime time = new DateTime(URLDecoder.decode(matcher.group(1), "ASCII"));
                    uvForecastURLsToCheck.put(url, time);
                }
            }

            // For each available forecast URL, retrieve the ones for the locations we are interested in
            for (String url : uvForecastURLsToCheck.keySet()) {
                DateTime time = uvForecastURLsToCheck.get(url);

                jdomDocument = jdomBuilder.build(url);
                Element uvRootElement = jdomDocument.getRootElement();

                closestForecastPositionToCityMap.forEach(
                        (city, position) -> {
                            Iterator<Element> iterator = uvRootElement.getDescendants(new ElementFilter("location"));

                            while (iterator.hasNext()) {
                                Element location = iterator.next();
                                double latitude = Double.parseDouble(location.getAttribute("latitude").getValue());
                                double longitude = Double.parseDouble(location.getAttribute("longitude").getValue());

                                if (latitude == position.lat && longitude == position.lon) {
                                    Map<String, Object> parameterMap = new HashMap<>();
                                    location.getChild("uv").getChildren().forEach(
                                            (parameter) -> parameterMap.put(parameter.getName(), Double.parseDouble(parameter.getAttribute("value").getValue())));

                                    Point point = Point.measurement("uv_forecast")
                                            .time(time.getMillis(), TimeUnit.MILLISECONDS)
                                            .tag("city", city)
                                            .fields(parameterMap)
                                            .build();

                                    // Tell DBActor to save in DB
                                    mediator.tell(new DistributedPubSubMediator.Publish(forecastTopic, point), self());
                                }
                            }
                });
            }
        }
        catch (IOException | JDOMException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onReceive(Object message) {
        log.info("Received: {} from {}", message, getSender());

        if (message instanceof Messages.GetForecastForCityMessage) {
            String city = ((Messages.GetForecastForCityMessage) message).name;
            Position position = ((Messages.GetForecastForCityMessage) message).position;

            // The positions used in the API are rounded to the nearest quarter
            Position positionRounded = new Position(Math.round(position.lat*4)/4f, Math.round(position.lon*4)/4f);

            closestForecastPositionToCityMap.put(city, positionRounded);
        }
        else {
            unhandled(message);
        }
    }
}
