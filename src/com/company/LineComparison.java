package com.company;

import OSM.OSMRelation;
import OSM.OSMWay;
import OSM.Region;

import java.util.*;

/**
 * Created by nick on 11/3/15.
 */
public class LineComparison {
    public final WaySegments mainWaySegments;
    public HashMap<Long, WaySegments> allCandidateSegments;
    public final List<OSMWay> candidateLines;
    public final ComparisonOptions options;
    public final boolean debug;

    public class LineMatch {
        public final WaySegments line;
        public final List<SegmentMatch> matchingSegments;

        public LineMatch(WaySegments line) {
            this.line = line;
            matchingSegments = new ArrayList<>(64);
        }
        public void addMatch(SegmentMatch match) {
            matchingSegments.add(match);
        }
    }

    public static class ComparisonOptions {
        public double maxSegmentLength = 5.0, maxSegmentOrthogonalDistance = 10.0, maxSegmentMidPointDistance = 20.0;
        private double minSegmentDotProduct;
        public void setMaxSegmentAngle(final double angle) {
            minSegmentDotProduct = Math.cos(angle);
        }
        public double getMinSegmentDotProduct() {
            return minSegmentDotProduct;
        }
    }

    public LineComparison(final OSMWay line, final List<OSMWay> candidates, ComparisonOptions options, boolean debug) {
        this.debug = debug;
        mainWaySegments = new WaySegments(line, debug);
        candidateLines = candidates;
        this.options = options;
    }

    public void matchLines(OSMRelation relation) {
        //first compile a list of OSM ways whose bounding boxes intersect *each segment* of the main line
        allCandidateSegments = new HashMap<>(candidateLines.size());
        for(final LineSegment mainLineSegment : mainWaySegments.segments) {
            final Region mainBoundingBox = mainLineSegment.segmentWay.getBoundingBox().regionInset(-0.0004, -0.0004);
            for(final OSMWay candidateLine : candidateLines) {
                //check for candidate lines whose bounding box intersects this segment
                if(Region.intersects(mainBoundingBox, candidateLine.getBoundingBox().regionInset(-0.0004, -0.0004))) {
                    final WaySegments candidateSegments;
                    if(!allCandidateSegments.containsKey(candidateLine.osm_id)) {
                        candidateSegments = new WaySegments(candidateLine, debug);
                        allCandidateSegments.put(candidateLine.osm_id, candidateSegments);
                    } else {
                        candidateSegments = allCandidateSegments.get(candidateLine.osm_id);
                    }

                    //and add to the candidate list for this segment
                    mainLineSegment.candidateSegments.add(candidateSegments);
                }
            }
        }

        //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
        int i = 0;
        final Map<Long, LineMatch> wayMatches = new HashMap<>(candidateLines.size());
        for(final LineSegment mainSegment :  mainWaySegments.segments) {
            for(final WaySegments candidateLine : mainSegment.candidateSegments) {
                LineMatch lineMatch;
                if(wayMatches.containsKey(candidateLine.line.osm_id)) {
                    lineMatch = wayMatches.get(candidateLine.line.osm_id);
                } else {
                    lineMatch = new LineMatch(candidateLine);
                    wayMatches.put(candidateLine.line.osm_id, lineMatch);
                }
                System.out.println("segment #" + i + ": " + candidateLine.line.osm_id + ":: " + candidateLine.line.getTag("highway") + ":" + candidateLine.line.getTag("name") + "(" + candidateLine.segments.size() + " seg)");
                for(final LineSegment candidateSegment : candidateLine.segments) {
                    SegmentMatch.checkCandidateForMatch(this, mainSegment, candidateSegment, lineMatch);
                }
            }
            i++;
        }

        double avgDotProduct, avgDistance;
        int segMatchCount;
        for(final LineMatch match : wayMatches.values()) {
            segMatchCount = match.matchingSegments.size();
            avgDotProduct = avgDistance = 0.0;
            for(final SegmentMatch sMatch : match.matchingSegments) {
                avgDistance += sMatch.orthogonalDistance;
                avgDotProduct += Math.abs(sMatch.dotProduct);
            }
            avgDistance /= segMatchCount;
            avgDotProduct /= segMatchCount;

            //lineMatch.getSummary();

            if(segMatchCount > 0) {
                System.out.println(match.line.line.osm_id + "/" + match.line.line.getTag("name") + ": " + segMatchCount + " match: " + 0.1 * Math.round(10.0 * avgDistance) + "/" + (0.01 * Math.round(100.0 * avgDotProduct)));

                if(segMatchCount > 4 && avgDistance < 8.0 && avgDotProduct > 0.9) {
                    relation.addMember(match.line.line, "");
                }
            }
        }

        HashMap<Long, OSMWay> condensedWays = new HashMap<>(64);
        /*for(final LineSegment seg : mainWaySegments.segments) {
            for(final SegmentMatch matchingSeg : seg.matchingOSMSegments.values()) {
                condensedWays.put(matchingSeg.matchingSegment.parentSegments.line.osm_id, matchingSeg.matchingSegment.parentSegments.line);
            }
            /*if(seg.bestMatch != null) {
            //    condensedWays.put(seg.bestMatch.matchingSegment.parentSegments.line.osm_id, seg.bestMatch.matchingSegment.parentSegments.line);
            }*
        }
        for(OSMWay way : condensedWays.values()) {
            //System.out.println("match: " + way.getTag("name"));
            relation.addMember(way, "");
        }*/


        //now that each segment has been matched (or not), we need to compile the data


        //run additional checks to ensure the matches are contiguous


        //split ways that only partially overlap the main way
    }
}
