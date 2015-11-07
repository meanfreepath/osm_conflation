package com.company;

import OSM.OSMNode;
import OSM.OSMRelation;
import OSM.OSMWay;
import OSM.Region;

import java.util.*;

/**
 * Created by nick on 11/3/15.
 */
public class LineComparison {
    private final static double DEGREE_DISTANCE_AT_EQUATOR = 111319.490779206;
    public final LineSegments mainLineSegments;
    public final List<OSMWay> candidateLines;
    private List<OSMWay> matchingLines;
    private ComparisonOptions options;
    public boolean debug = true;

    public class LineSegment {
        public class SegmentMatch {
            final double orthogonalDistance, dotProduct;
            final LineSegment matchingSegment;
            public SegmentMatch(LineSegment segment, double distance, double dotProduct) {
                matchingSegment = segment;
                orthogonalDistance = distance;
                this.dotProduct = dotProduct;
            }
        }

        public final OSMWay segmentWay;
        public final LineSegments parentSegments;
        public final List<LineSegments> candidateOSMSegments;
        public final HashMap<Long, SegmentMatch> matchingOSMSegments;
        public SegmentMatch bestMatch = null;
        private final double[] vector, orthogonalVector, midPoint;
        private final double vectorMagnitude;

        public LineSegment(final LineSegments parentSegments, final OSMWay segmentWay) {
            this.parentSegments = parentSegments;
            this.segmentWay = segmentWay;

            final OSMNode originNode = segmentWay.getNodes().get(0), destinationNode = segmentWay.getNodes().get(1);
            vector = new double[2];
            vector[0] = destinationNode.getLon() - originNode.getLon();
            vector[1] = destinationNode.getLat() - originNode.getLat();
            vectorMagnitude = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1]);

            orthogonalVector = new double[2];
            orthogonalVector[0] = -vector[1];
            orthogonalVector[1] = vector[0];

            midPoint = new double[2];
            midPoint[0] = originNode.getLon() + 0.5 * vector[0];
            midPoint[1] = originNode.getLat() + 0.5 * vector[1];

