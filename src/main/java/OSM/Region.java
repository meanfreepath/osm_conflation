package OSM;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Created by nick on 10/29/15.
 */
public class Region {
    public @NotNull Point origin, extent;

    /**
     * Determines whether the regions intersect
     * @param region1 the first region
     * @param region2 the second region
     * @return true if the regions intersect, false if not, or if either is null
     */
    public static boolean intersects(final @Nullable Region region1, final @Nullable Region region2) {
        if(region1 == null || region2 == null) {
            return false;
        }
        return !(region1.extent.x < region2.origin.x ||
                region1.origin.x > region2.extent.x ||
                region1.extent.y < region2.origin.y ||
                region1.origin.y > region2.extent.y);
    }

    /**
     * Determines whether region1 contains region2
     * @param region1 the first region
     * @param region2 the second region
     * @return true if region1 contains region2, false if not, or if either is null
     */
    public static boolean contains(final @Nullable Region region1, final @Nullable Region region2) {
        if(region1 == null || region2 == null) {
            return false;
        }
        return region1.origin.y <= region2.origin.y &&
                region1.extent.y >= region2.extent.y &&
                region1.origin.x <= region2.origin.x &&
                region1.extent.x >= region2.extent.x;
    }

    /**
     * Generate the union box of the given regions
     * @param region1
     * @param region2
     * @return
     */
    @NotNull
    public static Region union(final @NotNull Region region1, final @NotNull Region region2) {
        final Point unionOrigin = new Point(Math.min(region1.origin.x, region2.origin.x), Math.min(region1.origin.y, region2.origin.y));
        final Point unionExtent = new Point(Math.max(region1.extent.x, region2.extent.x), Math.max(region1.extent.y, region2.extent.y));
        return new Region(unionOrigin, unionExtent);
    }
    /**
     * Get the intersection box of the given regions
     * @param region1
     * @param region2
     * @return
     */
    @Nullable
    public static Region intersection(final @NotNull Region region1, final @NotNull Region region2) {
        final double xmin = Math.max(region1.origin.y, region2.origin.y);
        final double xmax = Math.min(region1.extent.y, region2.extent.y);
        if (xmax > xmin) {
            final double ymin = Math.max(region1.origin.x, region2.origin.x);
            final double ymax = Math.min(region1.extent.x, region2.extent.x);
            if (ymax > ymin) {
                return new Region(ymin, xmin, ymax - ymin, xmax - xmin);
            }
        }
        return null;
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
        minLat = Math.min(region1.origin.y, region2.origin.y);
        minLon = Math.min(region1.origin.x, region2.origin.x);
        maxLat = Math.max(region1.extent.y, region2.extent.y);
        maxLon = Math.max(region1.extent.x, region2.extent.x);
        return new Region(new Point(minLat, minLon), new Point(maxLat, maxLon));
    }*/
    public Region(final @NotNull Point origin, final @NotNull Point extent) {
        this.origin = new Point(origin.x, origin.y);
        this.extent = new Point(extent.x, extent.y);
    }
    public Region(final double x, final double y, final double deltaX, final double deltaY) {
        origin = new Point(x, y);
        extent = new Point(x + deltaX, y + deltaY);
    }
    public Region(final @NotNull Point[] includedPoints) {
        final Point point1 = includedPoints[0], point2 = includedPoints[1];
        origin = new Point(Math.min(point1.x, point2.x), Math.min(point1.y, point2.y));
        extent = new Point(Math.max(point1.x, point2.x), Math.max(point1.y, point2.y));
        for(int p=2;p<includedPoints.length;p++) {
            includePoint(includedPoints[p]);
        }
    }
    /**
     * Copy constructor
     * @param regionToCopy the region to copy
     */
    public Region(final @NotNull Region regionToCopy) {
        origin = new Point(regionToCopy.origin.x, regionToCopy.origin.y);
        extent = new Point(regionToCopy.extent.x, regionToCopy.extent.y);
    }

