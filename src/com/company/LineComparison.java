package com.company;

import OSM.OSMWay;
import OSM.Point;
import OSM.Region;

import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 11/3/15.
 */
public class LineComparison {
    public final WaySegments mainLine;
    private final List<OSMWay> candidateWays;
    public final HashMap<Long, WaySegments> candidateLines;
    public final ComparisonOptions options;
    public final boolean debug;

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

    public LineComparison(final OSMWay line, final List<OSMWay> candidates, final ComparisonOptions options, final boolean debug) {
        this.debug = debug;
        mainLine = new WaySegments(line, options.maxSegmentLength, debug);
        candidateWays = candidates;
        this.options = options;

        candidateLines = new HashMap<>(candidateWays.size());

        LineMatch.debug = debug;
    }

    public void matchLines() {
        //first compile a list of OSM ways whose bounding boxes intersect *each segment* of the main line
        final double latitudeDelta = -options.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * mainLine.segments.get(0).originPoint.latitude / 180.0);
        for(final LineSegment mainLineSegment : mainLine.segments) {
            final Region mainBoundingBox = mainLineSegment.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            for(final OSMWay candidateWay : candidateWays) {
                //don't match against any lines that have been marked as "ethereal", such as other gtfs shape lines
                if(candidateWay.hasTag("gtfs:ethereal")) {
                    continue;
                }

                //check for candidate lines whose bounding box intersects this segment
                if(Region.intersects(mainBoundingBox, candidateWay.getBoundingBox().regionInset(latitudeDelta, longitudeDelta))) {
                    final WaySegments candidateSegments;
                    if(!candidateLines.containsKey(candidateWay.osm_id)) {
                        candidateSegments = new WaySegments(candidateWay, options.maxSegmentLength, debug);
                        candidateLines.put(candidateWay.osm_id, candidateSegments);
                    } else {
                        candidateSegments = candidateLines.get(candidateWay.osm_id);
                    }

                    //and add to the candidate list for this segment
                    mainLineSegment.candidateWaySegments.add(candidateSegments);
                }
            }
        }

        //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
        for(final LineSegment mainSegment : mainLine.segments) {
            for(final WaySegments candidateLine : mainSegment.candidateWaySegments) {
                for(final LineSegment candidateSegment : candidateLine.segments) {
                    SegmentMatch.checkCandidateForMatch(this, mainSegment, candidateSegment, candidateLine.matchObject);
                }
            }
        }

        //consolidate the segment match data for each matching line
        for(final WaySegments line : candidateLines.values()) {
            line.matchObject.summarize();
        }

        //now that each segment has been matched (or not), we need to compile the data


        //run additional checks to ensure the matches are contiguous


        //split ways that only partially overlap the main way
    }
}
