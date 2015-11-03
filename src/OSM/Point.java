package OSM;

/**
 * Created by nick on 10/29/15.
 */
public class Point {
    public double latitude, longitude;

    public Point(double lat, double lon) {
        latitude = lat;
        longitude = lon;
    }
    public Point(String lat, String lon) {
        latitude = Double.parseDouble(lat);
        longitude = Double.parseDouble(lon);
    }
}