package Conflation;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;
import OSM.Region;

import java.util.*;

/**
 * Class that represents the GTFS route line, including any matching LineSegments associated with it
 * Created by nick on 9/30/16.
 */
public class RouteLineWaySegments extends WaySegments {
    public final HashMap<Long,LineMatch> lineMatches = new HashMap<>(8);
    public final Map<Long, List<SegmentMatch>> matchingOSMSegments;
    public final Map<Long, SegmentMatch> bestMatchingOSMSegments;

    public RouteLineWaySegments(final OSMWay way, final double maxSegmentLength) {
        super(way, maxSegmentLength);
        matchingOSMSegments = new HashMap<>(segments.size());
        bestMatchingOSMSegments = new HashMap<>(segments.size());
    }
    protected RouteLineWaySegments(final RouteLineWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);

        if(Math.random() < 2.0) {
            throw new RuntimeException("Copy constructor not implemented");
        }
        //TODO: need to copy over relevant matches
        matchingOSMSegments = new HashMap<>(segments.size());
        bestMatchingOSMSegments = new HashMap<>(segments.size());
    }

    public void findMatchingLineSegments(final RouteConflator routeConflator) {
        final long timeStartLineComparison = new Date().getTime();
        final double latitudeDelta = -RouteConflator.wayMatchingOptions.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * segments.get(0).originPoint.latitude / 180.0);

        //loop through all the segments in this RouteLine, compiling a list of OSM ways to check for detailed matches
        Date t0 = new Date();
        RouteLineSegment routeLineSegment;
        final Collection<OSMWaySegments> routeCandidateLines = routeConflator.candidateLines.values();
        for (final LineSegment lineSegment : segments) {
            routeLineSegment = (RouteLineSegment) lineSegment;
            //and loop through the OSM ways that intersect the current RouteLineSegment's bounding box
            final Region routeSegmentBoundingBox = lineSegment.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            for (final OSMWaySegments candidateLine : routeCandidateLines ) {

                //check the tags of the way, to ensure only valid ways are considered for the current route's type
                boolean isValidWay = false;
                for(final Map.Entry<String, List<String>> requiredTag : routeConflator.allowedRouteTags.entrySet()) {
                    if(requiredTag.getValue().contains(candidateLine.way.getTag(requiredTag.getKey()))) {
                        isValidWay = true;
                        break;
                    }
                }

                //don't match against any lines that have been marked as "ignore", such as other gtfs shape lines
                if (!isValidWay || candidateLine.way.hasTag("gtfs:ignore")) {
                    continue;
                }

                //check for candidate lines whose bounding box intersects this segment, and add to the candidate list for this segment
                if (Region.intersects(routeSegmentBoundingBox, candidateLine.way.getBoundingBox().regionInset(latitudeDelta, longitudeDelta))) {
                    if(!lineMatches.containsKey(candidateLine.way.osm_id)) {
                        lineMatches.put(candidateLine.way.osm_id, new LineMatch(this, candidateLine));
                    }
                    routeLineSegment.addCandidateLine(candidateLine);
                }
            }
        }
        System.out.println(lineMatches.size() + "LineMatches in " + (new Date().getTime() - t0.getTime()) + "ms");

        //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
        OSMLineSegment osmLineSegment;
        SegmentMatch currentMatch;
        final Collection<LineMatch> matchedLines = lineMatches.values();
        System.out.println(routeCandidateLines.size() + " route candidates, " + lineMatches.size() + " bb matches, " + segments.size() + "segments to match");
        for (final LineSegment segment : segments) { //iterate over the RouteLine's segments
            routeLineSegment = (RouteLineSegment) segment;
            for (final OSMWaySegments candidateLine : ((RouteLineSegment) segment).getCandidateLines()) { //and the OSM ways matched on the previous step
                //now check the given OSM way's segments against this segment
                for (final LineSegment candidateSegment : candidateLine.segments) {
                    osmLineSegment = (OSMLineSegment) candidateSegment;
                    currentMatch = SegmentMatch.checkCandidateForMatch(RouteConflator.wayMatchingOptions, routeLineSegment, osmLineSegment);

                    //if there was a reasonable match with the OSMLineSegment, add it to the various match indexes
                    if(currentMatch != null) {
                        //track the individual RouteLine->OSM segment matches
                        routeLineSegment.addMatch(currentMatch);

                        final LineMatch lineMatch = lineMatches.get(osmLineSegment.getParent().way.osm_id);
                        lineMatch.addMatch(currentMatch);

                        //also track the individual segment matches, keyed by the OSM segment's id
                        /*List<SegmentMatch> existingMatches = matchingOSMSegments.get(match.matchingSegment.id);
                        if (existingMatches == null) {
                            existingMatches = new ArrayList<>(4);
                            matchingOSMSegments.put(match.matchingSegment.id, existingMatches);
                        }
                        existingMatches.add(match);*/
                    }
                }
            }
        }
        System.out.println("Segment matches in " + (new Date().getTime() - t0.getTime()) + "ms");

        //consolidate the segment match data for each matching line
        /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
          may match multiple segments, and there is a typically good deal of overlap).  We want only
           the unique segment matches.
         */
        for (final LineSegment segment : segments) {
            ((RouteLineSegment) segment).summarize();
        }

        for(final LineMatch lineMatch : lineMatches.values()) {
            lineMatch.summarize();
        }

        //and generate an index of matches for easier lookup in later steps


        System.out.println("Matched lines in " + (new Date().getTime() - timeStartLineComparison) + "ms");
    }

    /*private void resyncLineMatches() {
        final List<Long> lineMatchesToRemove = new ArrayList<>(lineMatches.size());
        for(final LineMatch lineMatch : lineMatches.values()) {
            lineMatch.resyncMatchesForSegments(segments);

            //resummarize the match
            if(lineMatch.isSummarized()) {
                lineMatch.summarize();
                if(lineMatch.matchingSegments.size() == 0) { //and remove if no segment matches present
                    lineMatchesToRemove.add(lineMatch.routeLine.way.osm_id);
                }
            }
        }
        for(final Long id : lineMatchesToRemove) {
            lineMatches.remove(id);
        }
    }*/
    public SegmentMatch getBestMatchForLineSegment(final OSMLineSegment segment) {
        //final List<SegmentMatch> matches = matchingOSMSegments.get(segment.id);
        return null; //TODO 222
    }
    public LineMatch getMatchForLine(final WaySegments otherLine) {
        return lineMatches.get(otherLine.way.osm_id);
    }

    @Override
    protected LineSegment createLineSegment(final Point miniOrigin, final Point miniDestination, final OSMNode miniOriginNode, final OSMNode miniDestinationNode, int segmentIndex, int nodeIndex) {
        return new RouteLineSegment(this, miniOrigin, miniDestination, miniOriginNode, miniDestinationNode, segmentIndex, nodeIndex);
    }
    @Override
    protected LineSegment copyLineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        if(!(segmentToCopy instanceof RouteLineSegment)) {
            throw new RuntimeException("Wrong class provided when copying RouteLineSegment");
        }
        return new RouteLineSegment((RouteLineSegment) segmentToCopy, destination, destinationNode);
    }
}
