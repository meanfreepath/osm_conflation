package Conflation;

import OSM.OSMNode;
import OSM.Point;
import OSM.Region;

import java.text.DecimalFormat;
import java.util.zip.CRC32;

/**
 * Created by nick on 11/9/15.
 */
public abstract class LineSegment {
    private final static DecimalFormat DEBUG_OUTPUT_FORMATTER = new DecimalFormat("#.####");
    public final Point originPoint, midPoint, destinationPoint;
    public final OSMNode originNode, destinationNode;

    public abstract WaySegments getParent();
    public abstract void setParent(WaySegments newParent);
    public abstract void updateMatches();
    private final static CRC32 idGenerator = new CRC32();

    /**
     * The index of the originNode in the parent Way (if originNode is null, should be the index of the most recent node in the way)
     */
    public final long id;
    public int nodeIndex;
    public int segmentIndex;
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;
    public final double length;
    public final Region searchAreaForMatchingOtherSegments;

    private static long generateIdForPoints(final Point origin, final Point destination) {
        idGenerator.reset();
        idGenerator.update((origin.toString() + ":" + destination.toString()).getBytes());
        return idGenerator.getValue();
    }
    public LineSegment(final Point origin, final Point destination, final OSMNode originNode, final OSMNode destinationNode, final int segmentIndex, final int nodeIndex) {
        originPoint = origin;
        destinationPoint = destination;
        this.originNode = originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = nodeIndex;
        this.segmentIndex = segmentIndex;
        this.id = generateIdForPoints(originPoint, destinationPoint);

        vectorX = destination.longitude - origin.longitude;
        vectorY = destination.latitude - origin.latitude;
        vectorMagnitude = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        orthogonalVectorX = -vectorY;
        //noinspection SuspiciousNameCombination
        orthogonalVectorY = vectorX;

        midPointX = origin.longitude + 0.5 * vectorX;
        midPointY = origin.latitude + 0.5 * vectorY;
        midPoint = new Point(midPointY, midPointX);

        length = Point.distance(vectorY, vectorX);

        final double latitudeDelta = -RouteConflator.wayMatchingOptions.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * midPointY / 180.0);
        searchAreaForMatchingOtherSegments = getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
    }
    public LineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        this.originPoint = segmentToCopy.originPoint;
        this.destinationPoint = destination;
        this.originNode = segmentToCopy.originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = segmentToCopy.nodeIndex;
        this.segmentIndex = segmentToCopy.segmentIndex;
        this.id = generateIdForPoints(originPoint, destinationPoint);

        vectorX = destination.longitude - originPoint.longitude;
        vectorY = destination.latitude - originPoint.latitude;
        vectorMagnitude = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        orthogonalVectorX = -vectorY;
        //noinspection SuspiciousNameCombination
        orthogonalVectorY = vectorX;

        midPointX = originPoint.longitude + 0.5 * vectorX;
        midPointY = originPoint.latitude + 0.5 * vectorY;
        midPoint = new Point(midPointY, midPointX);

        length = Point.distance(vectorY, vectorX);

        final double latitudeDelta = -RouteConflator.wayMatchingOptions.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * midPointY / 180.0);
        searchAreaForMatchingOtherSegments = getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
    }
    public Region getBoundingBox() {
        return new Region(Math.min(originPoint.latitude, destinationPoint.latitude), Math.min(originPoint.longitude, destinationPoint.longitude), Math.abs(vectorY), Math.abs(vectorX));
    }


    /*public void copyMatches(final LineSegment fromSegment) {
        //TODO: may be more accurate to rematch rather than copy
        matchingSegments.clear();
        bestMatchForLine.clear();
        for(final Map.Entry<Long, List<SegmentMatch>> matches : fromSegment.matchingSegments.entrySet()) {
            final List<SegmentMatch> matchForLine = new ArrayList<>(matches.getValue().size());
            matchForLine.addAll(matches.getValue());
            matchingSegments.put(matches.getKey(), matches.getValue());
        }
        bestMatchForLine.putAll(fromSegment.bestMatchForLine);
    }*/

    /**
     * Finds the closest point on this segment to the given point
     * @param point
     * @return
     */
    public Point closestPointToPoint(final Point point) {
        final double apX = point.longitude - originPoint.longitude;
        final double apY = point.latitude - originPoint.latitude;

        final double ab2 = vectorX * vectorX + vectorY * vectorY;
        final double ap_ab = apX * vectorX + apY * vectorY;
        double t = ap_ab / ab2;

        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        return new Point(originPoint.latitude + vectorY * t, originPoint.longitude + vectorX * t);
    }
}
