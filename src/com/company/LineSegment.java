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
    public final static double DEGREE_DISTANCE_AT_EQUATOR = 111319.490779206;
    public static double MAX_SEGMENT_LENGTH = 10.0;
    public final Point originPoint, destinationPoint;
    public final OSMNode originNode, destinationNode;
    public final WaySegments parentSegments;
    public final List<WaySegments> candidateSegments = new ArrayList<>(1024);
    public final List<SegmentMatch> matchingSegments = new ArrayList<>(512);
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;
    public String debugName = null;

    public LineSegment(final WaySegments parentSegments, Point origin, Point destination, OSMNode originNode, OSMNode destinationNode) {
        this.parentSegments = parentSegments;
        originPoint = origin;
        destinationPoint = destination;
        this.originNode = originNode;
        this.destinationNode = destinationNode;

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
        return DEGREE_DISTANCE_AT_EQUATOR * Math.sqrt(vectorX * vectorX * Math.cos(midPointY * Math.PI / 180.0) + vectorY * vectorY);
    }
}
