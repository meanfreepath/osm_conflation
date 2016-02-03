package Conflation;

import OSM.OSMNode;
import OSM.Point;
import OSM.Region;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 11/9/15.
 */
public class LineSegment {
    private final static DecimalFormat DEBUG_OUTPUT_FORMATTER = new DecimalFormat("#.####");
    public Point originPoint, destinationPoint;
    public OSMNode originNode, destinationNode;
    public final WaySegments parentSegments;

    /**
     * The index of the originNode in the parent Way (if originNode is null, should be the index of the most recent node in the way)
     */
    public int nodeIndex;
    public int segmentIndex;
    public final List<WaySegments> candidateWaySegments = new ArrayList<>(16);
    public final List<SegmentMatch> matchingSegments = new ArrayList<>(16);
    public SegmentMatch bestMatch = null;
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;
    public final double length;

    public LineSegment(final WaySegments parentSegments, final Point origin, final Point destination, final OSMNode originNode, final OSMNode destinationNode, final int segmentIndex, final int nodeIndex) {
        this.parentSegments = parentSegments;
        originPoint = origin;
        destinationPoint = destination;
        this.originNode = originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = nodeIndex;
        this.segmentIndex = segmentIndex;

        vectorX = destination.longitude - origin.longitude;
        vectorY = destination.latitude - origin.latitude;
        vectorMagnitude = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        orthogonalVectorX = -vectorY;
        //noinspection SuspiciousNameCombination
        orthogonalVectorY = vectorX;

        midPointX = origin.longitude + 0.5 * vectorX;
        midPointY = origin.latitude + 0.5 * vectorY;

        length = Point.distance(vectorY, vectorX);
    }
    public Region getBoundingBox() {
        return new Region(Math.min(originPoint.latitude, destinationPoint.latitude), Math.min(originPoint.longitude, destinationPoint.longitude), Math.abs(vectorY), Math.abs(vectorX));
    }

    public void chooseBestMatch() {
        double minScore = Double.MAX_VALUE, matchScore;
        for(final SegmentMatch match : matchingSegments) {
            //choose the best match based on the product of their dot product and distance score
            matchScore = (match.orthogonalDistance * match.orthogonalDistance + match.midPointDistance * match.midPointDistance) / Math.max(0.000001, Math.abs(match.dotProduct));
            if(bestMatch == null || matchScore < minScore) {
                bestMatch = match;
                minScore = matchScore;
            }
        }
    }
    public void copyMatches(final LineSegment fromSegment) {
        //TODO: may be more accurate to rematch rather than copy
        matchingSegments.addAll(fromSegment.matchingSegments);
        bestMatch = fromSegment.bestMatch;
    }

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
    public String toString() {
        return "wayseg " + parentSegments.way.osm_id + " #" + nodeIndex + "/" + segmentIndex + " ([" + DEBUG_OUTPUT_FORMATTER.format(originPoint.latitude) + "," + DEBUG_OUTPUT_FORMATTER.format(originPoint.longitude) + "], [" + DEBUG_OUTPUT_FORMATTER.format(destinationPoint.latitude) + "," + DEBUG_OUTPUT_FORMATTER.format(destinationPoint.longitude) + "])";
    }
}
