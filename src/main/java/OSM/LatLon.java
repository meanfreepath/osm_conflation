package OSM;

/**
 * Simple container class for lat/lon coordinates
 * Created by nick on 10/22/16.
 */
public class LatLon {
    public final double latitude, longitude;

    public LatLon(final double longitude, final double latitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
    @Override
    public String toString() {
        return String.format("LatLon [%.06f, %.06f]", latitude, longitude);
    }
}