    /**
     * Expand this region to include the given point
     * @param point
     */
    public void includePoint(final @NotNull Point point) {
        if(containsPoint(point)) { //bail if the point's already included within the region
            return;
        }

        //expand the region to contain the point
        if(point.x < origin.x) {
            origin = new Point(point.x, origin.y);
        }
        if(point.y < origin.y) {
            origin = new Point(origin.x, point.y);
        }
        if(point.x > extent.x) {
            extent = new Point(point.x, extent.y);
        }
        if(point.y > extent.y) {
            extent = new Point(extent.x, point.y);
        }
    }
    public void combinedBoxWithRegion(final @NotNull Region otherRegion) {
        double oY = Math.min(origin.y, otherRegion.origin.y), oX = Math.min(origin.x, otherRegion.origin.x);
        double eY = Math.max(extent.y, otherRegion.extent.y), eX = Math.max(extent.x, otherRegion.extent.x);
        origin = new Point(oX, oY);
        extent = new Point(eX, eY);
    }

    /**
     * Calculate the area of the region, in square meters
     * @return
     */
    public double area() {
        return (extent.x - origin.x) * (extent.y - origin.y);
    }
    /**
     * Check whether the given point is inside this region (inclusive)
     * @param point
     * @return
     */
    public boolean containsPoint(final @NotNull Point point) {
        return !(point.y < origin.y || point.y > extent.y || point.x < origin.x || point.x > extent.x);
    }

    /**
     * Returns a region inset by the given amounts.  Negative values will expand
     * @return
     */
    public Region regionInset(final double bufferX, final double bufferY) {
        return new Region(origin.x + 0.5 * bufferX, origin.y + 0.5 * bufferY, extent.x - origin.x - bufferX, extent.y - origin.y - bufferY);
    }
    @NotNull
    public Point getCentroid() {
        return new Point(0.5 * (origin.x + extent.x), 0.5 * (origin.y + extent.y));
    }

    /*public static Point computeCentroid(final Point[] vertices) {
        if(vertices.length < 2) {
            return null;
        }
        Point centroid = new Point(0, 0);
        double signedArea = 0.0;
        double x0; // Current vertex X
        double y0; // Current vertex Y
        double x1; // Next vertex X
        double y1; // Next vertex Y
        double a;  // Partial signed area

        // For all vertices except last
        int i;
        for (i=0; i<vertices.length-1; ++i) {
            x0 = vertices[i].x;
            y0 = vertices[i].y;
            x1 = vertices[i+1].x;
            y1 = vertices[i+1].y;
            a = x0*y1 - x1*y0;
            signedArea += a;
            centroid.x += (x0 + x1)*a;
            centroid.y += (y0 + y1)*a;
        }

        // Do last vertex separately to avoid performing an expensive
        // modulus operation in each iteration.
        x0 = vertices[i].x;
        y0 = vertices[i].y;
        x1 = vertices[0].x;
        y1 = vertices[0].y;
        a = x0*y1 - x1*y0;
        signedArea += a;
        centroid.x += (x0 + x1)*a;
        centroid.y += (y0 + y1)*a;

        signedArea *= 0.5;
        centroid.x /= (6.0*signedArea);
        centroid.y /= (6.0*signedArea);

        return centroid;
    }*/
    @Nullable
    public static Point computeCentroid2(final @NotNull Point[] vertices) {
        if(vertices.length < 2) {
            return null;
        }
        double Cx = 0.0, Cy = 0.0;
        for(final Point vertex : vertices) {
            Cx += vertex.x;
            Cy += vertex.y;
        }
        Cx /= vertices.length;
        Cy /= vertices.length;

        /*for(int i=0;i<vertices.length - 1;i++) {
            Cx += (vertices[i].x + vertices[i+1].x) * ()
        }*/
        return new Point(Cx, Cy);
    }
    @Override
    public String toString() {
        return String.format("Region([%.03f,%.03f][%.03f,%.03f])", origin.x, origin.y, extent.x, extent.y);
    }
}
