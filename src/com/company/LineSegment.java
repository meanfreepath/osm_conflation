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
    public static double MATCH_MAX_DISTANCE = MAX_SEGMENT_LENGTH, MATCH_MIN_DOT_PRODUCT = 0.98;
    public final OSMWay segmentWay;
    public final OSMNode originNode, destinationNode;
    public final WaySegments parentSegments;
    public final List<WaySegments> candidateSegments = new ArrayList<>(128);
    public final double vectorX, vectorY, orthogonalVectorX, orthogonalVectorY, midPointX, midPointY;
    private final double vectorMagnitude;

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
    public static void checkCandidateForMatch(final LineSegment segment1, final LineSegment segment2, LineComparison.LineMatch lineMatch) {
        //take the dot product
        final double dotProduct = (segment1.vectorX * segment2.vectorX + segment1.vectorY * segment2.vectorY) / (segment1.vectorMagnitude * segment2.vectorMagnitude);

        //find the intersection of the orthogonal vector with the candidate segment
        final double vecA, vecB, vecC, vecD, xInt, yInt;
        if(segment1.vectorY == 0.0) { //i.e. segment1 is purely east-west
            xInt = segment1.midPointX;
            if(segment2.vectorY == 0.0) { //case where both are parallel
                yInt = segment2.midPointY;
            } else {
                vecB = segment2.vectorY / segment2.vectorX;
                vecD = segment2.midPointY - vecB * segment2.midPointX;
                yInt = xInt * vecB + vecD;
            }
        } else if(segment1.vectorX == 0.0) { //i.e. segment1 is purely north-south
            yInt = segment1.midPointY;
            if (segment2.vectorX == 0.0) { //case where both are parallel
                xInt = segment2.midPointX;
            } else {
                vecB = segment2.vectorY / segment2.vectorX;
                vecD = segment2.midPointY - vecB * segment2.midPointX;
                xInt = (yInt - vecD) / vecB;
            }
        } else if(segment2.vectorY == 0.0) { //segment2 is east-west
            yInt = segment2.midPointY;
            if(segment1.vectorY == 0.0) {
                xInt = segment1.midPointX;
            } else {
                vecA = segment1.orthogonalVectorY / segment1.orthogonalVectorX;
                vecC = segment1.midPointY - vecA * segment1.midPointX;
                xInt = (yInt - vecA) / vecC;
            }
        } else if(segment2.vectorX == 0.0) { //segment2 is north-south
            xInt = segment2.midPointX;
            if(segment1.vectorX == 0.0) {
                yInt = segment2.midPointY;
            } else {
                vecA = segment1.orthogonalVectorY / segment1.orthogonalVectorX;
                vecC = segment1.midPointY - vecA * segment1.midPointX;
                yInt = xInt * vecA + vecC;
            }
        } else {
            vecA = segment1.orthogonalVectorY / segment1.orthogonalVectorX;
            vecB = segment2.vectorY / segment2.vectorX;
            vecC = segment1.midPointY - vecA * segment1.midPointX;
            vecD = segment2.midPointY - vecB * segment2.midPointX;
            xInt = (vecD - vecC) / (vecA - vecB);
            yInt = xInt * vecA + vecC;
        }

        final double oDiffX = (xInt - segment1.midPointX) * Math.cos(segment1.midPointY * Math.PI / 180.0), oDiffY = yInt - segment1.midPointY;
        final double orthogonalDistance = Math.sqrt(oDiffX * oDiffX + oDiffY * oDiffY) * DEGREE_DISTANCE_AT_EQUATOR;

        /*if(segment2.parentSegments.line.osm_id == 263557332){
            //System.out.println("[" + vectorX + "," + vectorY + "]...A:" + vecA + ", B:" + vecB + ", C:" + vecC + ", D:" + vecD + "::: point:(" + midPointX + "," + midPointY + "/" + ((vecA * midPointX + vecC) + ")"));
            System.out.println("DP MATCH: " + segment2.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist: (" + oDiffX + "," + oDiffY + ") " + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
        }*/

        //if the segments meet the threshold requirements, store the match in a SegmentMatch object
        if(Math.abs(dotProduct) >= MATCH_MIN_DOT_PRODUCT && orthogonalDistance <= MATCH_MAX_DISTANCE) {
            //System.out.println("DP MATCH: " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            //System.out.println("DP MATCH: " + segment2.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            final SegmentMatch match = new SegmentMatch(segment1, segment2, orthogonalDistance, dotProduct);
            lineMatch.addMatch(match);
        }
    }
}
