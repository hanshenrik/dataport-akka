package no.ntnu.dataport.actors;

import akka.actor.*;
import akka.cluster.pubsub.DistributedPubSub;
import akka.cluster.pubsub.DistributedPubSubMediator;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import no.ntnu.dataport.types.Messages;
import org.joda.time.DateTime;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import scala.concurrent.duration.Duration;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WeatherDataActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public static Props props() {
        return Props.create(new Creator<WeatherDataActor>() {
            private static final long serialVersionUID = 1L;

            @Override
            public WeatherDataActor create() throws Exception {
                return new WeatherDataActor();
            }
        });
    }

    ActorRef mediator;
    final Cancellable getAndPublishWeatherDataTimeout;
    final String forecastTopic;

    @Override
    public void postStop() {
        getAndPublishWeatherDataTimeout.cancel();
    }

    public WeatherDataActor() {
        log.info("WeatherDataActor created!");

        this.mediator = DistributedPubSub.get(context().system()).mediator();
        this.forecastTopic = "dataport/forecast";

        this.getAndPublishWeatherDataTimeout = getContext().system().scheduler().schedule(
                Duration.create(1, TimeUnit.MINUTES),
                Duration.create(6, TimeUnit.HOURS),
                this::getAndPublishWeatherData,
                getContext().dispatcher());

    }

    private void getAndPublishWeatherData() {
        Map<String, String> citiesMap = new HashMap<>();
        citiesMap.put("Trondheim", "http://www.yr.no/place/Norway/Sør-Trøndelag/Trondheim/Trondheim/forecast_hour_by_hour.xml");
        citiesMap.put("Vejle", "http://www.yr.no/place/Denmark/South_Denmark/Vejle/forecast_hour_by_hour.xml");

        citiesMap.forEach((city, yrURL) -> {
            try {
                HttpResponse<String> response = Unirest.get(yrURL)
                        .asString();

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setIgnoringElementContentWhitespace(true);
                try {
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    InputSource inputSource = new InputSource(new StringReader(response.getBody()));
                    Document document = builder.parse(inputSource);
                    document.getDocumentElement().normalize();

                    Element location = (Element) document.getElementsByTagName("location").item(1);

                    double altitude = Double.parseDouble(location.getAttribute("altitude"));
                    double latitude = Double.parseDouble(location.getAttribute("latitude"));
                    double longitude = Double.parseDouble(location.getAttribute("longitude"));

                    Element sun = (Element) document.getElementsByTagName("sun").item(0);
                    DateTime sunrise = new DateTime(sun.getAttribute("rise"));
                    DateTime sunset = new DateTime(sun.getAttribute("set"));

                    NodeList hourlyForecasts = ((Element) document.getElementsByTagName("tabular").item(0)).getElementsByTagName("time");

                    for (int i = 0; i < hourlyForecasts.getLength(); i++) {
                        Node nNode = hourlyForecasts.item(i);
                        Element hourForecast = (Element) nNode;
                        DateTime from = new DateTime(hourForecast.getAttribute("from"));
                        DateTime to = new DateTime(hourForecast.getAttribute("to"));
                        int temperature = Integer.parseInt(((Element) hourForecast.getElementsByTagName("temperature").item(0)).getAttribute("value"));
                        double precipitation = Double.parseDouble(((Element) hourForecast.getElementsByTagName("precipitation").item(0)).getAttribute("value"));
                        String cloudiness = ((Element) hourForecast.getElementsByTagName("symbol").item(0)).getAttribute("name");

                        Messages.ForecastMessage message = new Messages.ForecastMessage(city, altitude, latitude, longitude, sunrise, sunset, from, temperature, precipitation, cloudiness);

                        // Tell DBActor to save in DB
                        mediator.tell(new DistributedPubSubMediator.Publish(forecastTopic, message), self());
                    }
                } catch (ParserConfigurationException | SAXException | IOException e) {
                    e.printStackTrace();
                }
            } catch (UnirestException ue) {
                ue.printStackTrace();
            }
        });
    }

    @Override
    public void onReceive(Object message) {
        log.info("Received: {} from {}", message, getSender());
    }
}
