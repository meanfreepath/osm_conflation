package Conflation;

import OSM.OSMNode;
import OSM.Point;
import OSM.Region;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by nick on 11/9/15.
 */
public class LineSegment {
    private final static DecimalFormat DEBUG_OUTPUT_FORMATTER = new DecimalFormat("#.####");
    public final Point originPoint, midPoint, destinationPoint;
    public final OSMNode originNode, destinationNode;
    public WaySegments parentSegments;

    /**
     * The index of the originNode in the parent Way (if originNode is null, should be the index of the most recent node in the way)
     */
    public int nodeIndex;
    public int segmentIndex;
    public final List<WaySegments> candidateWaySegments;
    public final Map<Long, List<SegmentMatch>> matchingSegments;
    public final Map<Long, SegmentMatch> bestMatchForLine;
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;
    public final double length;
    public final Region searchAreaForMatchingOtherSegments;

    public LineSegment(final WaySegments parentSegments, final Point origin, final Point destination, final OSMNode originNode, final OSMNode destinationNode, final int segmentIndex, final int nodeIndex) {
        this.parentSegments = parentSegments;
        originPoint = origin;
        destinationPoint = destination;
        this.originNode = originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = nodeIndex;
        this.segmentIndex = segmentIndex;

        candidateWaySegments = new ArrayList<>(16);
        matchingSegments = new HashMap<>(8);
        bestMatchForLine = new HashMap<>(8);

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
        this.parentSegments = segmentToCopy.parentSegments;
        this.originPoint = segmentToCopy.originPoint;
        this.destinationPoint = destination;
        this.originNode = segmentToCopy.originNode;
        this.destinationNode = destinationNode;
        this.nodeIndex = segmentToCopy.nodeIndex;
        this.segmentIndex = segmentToCopy.segmentIndex;

        candidateWaySegments = new ArrayList<>(segmentToCopy.candidateWaySegments);
        matchingSegments = new HashMap<>(segmentToCopy.matchingSegments);
        bestMatchForLine = new HashMap<>(segmentToCopy.bestMatchForLine);

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

    public void addMatch(final SegmentMatch match) {
        List<SegmentMatch> matchesForLine = matchingSegments.get(match.mainSegment.parentSegments.way.osm_id);
        if(matchesForLine == null) {
            matchesForLine = new ArrayList<>(8);
            matchingSegments.put(match.mainSegment.parentSegments.way.osm_id, matchesForLine);
        }
        matchesForLine.add(match);
    }
    public void chooseBestMatch(final WaySegments routeLine) {
        //look up the matching segments we have for the given route line
        final List<SegmentMatch> matchesForLine = matchingSegments.get(routeLine.way.osm_id);
        if(matchesForLine == null) {
            return;
        }

        //and loop through them, picking the one with the best score as the best match
        double minScore = Double.MAX_VALUE, matchScore;
        SegmentMatch bestMatch = null;
        for(final SegmentMatch match : matchesForLine) {
            //choose the best match based on the product of their dot product and distance score
            matchScore = (match.orthogonalDistance * match.orthogonalDistance + match.midPointDistance * match.midPointDistance) / Math.max(0.000001, Math.abs(match.dotProduct));
            if(bestMatch == null || matchScore < minScore) {
                bestMatch = match;
                minScore = matchScore;
            }
        }
        bestMatchForLine.put(routeLine.way.osm_id, bestMatch);
    }
    public void copyMatches(final LineSegment fromSegment) {
        //TODO: may be more accurate to rematch rather than copy
        matchingSegments.clear();
        bestMatchForLine.clear();
        for(final Map.Entry<Long, List<SegmentMatch>> matches : fromSegment.matchingSegments.entrySet()) {
            final List<SegmentMatch> matchForLine = new ArrayList<>(matches.getValue().size());
            matchForLine.addAll(matches.getValue());
            matchingSegments.put(matches.getKey(), matches.getValue());
        }
        bestMatchForLine.putAll(fromSegment.bestMatchForLine);
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
        return "wayseg " + parentSegments.way.osm_id + " #" + nodeIndex + "/" + segmentIndex + " ([" + DEBUG_OUTPUT_FORMATTER.format(originPoint.latitude) + "," + DEBUG_OUTPUT_FORMATTER.format(originPoint.longitude) + "], [" + DEBUG_OUTPUT_FORMATTER.format(destinationPoint.latitude) + "," + DEBUG_OUTPUT_FORMATTER.format(destinationPoint.longitude) + "]) NODES " + (originNode != null ? originNode.osm_id : "NULL") + "/" +  (destinationNode != null ? destinationNode.osm_id : "NULL");
    }
}
