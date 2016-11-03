package OSM;

/**
 * Created by nick on 10/29/15.
 */
public class Point {
    public final static SphericalMercator projector = new SphericalMercator();
    public final double x, y;

    public Point(final double x, final double y) {
        this.x = x;
        this.y = y;
    }
    public Point(final String xString, final String yString) {
        x = Double.parseDouble(xString);
        y = Double.parseDouble(yString);
    }
    public Point(final Point point) {
        x = point.x;
        y = point.y;
    }

    /**
     * Calculates the distance between the given Points, using the current projection
     * @param point1
     * @param point2
     * @return distance in meters
     */
    public static double distance(final Point point1, final Point point2) {
        return projector.distance(point1, point2);
    }

    /**
     * Calculates the distance between the given coordinate pairs
     * @param x1
     * @param y1
     * @param x2
     * @param y2
     * @return distance in meters
     */
    public static double distance(final double x1, final double y1, final double x2, final double y2) {
        return projector.distance(y1, x1, y2, x2);
    }
    public static double distance(final double dX, final double dY, final double y) {
        return projector.distance(dX, dY, y);
    }
    @Override
    public String toString() {
        return String.format("Point@%d [%.03f,%.03f]", hashCode(), x, y);
    }
}