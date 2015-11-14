package com.company;

import OSM.OSMNode;
import OSM.Point;
import OSM.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 11/9/15.
 */
public class LineSegment {
    public Point originPoint, destinationPoint;
    public OSMNode originNode, destinationNode;
    public final WaySegments parentSegments;

    /**
     * The index of the originNode in the parent Way (if originNode is null, should be the index of the most recent node in the way)
     */
    public int nodeIndex;
    public int segmentIndex;
    public final List<WaySegments> candidateSegments = new ArrayList<>(1024);
    public final List<SegmentMatch> matchingSegments = new ArrayList<>(512);
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;

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
    }
    public Region getBoundingBox() {
        return new Region(Math.min(originPoint.latitude, destinationPoint.latitude), Math.min(originPoint.longitude, destinationPoint.longitude), Math.abs(vectorY), Math.abs(vectorX));
    }
    public double getLength() {
        return Point.distance(vectorY, vectorX);
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
    public String getDebugName() {
        return "Segment #" + nodeIndex + "/" + segmentIndex;
    }
}
