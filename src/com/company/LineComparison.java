package com.company;

import OSM.OSMRelation;
import OSM.OSMWay;
import OSM.Point;
import OSM.Region;

import java.util.*;

/**
 * Created by nick on 11/3/15.
 */
public class LineComparison {
    public final WaySegments mainWaySegments;
    public final List<OSMWay> candidateLines;
    public final Map<Long, LineMatch> matchingLines;
    public final ComparisonOptions options;
    public final boolean debug;

    public class LineMatch {
        private final Comparator<SegmentMatch> comp = new Comparator<SegmentMatch>() {
            @Override
            public int compare(SegmentMatch o1, SegmentMatch o2) {
                return o1.matchingSegment.segmentIndex < o2.matchingSegment.segmentIndex ? -1 : 1;
            }
        };
        public final WaySegments line;
        public final List<SegmentMatch> matchingSegments;
        private double avgDotProduct, avgDistance;

        public LineMatch(WaySegments line) {
            this.line = line;
            matchingSegments = new ArrayList<>(64);
        }
        public void addMatch(SegmentMatch match) {
            matchingSegments.add(match);
        }

        /**
         * Consolidates all the segment matches and calculates the various totals
         */
        public void summarize() {

            /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
              may match multiple segments, and there is a typically good deal of overlap).  We want only
               the unique segment matches.  NOTE: this loses the data on
             */
            HashMap<Integer, SegmentMatch> matchMap = new HashMap<>(matchingSegments.size());
            SegmentMatch curMatch;
            for(final SegmentMatch match : matchingSegments) {
                curMatch = matchMap.get(match.matchingSegment.segmentIndex);
                if(curMatch == null) {
                    matchMap.put(match.matchingSegment.segmentIndex, match);
                    curMatch = match;
                    curMatch.consolidated = true;
                }
                curMatch.consolidatedMatches++;
            }
            matchingSegments.clear();

            //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
            avgDotProduct = avgDistance = 0.0;
            for(final SegmentMatch match : matchMap.values()) {
                matchingSegments.add(match);

                avgDistance += match.orthogonalDistance;
                avgDotProduct += Math.abs(match.dotProduct);
            }
            final int segMatchCount = matchingSegments.size();
            avgDistance /= segMatchCount;
            avgDotProduct /= segMatchCount;

            //sort in debug mode, to ensure a nice output for segments
            if(debug) {
                Collections.sort(matchingSegments, comp);
            }
        }
        public double getAvgDotProduct() {
            return avgDotProduct;
        }
        public double getAvgDistance() {
            return avgDistance;
        }
    }

    public static class ComparisonOptions {
        public double maxSegmentLength = 5.0, maxSegmentOrthogonalDistance = 10.0, maxSegmentMidPointDistance = 20.0, boundingBoxSize = 50.0;
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
        mainWaySegments = new WaySegments(line, options.maxSegmentLength, debug);
        candidateLines = candidates;
        this.options = options;

        matchingLines = new HashMap<>(candidateLines.size());
    }

    public void matchLines(OSMRelation relation) {
        //first compile a list of OSM ways whose bounding boxes intersect *each segment* of the main line
        final HashMap<Long, WaySegments> allCandidateSegments = new HashMap<>(candidateLines.size());
        final double latitudeDelta = -options.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * mainWaySegments.segments.get(0).originPoint.latitude / 180.0);
        for(final LineSegment mainLineSegment : mainWaySegments.segments) {
            final Region mainBoundingBox = mainLineSegment.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            for(final OSMWay candidateLine : candidateLines) {
                //check for candidate lines whose bounding box intersects this segment
                if(Region.intersects(mainBoundingBox, candidateLine.getBoundingBox().regionInset(latitudeDelta, longitudeDelta))) {
                    final WaySegments candidateSegments;
                    if(!allCandidateSegments.containsKey(candidateLine.osm_id)) {
                        candidateSegments = new WaySegments(candidateLine, options.maxSegmentLength, debug);
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
        for(final LineSegment mainSegment :  mainWaySegments.segments) {
            for(final WaySegments candidateLine : mainSegment.candidateSegments) {
                LineMatch lineMatch;
                if(matchingLines.containsKey(candidateLine.line.osm_id)) {
                    lineMatch = matchingLines.get(candidateLine.line.osm_id);
                } else {
                    lineMatch = new LineMatch(candidateLine);
                    matchingLines.put(candidateLine.line.osm_id, lineMatch);
                }
               // System.out.println("segment #" + i + ": " + candidateLine.line.osm_id + ":: " + candidateLine.line.getTag("highway") + ":" + candidateLine.line.getTag("name") + "(" + candidateLine.segments.size() + " seg)");
                for(final LineSegment candidateSegment : candidateLine.segments) {
                    SegmentMatch.checkCandidateForMatch(this, mainSegment, candidateSegment, lineMatch);
                }
            }
            i++;
        }

        //consolidate the segment match data for each matching line
        for(final LineMatch match : matchingLines.values()) {
            match.summarize();
        }

        //now that each segment has been matched (or not), we need to compile the data


        //run additional checks to ensure the matches are contiguous


        //split ways that only partially overlap the main way
    }
}
