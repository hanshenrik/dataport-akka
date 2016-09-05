package no.ntnu.dataport.utils;

import no.ntnu.dataport.types.Position;

/**
 * Jason Winn
 * http://jasonwinn.org
 * Created July 10, 2013
 *
 * Description: Small class that provides approximate distance between
 * two points using the Haversine formula.
 *
 * Call in a static context:
 * Haversine.distance(47.6788206, -122.3271205,
 *                    47.6788206, -122.5271205)
 * 14.973190481586224 [km] (we changed to meters)
 *
 */

public class Haversine {

    private static final int EARTH_RADIUS = 6371 * 1000; // Approx Earth radius in meters

    public static double distance(double startLat, double startLong,
                                  double endLat, double endLong) {
        double dLat  = Math.toRadians((endLat - startLat));
        double dLong = Math.toRadians((endLong - startLong));
        startLat = Math.toRadians(startLat);
        endLat   = Math.toRadians(endLat);
        double a = haversine(dLat) + Math.cos(startLat) * Math.cos(endLat) * haversine(dLong);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS * c;
    }

    public static double haversine(double val) {
        return Math.pow(Math.sin(val / 2), 2);
    }

    public static double distance(Position p1, Position p2) {
        return distance(p1.lat, p1.lon, p2.lat, p2.lon);
    }
}
