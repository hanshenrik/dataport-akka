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
import org.jdom2.input.SAXBuilder;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SunForecastActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public static Props props() {
        return Props.create(new Creator<SunForecastActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public SunForecastActor create() throws Exception {
                return new SunForecastActor();
            }
        });
    }

    ActorRef mediator;
    private final Cancellable getAndPublishSunDataTimeout;
    private final String sunForecastTopic;
    private final String sunriseAPIBaseURL;
    private Map<String, Position> cityPositionMap;

    @Override
    public void postStop() {
        getAndPublishSunDataTimeout.cancel();
    }

    public SunForecastActor() {
        log.info("SunForecastActor created!");

        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.sunForecastTopic = "dataport/forecast/sun";
        this.sunriseAPIBaseURL = "http://api.met.no/weatherapi/sunrise/1.0/?lat=%f;lon=-%f;from=%s;to=%s";
        this.cityPositionMap = new HashMap<>();

        // TODO: Should probably only get 1 every time, not 30. Only need to get 30 first time. If we gather every day, that is.
        this.getAndPublishSunDataTimeout = getContext().system().scheduler().schedule(
                Duration.create(2, TimeUnit.MINUTES),
                Duration.create(1, TimeUnit.DAYS),
                this::getAndPublishSunlightForecast,
                getContext().dispatcher());

    }

    private void getAndPublishSunlightForecast() {
        LocalDate today = new LocalDate();
        LocalDate thirtyDaysAhead = today.plusDays(30);

        cityPositionMap.forEach((city, position) -> {
            String apiURLForCity = String.format(sunriseAPIBaseURL, position.lat, position.lon, today, thirtyDaysAhead);
            try {
                SAXBuilder jdomBuilder = new SAXBuilder();
                Document jdomDocument = jdomBuilder.build(apiURLForCity);
                Element rootElement = jdomDocument.getRootElement();
                List<Element> timeElements = rootElement.getChildren("time");

                timeElements.forEach(
                        (timeElement) -> {
                            DateTime time = new DateTime(timeElement.getAttribute("date").getValue());
                            Element sunElement = timeElement.getChild("location").getChild("sun");
                            DateTime sunrise = new DateTime(sunElement.getAttribute("rise").getValue());
                            DateTime sunset = new DateTime(sunElement.getAttribute("set").getValue());
                            long daylightInMillis = sunset.getMillis() - sunrise.getMillis();
                            Point point = Point.measurement("sun_forecast")
                                    .time(time.getMillis(), TimeUnit.MILLISECONDS)
                                    .tag("city", city)
                                    .addField("sunrise", sunrise.getMillis())
                                    .addField("sunset", sunset.getMillis())
                                    .addField("daylight_in_millis", daylightInMillis)
                                    .build();

                            mediator.tell(new DistributedPubSubMediator.Publish(sunForecastTopic, point), self());
                        }
                );
            }
            catch (IOException | JDOMException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void onReceive(Object message) {
        log.info("Received: {} from {}", message, getSender());
        if (message instanceof Messages.GetForecastForCityMessage) {
            String city = ((Messages.GetForecastForCityMessage) message).name;
            Position position = ((Messages.GetForecastForCityMessage) message).position;

            cityPositionMap.put(city, position);
        }
        else {
            unhandled(message);
        }
    }
}
