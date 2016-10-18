package no.ntnu.dataport.actors;

import akka.actor.*;
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
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WeatherForecastActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public static Props props() {
        return Props.create(new Creator<WeatherForecastActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public WeatherForecastActor create() throws Exception {
                return new WeatherForecastActor();
            }
        });
    }

    ActorRef mediator;
    private final Cancellable getAndPublishWeatherDataTimeout;
    private final String weatherForecastTopic;
    private final String weatherAPIBaseURL;
    private Map<String, String> cityURLMap;

    @Override
    public void postStop() {
        getAndPublishWeatherDataTimeout.cancel();
    }

    public WeatherForecastActor() {
        log.info("WeatherForecastActor created!");

        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.weatherForecastTopic = "dataport/forecast/weather";
        this.weatherAPIBaseURL = "http://api.met.no/weatherapi/locationforecastlts/1.2/?lat=%f;lon=%f";
        this.cityURLMap = new HashMap<>();

        this.getAndPublishWeatherDataTimeout = getContext().system().scheduler().schedule(
                Duration.create(1, TimeUnit.MINUTES),
                Duration.create(6, TimeUnit.HOURS),
                this::getAndPublishWeatherForecast,
                getContext().dispatcher());

    }

    private void getAndPublishWeatherForecast() {
        cityURLMap.forEach((city, apiURLForCity) -> {
            try {
                SAXBuilder jdomBuilder = new SAXBuilder();
                Document jdomDocument = jdomBuilder.build(apiURLForCity);
                Element rootElement = jdomDocument.getRootElement();
                List<Element> timeElements = rootElement.getChild("product").getChildren();

                for (int i = 0; i < timeElements.size(); i++) {
                    Element forecastElement = timeElements.get(i);
                    DateTime from = new DateTime(forecastElement.getAttribute("from").getValue());
                    DateTime to = new DateTime(forecastElement.getAttribute("to").getValue());
                    if (from.isEqual(to)) {
                        List<Element> parameters = forecastElement.getChild("location").getChildren();
                        Map<String, Object> parameterMap = new HashMap<>();
                        parameters.forEach(
                                (parameter) -> {
                                    String parameterName = parameter.getName();
                                    switch (parameterName) {
                                        case "windDirection":
                                            parameterMap.put(parameterName, Float.parseFloat(parameter.getAttribute("deg").getValue()));
                                            break;
                                        case "windSpeed":
                                        case "windGust":
                                            parameterMap.put(parameterName, Float.parseFloat(parameter.getAttribute("mps").getValue()));
                                            break;
                                        case "cloudiness":
                                        case "fog":
                                        case "lowClouds":
                                        case "mediumClouds":
                                        case "highClouds":
                                            parameterMap.put(parameterName, Float.parseFloat(parameter.getAttribute("percent").getValue()));
                                            break;
                                        default:
                                            parameterMap.put(parameterName, Float.parseFloat(parameter.getAttribute("value").getValue()));
                                    }
                                });

                        // Get precipitation from the next tag. For the first three days this holds the forecasted
                        // value for the previous hour until this hour. For days further in the future the
                        // precipitation forecast is for a longer time interval. We store it for the "to" time.
                        // It will be overridden when a newer (more accurate) forecast is available.
                        Element precipitationElement = timeElements.get(i+1).getChild("location").getChild("precipitation");
                        parameterMap.put(precipitationElement.getName(), Float.parseFloat(precipitationElement.getAttribute("value").getValue()));
                        Point point = Point.measurement("weather_forecast")
                                .time(to.getMillis(), TimeUnit.MILLISECONDS)
                                .tag("city", city)
                                .fields(parameterMap)
                                .build();

                        // Tell DBActor to save in DB
                        mediator.tell(new DistributedPubSubMediator.Publish(weatherForecastTopic, point), self());
                    }
                }
            } catch (IOException | JDOMException e) {
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

            cityURLMap.put(city, String.format(weatherAPIBaseURL, position.lat, position.lon));
        }
        else {
            unhandled(message);
        }
    }
}
