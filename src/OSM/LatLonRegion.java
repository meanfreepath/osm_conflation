package OSM;

/**
 * Simple container class for lat/lon coordinate areas
 * Created by nick on 10/22/16.
 */
public class LatLonRegion {
    public LatLon origin, extent;

    public LatLonRegion(final LatLon origin, final LatLon extent) {
        this.origin = new LatLon(origin.longitude, origin.latitude);
        this.extent = new LatLon(extent.longitude, extent.latitude);
    }
    public LatLonRegion(final double longitude, final double latitude, final double longitudeDelta, final double latitudeDelta) {
        origin = new LatLon(longitude, latitude);
        extent = new LatLon(longitude + longitudeDelta, latitude + latitudeDelta);
    }
}
