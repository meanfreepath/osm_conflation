package OSM;

/**
 * Created by nick on 10/29/15.
 */
public class Region {
    public final Point origin, extent;

    public static boolean intersects(Region region1, Region region2) {
        return !(region1.extent.longitude < region2.origin.longitude ||
                region1.origin.longitude > region2.extent.longitude ||
                region1.extent.latitude < region2.origin.latitude ||
                region1.origin.latitude > region2.extent.latitude);
    }
    /*public static Region combinedRegionBox(Region region1, Region region2) {
        if(region1 == null) {
            if(region2 == null) {
                return null;
            }
            return region2;
        } else if(region2 == null) {
            if(region1 == null) {
                return null;
            }
            return region1;
        }

        double minLat, minLon, maxLat, maxLon;
        minLat = Math.min(region1.origin.latitude, region2.origin.latitude);
        minLon = Math.min(region1.origin.longitude, region2.origin.longitude);
        maxLat = Math.max(region1.extent.latitude, region2.extent.latitude);
        maxLon = Math.max(region1.extent.longitude, region2.extent.longitude);
        return new Region(new Point(minLat, minLon), new Point(maxLat, maxLon));
    }*/
    public Region(Point origin, Point extent) {
        this.origin = new Point(origin.latitude, origin.longitude);
        this.extent = new Point(extent.latitude, extent.longitude);
    }
    public Region(double latitude, double longitude, double latitudeDelta, double longitudeDelta) {
        origin = new Point(latitude, longitude);
        extent = new Point(latitude + latitudeDelta, longitude + longitudeDelta);
    }
    public void combinedBoxWithRegion(Region otherRegion) {
        if(otherRegion == null) {
            return;
        }
        origin.latitude = Math.min(origin.latitude, otherRegion.origin.latitude);
        origin.longitude = Math.min(origin.longitude, otherRegion.origin.longitude);
        extent.latitude = Math.max(extent.latitude, otherRegion.extent.latitude);
        extent.longitude = Math.max(extent.longitude, otherRegion.extent.longitude);
    }

    /**
     * Returns a region inset by the given amounts.  Negative values will expand
     * @param bufferLat
     * @param bufferLon
     * @return
     */
    public Region regionInset(double bufferLat, double bufferLon) {
        Region insetRegion = new Region(origin, extent);
        insetRegion.origin.latitude += bufferLat * 0.5;
        insetRegion.extent.latitude -= bufferLat * 0.5;
        insetRegion.origin.longitude += bufferLon * 0.5;
        insetRegion.extent.longitude -= bufferLon * 0.5;
        return insetRegion;
    }

    public static Point computeCentroid(final Point[] vertices) {
        Point centroid = new Point(0, 0);
        double signedArea = 0.0;
        double x0 = 0.0; // Current vertex X
        double y0 = 0.0; // Current vertex Y
        double x1 = 0.0; // Next vertex X
        double y1 = 0.0; // Next vertex Y
        double a = 0.0;  // Partial signed area

        // For all vertices except last
        int i=0;
        for (i=0; i<vertices.length-1; ++i) {
            x0 = vertices[i].longitude;
            y0 = vertices[i].latitude;
            x1 = vertices[i+1].longitude;
            y1 = vertices[i+1].latitude;
            a = x0*y1 - x1*y0;
            signedArea += a;
            centroid.longitude += (x0 + x1)*a;
            centroid.latitude += (y0 + y1)*a;
        }

        // Do last vertex separately to avoid performing an expensive
        // modulus operation in each iteration.
        x0 = vertices[i].longitude;
        y0 = vertices[i].latitude;
        x1 = vertices[0].longitude;
        y1 = vertices[0].latitude;
        a = x0*y1 - x1*y0;
        signedArea += a;
        centroid.longitude += (x0 + x1)*a;
        centroid.latitude += (y0 + y1)*a;

        signedArea *= 0.5;
        centroid.longitude /= (6.0*signedArea);
        centroid.latitude /= (6.0*signedArea);

        return centroid;
    }
}
