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
    public final HashMap<Long,LineMatch> lineMatches = new HashMap<>(512);
    public final HashMap<Long,List<LineMatch>> lineMatchesBySegmentId;

    public RouteLineWaySegments(final OSMWay way, final double maxSegmentLength) {
        super(way, maxSegmentLength);
        lineMatchesBySegmentId = new HashMap<>(segments.size());
    }
    protected RouteLineWaySegments(final RouteLineWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);

        if(Math.random() < 2.0) {
            throw new RuntimeException("Copy constructor not implemented");
        }
        //TODO: need to copy over relevant matches
        lineMatchesBySegmentId = new HashMap<>(segments.size());
    }
    /**
     * Inserts a Point onto the given segment, splitting it into two segments
     * NOTE: does not check if point lies on onSegment!
     * @param point The point to add
     * @param onSegment The segment to add the node to
     * @return If an existing node is within the tolerance distance, that node, otherwise the input node
     */
    public Point insertPoint(final Point point, final RouteLineSegment onSegment, final double nodeTolerance) {

        //create a new segment starting from the node, and ending at onSegment's destination Point
        final LineSegment insertedSegment = createLineSegment(point, onSegment.destinationPoint, null, onSegment.destinationNode, onSegment.segmentIndex + 1, onSegment.nodeIndex + 1);

        //and replace onSegment with a version of itself that's truncated to the new node's position
        final LineSegment newOnSegment = copyLineSegment(onSegment, point, null);
        segments.set(segments.indexOf(onSegment), newOnSegment);

        //increment the node index of all following segments
        for(final LineSegment segment : segments) {
            if(segment.segmentIndex > onSegment.segmentIndex) {
                segment.segmentIndex++;
                segment.nodeIndex++;
            }
        }

        //add the segment and node to this line and its way
        segments.add(insertedSegment.segmentIndex, insertedSegment);

        //update any matches related to the segments (TODO maybe use observers instead?)
        insertedSegment.updateMatches();
        newOnSegment.updateMatches();

        //and notify any observers
        if(observers != null) {
            final List<WaySegmentsObserver> observersToNotify = new ArrayList<>(observers);
            for (final WaySegmentsObserver observer : observersToNotify) {
                observer.waySegmentsAddedSegment(this, insertedSegment);
            }
        }

        return point;
    }

    public void findMatchingLineSegments(final RouteConflator routeConflator) {
        final long timeStartLineComparison = new Date().getTime();
        final double latitudeDelta = RouteConflator.Cell.getLatitudeBuffer(), longitudeDelta = RouteConflator.Cell.getLongitudeBuffer();

        //loop through all the segments in this RouteLine, compiling a list of OSM ways to check for detailed matches
        Date t0 = new Date();
        RouteLineSegment routeLineSegment;
        //final Collection<OSMWaySegments> routeCandidateLines = routeConflator.candidateLines.values();
        final Map<Long, RouteConflator.Cell> allCells = RouteConflator.Cell.allCells;
        int totalIterations = 0;
        for (final LineSegment lineSegment : segments) {
            routeLineSegment = (RouteLineSegment) lineSegment;
            //and loop through the OSM ways that intersect the current RouteLineSegment's bounding box
            final Region routeSegmentBoundingBox = lineSegment.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);

            //get the cells which the routeLineSegment overlaps with
            final List<RouteConflator.Cell> segmentCells = new ArrayList<>(2);
            for(final RouteConflator.Cell cell : RouteConflator.Cell.allCells.values()) {
                if (Region.intersects(routeSegmentBoundingBox, cell.expandedBoundingBox)) {
                    if (!segmentCells.contains(cell)) {
                        segmentCells.add(cell);
                    }
                }
            }

            //and check the ways contained in those cells for overlap with the segment
            for (final RouteConflator.Cell candidateCell : segmentCells) {
                for(final OSMWaySegments candidateLine : candidateCell.containedWays) {
                //for(final OSMWaySegments candidateLine : routeConflator.candidateLines.values()) {
                    totalIterations++;

                    //check the tags of the way, to ensure only valid ways are considered for the current route's type
                    boolean isValidWay = false;
                    for (final Map.Entry<String, List<String>> requiredTag : routeConflator.allowedRouteTags.entrySet()) {
                        if (requiredTag.getValue().contains(candidateLine.way.getTag(requiredTag.getKey()))) {
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
                        if (!lineMatches.containsKey(candidateLine.way.osm_id)) {
                            lineMatches.put(candidateLine.way.osm_id, new LineMatch(this, candidateLine));
                        }
                        routeLineSegment.addCandidateLine(candidateLine);
                    }
                }
            }
        }
        System.out.println(lineMatches.size() + "LineMatches in " + (new Date().getTime() - t0.getTime()) + "ms, " + totalIterations + " iterations");

        //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
        OSMLineSegment osmLineSegment;
        SegmentMatch currentMatch;
        System.out.println(routeConflator.candidateLines.size() + " route candidates, " + lineMatches.size() + " bb matches, " + segments.size() + "segments to match");
        int preciseMatches = 0, dotProductMatches = 0, distanceMatches = 0, travelDirectionMatches = 0, bboxMatches = 0;
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

                        if(currentMatch.type == SegmentMatch.matchMaskAll) {
                            preciseMatches++;
                        }
                        if((currentMatch.type & SegmentMatch.matchTypeDotProduct) != 0) {
                            dotProductMatches++;
                        }
                        if((currentMatch.type & SegmentMatch.matchTypeDistance) != 0) {
                            distanceMatches++;
                        }
                        if((currentMatch.type & SegmentMatch.matchTypeTravelDirection) != 0) {
                            travelDirectionMatches++;
                        }
                        if((currentMatch.type & SegmentMatch.matchTypeBoundingBox) != 0) {
                            bboxMatches++;
                        }


                        List<LineMatch> lineMatchesForRouteLineSegment = lineMatchesBySegmentId.get(routeLineSegment.id);
                        if(lineMatchesForRouteLineSegment == null) {
                            lineMatchesForRouteLineSegment = new ArrayList<>(4);
                            lineMatchesBySegmentId.put(routeLineSegment.id, lineMatchesForRouteLineSegment);
                        }
                        if(!lineMatchesForRouteLineSegment.contains(lineMatch)) {
                            lineMatchesForRouteLineSegment.add(lineMatch);
                        }

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
        System.out.format("%d/%d/%d/%d/%d bbox/dotproduct/travel/distance/precise Matches in %dms\n", bboxMatches, dotProductMatches, travelDirectionMatches, distanceMatches, preciseMatches, new Date().getTime() - t0.getTime());

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
