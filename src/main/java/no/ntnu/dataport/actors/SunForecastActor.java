package no.ntnu.dataport.actors;

import akka.actor.*;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
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
    final Cancellable getAndPublishWeatherDataTimeout;
    final String weatherForecastTopic;
    final String sunForecastTopic;
    final String weatherAPIBaseURL;
    final String sunriseAPIBaseURL;

    @Override
    public void postStop() {
        getAndPublishWeatherDataTimeout.cancel();
    }

    public WeatherForecastActor() {
        log.info("WeatherForecastActor created!");

        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.weatherForecastTopic = "dataport/forecast/weather";
        this.sunForecastTopic = "dataport/forecast/sun";
        this.weatherAPIBaseURL = "http://api.met.no/weatherapi/locationforecastlts/1.2/?lat=%f;lon=%f";
        this.sunriseAPIBaseURL = "http://api.met.no/weatherapi/sunrise/1.0/?lat=%f;lon=-%f;from=%s;to=%s";

        this.getAndPublishWeatherDataTimeout = getContext().system().scheduler().schedule(
                Duration.create(0, TimeUnit.MINUTES),
                Duration.create(6, TimeUnit.HOURS),
                this::getAndPublishSunlightForecast,
                getContext().dispatcher());

    }

    private void getAndPublishWeatherForecast() {
        Map<String, String> cityURLMap = new HashMap<>();
        cityURLMap.put("Trondheim", String.format(weatherAPIBaseURL, 63.430515, 10.395053));
        cityURLMap.put("Vejle", String.format(weatherAPIBaseURL, 55.711311, 9.536354));

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
                                    System.out.println(parameter);
                                    String parameterName = parameter.getName();
                                    switch (parameterName) {
                                        case "windDirection":
                                            parameterMap.put(parameterName, parameter.getAttribute("deg").getValue());
                                            break;
                                        case "windSpeed":
                                        case "windGust":
                                            parameterMap.put(parameterName, parameter.getAttribute("mps").getValue());
                                            break;
                                        case "cloudiness":
                                        case "fog":
                                        case "lowClouds":
                                        case "mediumClouds":
                                        case "highClouds":
                                            parameterMap.put(parameterName, parameter.getAttribute("percent").getValue());
                                            break;
                                        default:
                                            parameterMap.put(parameterName, parameter.getAttribute("value").getValue());
                                    }
                                });

                        // Get precipitation from the next tag. For the first three days this holds the forecasted
                        // value for the previous hour until this hour. For days further in the future the
                        // precipitation forecast is for a longer time interval. We store it for the "to" time.
                        // It will be overridden when a newer (more accurate) forecast is available.
                        Element precipitationElement = timeElements.get(i+1).getChild("location").getChild("precipitation");
                        parameterMap.put(precipitationElement.getName(), precipitationElement.getAttribute("value").getValue());
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

    private void getAndPublishSunlightForecast() {
        Map<String, String> cityURLMap = new HashMap<>();
        LocalDate today = new LocalDate();
        LocalDate thirtyDaysAhead = today.plusDays(30);
        cityURLMap.put("Trondheim", String.format(sunriseAPIBaseURL, 63.430515, 10.395053, today, thirtyDaysAhead));
        cityURLMap.put("Vejle", String.format(sunriseAPIBaseURL, 55.711311, 9.536354, today, thirtyDaysAhead));

        cityURLMap.forEach((city, apiURLForCity) -> {
            try {
                SAXBuilder jdomBuilder = new SAXBuilder();
                Document jdomDocument = jdomBuilder.build(apiURLForCity);
                Element rootElement = jdomDocument.getRootElement();
                List<Element> timeElements = rootElement.getChildren("time");

                timeElements.forEach(
                        (timeElement) -> {
                            DateTime time = new DateTime(timeElement.getAttribute("date").getValue());
                            System.out.println(time);
                            Element sunElement = timeElement.getChild("location").getChild("sun");
                            DateTime sunrise = new DateTime(sunElement.getAttribute("rise").getValue());
                            DateTime sunset = new DateTime(sunElement.getAttribute("set").getValue());
                            long daylightInMillis = sunset.getMillisOfDay() - sunrise.getMillisOfDay();
                            Point point = Point.measurement("sun")
                                    .time(time.getMillis(), TimeUnit.MILLISECONDS)
                                    .tag("city", city)
                                    .addField("sunrise", sunrise.getMillis())
                                    .addField("sunset", sunset.getMillis())
                                    .addField("daylight_in_millis", daylightInMillis)
                                    .build();
                            System.out.println(point);

                            mediator.tell(new DistributedPubSubMediator.Publish(weatherForecastTopic, point), self());
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
    }
}
