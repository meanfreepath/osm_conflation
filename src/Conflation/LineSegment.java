package Conflation;

import OSM.OSMNode;
import OSM.Point;
import OSM.Region;
import OSM.SphericalMercator;

import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.zip.CRC32;

/**
 * Created by nick on 11/9/15.
 */
public abstract class LineSegment {
    private final static DecimalFormat DEBUG_OUTPUT_FORMATTER = new DecimalFormat("#.####");
    private final static String ID_HASH_FORMAT = "%d:%d:%.03f,%.03f:%.03f,%.03f";
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
    public int nodeIndex, segmentIndex;
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;
    public final double length;
    public final Region boundingBox, searchAreaForMatchingOtherSegments;

    private static long generateIdForPoints(final long parent_id, final int segmentIndex, final Point origin, final Point destination) {
        idGenerator.reset();
        idGenerator.update(String.format(ID_HASH_FORMAT, parent_id, segmentIndex, origin.y, origin.x, destination.y, destination.x).getBytes(Charset.forName("ascii")));
        return idGenerator.getValue();
    }
    public LineSegment(final long parent_id, final Point origin, final Point destination, final OSMNode originNode, final OSMNode destinationNode, final int segmentIndex, final int nodeIndex) {
        originPoint = origin;
        destinationPoint = destination;
        this.originNode = originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = nodeIndex;
        this.segmentIndex = segmentIndex;
        this.id = generateIdForPoints(parent_id, segmentIndex, originPoint, destinationPoint);

        vectorX = destinationPoint.x - originPoint.x;
        vectorY = destinationPoint.y - originPoint.y;
        vectorMagnitude = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        orthogonalVectorX = -vectorY;
        //noinspection SuspiciousNameCombination
        orthogonalVectorY = vectorX;

        midPointX = originPoint.x + 0.5 * vectorX;
        midPointY = originPoint.y + 0.5 * vectorY;
        midPoint = new Point(midPointX, midPointY);

        length = Point.distance(vectorX, vectorY, midPointY);
        boundingBox = new Region(Math.min(originPoint.x, destinationPoint.x), Math.min(originPoint.y, destinationPoint.y), Math.abs(vectorX), Math.abs(vectorY));
        final double searchAreaBuffer = -SphericalMercator.metersToCoordDelta(RouteConflator.wayMatchingOptions.segmentSearchBoxSize, midPointY);
        searchAreaForMatchingOtherSegments = boundingBox.regionInset(searchAreaBuffer, searchAreaBuffer);
    }
    public LineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        this.originPoint = segmentToCopy.originPoint;
        this.destinationPoint = destination;
        this.originNode = segmentToCopy.originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = segmentToCopy.nodeIndex;
        this.segmentIndex = segmentToCopy.segmentIndex;
        this.id = generateIdForPoints(segmentToCopy.getParent().way.osm_id, segmentIndex, originPoint, destinationPoint);

        vectorX = destinationPoint.x - originPoint.x;
        vectorY = destinationPoint.y - originPoint.y;
        vectorMagnitude = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        orthogonalVectorX = -vectorY;
        //noinspection SuspiciousNameCombination
        orthogonalVectorY = vectorX;

        midPointX = originPoint.x + 0.5 * vectorX;
        midPointY = originPoint.y + 0.5 * vectorY;
        midPoint = new Point(midPointX, midPointY);

        length = Point.distance(vectorX, vectorY, midPointY);
        boundingBox = new Region(Math.min(originPoint.x, destinationPoint.x), Math.min(originPoint.y, destinationPoint.y), Math.abs(vectorX), Math.abs(vectorY));
        final double searchAreaBuffer = -SphericalMercator.metersToCoordDelta(RouteConflator.wayMatchingOptions.segmentSearchBoxSize, midPointY);
        searchAreaForMatchingOtherSegments = boundingBox.regionInset(searchAreaBuffer, searchAreaBuffer);
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
        final double apX = point.x - originPoint.x;
        final double apY = point.y - originPoint.y;

        final double ab2 = vectorX * vectorX + vectorY * vectorY;
        final double ap_ab = apX * vectorX + apY * vectorY;
        double t = ap_ab / ab2;

        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        return new Point(originPoint.x + vectorX * t, originPoint.y + vectorY * t);
    }
}
