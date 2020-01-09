package OSM;

/**
 * Class for converting between Lat/Lon (SRID 4326) and Spherical Mercator (SRID 90017) coordinate systems
 * Created by nick on 10/22/16.
 */
public class SphericalMercator {
    public final static double r_equator = 6378137.0;

    public static double deg2rad(final double d) {
        return d * Math.PI / 180.0;
    }
    public static double rad2deg(final double r) {
        return 180.0 * r / Math.PI;
    }
    public static double transformLatToY(final double lat) {
        return Math.log(Math.tan(Math.PI / 4.0 + deg2rad(lat) / 2.0)) * r_equator;
    }
    public static double transformLonToX(final double lon) {
        return deg2rad(lon) * r_equator;
    }
    public static double transformYToLat(final double y) {
        return rad2deg(2.0 * Math.atan(Math.exp(y / r_equator)) - Math.PI / 2.0);
    }
    public static double transformXToLon(final double x) {
        return rad2deg(x / r_equator);
    }
    public static Point latLonToMercator(final double lat, final double lon) {
        return new Point(transformLonToX(lon), transformLatToY(lat));
    }
    public static Point latLonToMercator(final Point latLonPoint) {
        return new Point(transformLonToX(latLonPoint.x), transformLatToY(latLonPoint.y));
    }
    public static Region latLonToMercator(final Region latLonRegion) {
        return new Region(latLonToMercator(latLonRegion.origin), latLonToMercator(latLonRegion.extent));
    }
    public static LatLon mercatorToLatLon(final double x, final double y) {
        return new LatLon(transformXToLon(x), transformYToLat(y));
    }
    public static LatLon mercatorToLatLon(final Point mercatorPoint) {
        return new LatLon(transformXToLon(mercatorPoint.x), transformYToLat(mercatorPoint.y));
    }
    public static LatLonRegion mercatorToLatLon(final Region mercatorRegion) {
        return new LatLonRegion(mercatorToLatLon(mercatorRegion.origin), mercatorToLatLon(mercatorRegion.extent));
    }
    /**
     * Finds the distance between two Mercator points
     * @param point1
     * @param point2
     * @return
     */
    public double distance(final Point point1, final Point point2) {
        return coordDeltaToMeters(Math.sqrt((point1.x - point2.x) * (point1.x - point2.x) + (point1.y - point2.y) * (point1.y - point2.y)), 0.5 * (point1.y + point2.y));
    }
    public double distance(final double y1, final double x1, final double y2, final double x2) {
        return coordDeltaToMeters(Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)), 0.5 * (y1 + y2));
    }
    public double distance(final double dX, final double dY, final double y) {
        return coordDeltaToMeters(Math.sqrt(dX * dX + dY * dY), y);
    }
    public static double metersToCoordDelta(final double distanceInMeters, final double y) {
        return distanceInMeters / Math.cos(deg2rad(transformYToLat(y)));
    }
    public static double coordDeltaToMeters(final double coordDelta, final double y) {
        return coordDelta * Math.cos(deg2rad(transformYToLat(y)));
    }
}