            candidateOSMSegments = new ArrayList<>(16);
            matchingOSMSegments = new HashMap<>(4);
         //   matchingOSMWays = new HashMap<>(4);
        }
        public void checkCandidateForMatch(final LineSegment candidateSegment) {
            //no need to check again if the candidate segment's line has already matched
            if(matchingOSMSegments.containsKey(candidateSegment.parentSegments.line.osm_id)) {
                return;
            }

            //take the dot product
            final double dotProduct = (vector[0] * candidateSegment.vector[0] + vector[1] * candidateSegment.vector[1]) / (vectorMagnitude * candidateSegment.vectorMagnitude);

            //find the intersection of the orthogonal vector with the candidate segment
            final double vecA = orthogonalVector[1] / orthogonalVector[0], vecB = candidateSegment.vector[1] / candidateSegment.vector[0];
            final double vecC = midPoint[1] - vecA * midPoint[0], vecD = candidateSegment.midPoint[1] - vecB * candidateSegment.midPoint[0];
            final double xInt = (vecD - vecC) / (vecA - vecB), yInt = xInt * vecA + vecC;

            final double oDiffX = (xInt - midPoint[0]) * Math.cos(midPoint[1] * Math.PI / 180.0), oDiffY = yInt - midPoint[1];
            final double orthogonalDistance = Math.sqrt(oDiffX * oDiffX + oDiffY * oDiffY) * DEGREE_DISTANCE_AT_EQUATOR;
            //System.out.println("[" + vector[0] + "," + vector[1] + "]...A:" + vecA + ", B:" + vecB + ", C:" + vecC + ", D:" + vecD + "::: point:(" + midPoint[0] + "," + midPoint[1] + "/" + ((vecA * midPoint[0] + vecC) + ")"));

            //double xInt = ((candidateSegment.midPoint[1] - candidateSegment.vector[1] * candidateSegment.midPoint[0] / candidateSegment.vector[0]) - (midPoint[1] - vector[1] * midPoint[0] / vector[0])) / (orthogonalVector[1] / orthogonalVector[0] - candidateSegment.orthogonalVector[1] / candidateSegment.orthogonalVector[0]);
            //double yInt = (orthogonalVector[1] / orthogonalVector[0]) * xInt + midPoint[1];
            //System.out.println();


            if(Math.abs(dotProduct) >= 0.95 && orthogonalDistance < 10.0) {
                //System.out.println("DP MATCH: " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
                //matchingOSMSegments.add(candidateSegment.parentWay);
                SegmentMatch match = new SegmentMatch(candidateSegment, orthogonalDistance, dotProduct);
                matchingOSMSegments.put(candidateSegment.parentSegments.line.osm_id, match);
            }
        }
        public void chooseBestMatch() {
            double bestDistance = 9999999999999.00, bestDotProduct = 0.0, bestScore = 0.0;
            for(SegmentMatch match : matchingOSMSegments.values()) {
                double score = match.dotProduct / match.orthogonalDistance;
                if(score >= bestScore) {
                    bestMatch = match;
                    bestScore = score;
                    bestDistance = match.orthogonalDistance;
                    bestDotProduct = match.dotProduct;
                }
            }
        }
    }

    /**
     * Container for an OSM way and its calculated line segments
     */
    public class LineSegments {
        public final OSMWay line;
        public final List<LineSegment> segments;

        public LineSegments(final OSMWay line) {
            this.line = line;

            //generate a list of line segments out of this line
            segments = new ArrayList<>(line.getNodes().size() - 1);
            OSMWay segmentWay = OSMWay.create();
            OSMNode lastNode = null;
            int segmentIndex = 0;
            for(OSMNode node: line.getNodes()) {
                if(segmentWay.getNodes().size() == 2) {
                    if(debug) {
                        segmentWay.setTag("name", "Segment #" + segmentIndex++);
                    }
                    segments.add(new LineSegment(this, segmentWay)); //add the completed segment

                    //and create a new segment, adding the last node to it
                    segmentWay = OSMWay.create();
                    segmentWay.addNode(lastNode);
                }
                segmentWay.addNode(node);
                lastNode = node;
            }
            segments.add(new LineSegment(this, segmentWay)); //add the completed segment
        }
    }

    public static class ComparisonOptions {
        public double maxSegmentDistance, maxSegmentAngle;

        public ComparisonOptions() {

        }
    }

    public LineComparison(final OSMWay line, final List<OSMWay> candidates) {
        mainLineSegments = new LineSegments(line);
        candidateLines = candidates;
    }

    public void matchLines(ComparisonOptions options, OSMRelation relation) {

        //first compile a list of OSM ways whose bounding boxes intersect *each segment* of the main line
        final HashMap<Long, LineSegments> allCandidateSegments = new HashMap<>(candidateLines.size());
        for(LineSegment mainLineSegment : mainLineSegments.segments) {
            final Region mainBoundingBox = mainLineSegment.segmentWay.getBoundingBox().regionInset(-0.0004, -0.0004);
            for(OSMWay candidateLine : candidateLines) {
                //check for candidate lines whose bounding box intersects this segment
                if(Region.intersects(mainBoundingBox, candidateLine.getBoundingBox().regionInset(-0.0004, -0.0004))) {
                    final LineSegments candidateSegments;
                    if(!allCandidateSegments.containsKey(candidateLine.osm_id)) {
                        candidateSegments = new LineSegments(candidateLine);
                        allCandidateSegments.put(candidateLine.osm_id, candidateSegments);
                    } else {
                        candidateSegments = allCandidateSegments.get(candidateLine.osm_id);
                    }

                    //and add to the candidate list for this segment
                    mainLineSegment.candidateOSMSegments.add(candidateSegments);
                }

                /*for(OSMWay candidateLineSegment : candidate.segments) { //TODO need to travel both ways
                    //compareSegments(mainLineSegment, candidateLineSegment, options);
                }*/
            }
        }

        //now that we have a rough idea of the candidate ways for each segment, check
        int i = 0;
        for(final LineSegment mainSegment :  mainLineSegments.segments) {
            for(final LineSegments candidateLine : mainSegment.candidateOSMSegments) {
           //     System.out.println("segment #" + i + ": " + candidateLine.line.osm_id + ":: " + candidateLine.line.getTag("highway") + ":" + candidateLine.line.getTag("name"));
                for(final LineSegment candidateSegment : candidateLine.segments) {
                    mainSegment.checkCandidateForMatch(candidateSegment);
                }
            }
            mainSegment.chooseBestMatch();
            i++;
        }

        HashMap<Long, OSMWay> condensedWays = new HashMap<>(64);
        for(final LineSegment seg : mainLineSegments.segments) {
            for(final LineSegment.SegmentMatch matchingSeg : seg.matchingOSMSegments.values()) {
                condensedWays.put(matchingSeg.matchingSegment.parentSegments.line.osm_id, matchingSeg.matchingSegment.parentSegments.line);
            }
            if(seg.bestMatch != null) {
            //    condensedWays.put(seg.bestMatch.matchingSegment.parentSegments.line.osm_id, seg.bestMatch.matchingSegment.parentSegments.line);
            }
        }
        for(OSMWay way : condensedWays.values()) {
            //System.out.println("match: " + way.getTag("name"));
            relation.addMember(way, "");
        }


        //now that each segment has been matched (or not), we need to compile the data


        //run additional checks to ensure the matches are contiguous


        //split ways that only partially overlap the main way
    }
}
