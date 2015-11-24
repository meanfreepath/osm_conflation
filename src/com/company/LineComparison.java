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
    public final List<OSMWay> candidateWays;
    public final HashMap<Long, WaySegments> matchingLines;
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

    public LineComparison(final OSMWay line, final List<OSMWay> candidates, ComparisonOptions options, boolean debug) {
        this.debug = debug;
        mainWaySegments = new WaySegments(line, options.maxSegmentLength, debug);
        candidateWays = candidates;
        this.options = options;

        matchingLines = new HashMap<>(candidateWays.size() / 2); //rough estimate that ~50% will match a segment bounding box

        LineMatch.debug = debug;
    }

    public void matchLines(OSMRelation relation) {
        //first compile a list of OSM ways whose bounding boxes intersect *each segment* of the main line
        final double latitudeDelta = -options.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * mainWaySegments.segments.get(0).originPoint.latitude / 180.0);
        for(final LineSegment mainLineSegment : mainWaySegments.segments) {
            final Region mainBoundingBox = mainLineSegment.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            for(final OSMWay candidateWay : candidateWays) {
                //check for candidate lines whose bounding box intersects this segment
                if(Region.intersects(mainBoundingBox, candidateWay.getBoundingBox().regionInset(latitudeDelta, longitudeDelta))) {
                    final WaySegments candidateSegments;
                    if(!matchingLines.containsKey(candidateWay.osm_id)) {
                        candidateSegments = new WaySegments(candidateWay, options.maxSegmentLength, debug);
                        matchingLines.put(candidateWay.osm_id, candidateSegments);
                    } else {
                        candidateSegments = matchingLines.get(candidateWay.osm_id);
                    }

                    //and add to the candidate list for this segment
                    mainLineSegment.candidateWaySegments.add(candidateSegments);
                }
            }
        }

        //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
        for(final LineSegment mainSegment : mainWaySegments.segments) {
            for(final WaySegments candidateLine : mainSegment.candidateWaySegments) {
                for(final LineSegment candidateSegment : candidateLine.segments) {
                    SegmentMatch.checkCandidateForMatch(this, mainSegment, candidateSegment, candidateLine.matchObject);
                }
            }
        }

        //consolidate the segment match data for each matching line
        for(final WaySegments line : matchingLines.values()) {
            line.matchObject.summarize();
        }

        //now that each segment has been matched (or not), we need to compile the data


        //run additional checks to ensure the matches are contiguous


        //split ways that only partially overlap the main way
    }
}
