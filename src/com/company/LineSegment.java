package com.company;

import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 11/9/15.
 */
public class LineSegment {
    public final static double DEGREE_DISTANCE_AT_EQUATOR = 111319.490779206;
    public static double MAX_SEGMENT_LENGTH = 10.0;
    public final OSMWay segmentWay;
    public final OSMNode originNode, destinationNode;
    public final WaySegments parentSegments;
    public final List<WaySegments> candidateSegments = new ArrayList<>(1024);
    public final List<SegmentMatch> matchingSegments = new ArrayList<>(512);
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    public final double vectorMagnitude;

    public LineSegment(final WaySegments parentSegments, final OSMWay segmentWay) {
        this.parentSegments = parentSegments;
        this.segmentWay = segmentWay;

        originNode = segmentWay.getNodes().get(0);
        destinationNode = segmentWay.getNodes().get(1);
        vectorX = destinationNode.getLon() - originNode.getLon();
        vectorY = destinationNode.getLat() - originNode.getLat();
        vectorMagnitude = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        orthogonalVectorX = -vectorY;
        //noinspection SuspiciousNameCombination
        orthogonalVectorY = vectorX;

        midPointX = originNode.getLon() + 0.5 * vectorX;
        midPointY = originNode.getLat() + 0.5 * vectorY;
    }
    public double getLength() {
        return DEGREE_DISTANCE_AT_EQUATOR * Math.sqrt(vectorX * vectorX * Math.cos(midPointY * Math.PI / 180.0) + vectorY * vectorY);
    }
}
