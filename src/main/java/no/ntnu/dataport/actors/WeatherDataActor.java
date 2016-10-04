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
import no.ntnu.dataport.types.Position;
import org.influxdb.dto.Point;
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
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                () -> {
                    getAndPublishWeatherForecast();
                    getAndPublishUVForecast();
                },
                getContext().dispatcher());

    }

    private void getAndPublishWeatherForecast() {
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

    private void getAndPublishUVForecast() {
        Map<String, Position> closestForecastPositionToCityMap = new HashMap<>();
        closestForecastPositionToCityMap.put("Trondheim", new Position(63.50, 10.50));
        closestForecastPositionToCityMap.put("Vejle", new Position(55.75, 9.50));
        // TODO: use city name and position provided in DataportMain and just round to closest 0.25
        Pattern pattern = Pattern.compile("http://.*?time=(.*?);.*?content_type=text%2Fxml");
        Map<String, DateTime> uvForecastURLsToCheck = new HashMap<>();
        String availableUVForecastsURL = "http://api.met.no/weatherapi/uvforecast/1.0/available";

        // The available forecast URLs are provided on one page. First retrieve these URLs, then query them
        try {
            HttpResponse<String> response = Unirest.get(availableUVForecastsURL)
                    .asString();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringElementContentWhitespace(true);
            try {
                DocumentBuilder builder = factory.newDocumentBuilder();
                InputSource inputSource = new InputSource(new StringReader(response.getBody()));
                Document document = builder.parse(inputSource);
                document.getDocumentElement().normalize();

                NodeList availableForecastsURLs = document.getElementsByTagName("uri");
                for (int i = 0; i < availableForecastsURLs.getLength(); i++) {
                    String url = availableForecastsURLs.item(i).getTextContent();
                    Matcher matcher = pattern.matcher(url);
                    if (matcher.matches()) {
                        DateTime time = new DateTime(URLDecoder.decode(matcher.group(1), "ascii"));
                        uvForecastURLsToCheck.put(url, time);
                    }
                }
            } catch (ParserConfigurationException | SAXException | IOException e) {
                e.printStackTrace();
            }
        } catch (UnirestException ue) {
            ue.printStackTrace();
        }

        // For each available forecast, retrieve the ones for the locations we are interested in
        uvForecastURLsToCheck.forEach(
                (url, time) -> {
                    try {
                        HttpResponse<String> response = Unirest.get(url)
                                .asString();
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setIgnoringElementContentWhitespace(true);
                        try {
                            DocumentBuilder builder = factory.newDocumentBuilder();
                            InputSource inputSource = new InputSource(new StringReader(response.getBody()));
                            Document document = builder.parse(inputSource);
                            document.getDocumentElement().normalize();

                            closestForecastPositionToCityMap.forEach(
                                    (city, position) -> {
                                        NodeList locations = document.getElementsByTagName("location");
                                        for (int i = 0; i < locations.getLength(); i++) {
                                            Node location = locations.item(i);
                                            String nodeLatitude = location.getAttributes().getNamedItem("latitude").getNodeValue();
                                            String nodeLongitude = location.getAttributes().getNamedItem("longitude").getNodeValue();
                                            if (Double.parseDouble(nodeLatitude) == position.lat &&
                                                    Double.parseDouble(nodeLongitude) == position.lon) {

                                                Element uvElement = (Element) ((Element) location).getElementsByTagName("uv").item(0);
                                                NodeList parameters = uvElement.getElementsByTagName("*");
                                                Map<String, Object> parameterMap = new HashMap<>();
                                                for (int j = 0; j < parameters.getLength(); j++) {
                                                    Node nNode = parameters.item(j);
                                                    Element parameter = (Element) nNode;
                                                    parameterMap.put(parameter.getTagName(), Double.parseDouble(parameter.getAttribute("value")));
                                                }
                                                Point point = Point.measurement("uv_forecast")
                                                        .time(time.getMillis(), TimeUnit.MILLISECONDS)
                                                        .tag("city", city)
                                                        .fields(parameterMap)
                                                        .build();

                                                // Tell DBActor to save in DB
                                                mediator.tell(new DistributedPubSubMediator.Publish(forecastTopic, point), self());
                                            }
                                        }
                                    }
                            );
                        } catch (ParserConfigurationException | SAXException | IOException e) {
                            e.printStackTrace();
                        }
                    } catch (UnirestException ue) {
                        ue.printStackTrace();
                    }
                }
        );
    }

    @Override
    public void onReceive(Object message) {
        log.info("Received: {} from {}", message, getSender());
    }
}
