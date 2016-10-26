package Conflation;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;
import OSM.Region;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.*;

/**
 * Class that represents the GTFS route line, including any matching LineSegments associated with it
 * Created by nick on 9/30/16.
 */
public class RouteLineWaySegments extends WaySegments implements WaySegmentsObserver {
    private static class DebugMatchCounting {
        public int preciseMatches = 0, dotProductMatches = 0, distanceMatches = 0, travelDirectionMatches = 0, bboxMatches = 0;

        public void updateCounts(final short matchMask) {
            if(matchMask == SegmentMatch.matchMaskAll) {
                preciseMatches++;
            }
            if((matchMask & SegmentMatch.matchTypeDotProduct) != 0) {
                dotProductMatches++;
            }
            if((matchMask & SegmentMatch.matchTypeDistance) != 0) {
                distanceMatches++;
            }
            if((matchMask & SegmentMatch.matchTypeTravelDirection) != 0) {
                travelDirectionMatches++;
            }
            if((matchMask & SegmentMatch.matchTypeBoundingBox) != 0) {
                bboxMatches++;
            }
        }
    }

    /**
     * OSM way matches, keyed by their osm_id
     */
    public final HashMap<Long,LineMatch> lineMatchesByOSMSegmentId = new HashMap<>(512);

    /**
     * OSM way matches, keyed by RouteLineSegments' ids
     */
    public final HashMap<Long,List<LineMatch>> lineMatchesByRouteLineSegmentId;

    public RouteLineWaySegments(final OSMWay way, final double maxSegmentLength) {
        super(way, maxSegmentLength);
        lineMatchesByRouteLineSegmentId = new HashMap<>(segments.size());

        this.addObserver(this); //watch for changes to the contained segments
    }
    protected RouteLineWaySegments(final RouteLineWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);

        if(Math.random() < 2.0) {
            throw new RuntimeException("Copy constructor not implemented");
        }
        //TODO: need to copy over relevant matches
        lineMatchesByRouteLineSegmentId = new HashMap<>(segments.size());
        this.addObserver(this); //watch for changes to the contained segments
    }
    /**
     * Inserts a Point onto the given segment, splitting it into two segments
     * NOTE: does not check if point lies on onSegment!
     * @param point The point to add
     * @param onSegment The segment to add the node to
     * @return If an existing node is within the tolerance distance, that node, otherwise the input node
     */
    public Point insertPoint(final Point point,  RouteLineSegment onSegment, final double nodeTolerance) {

        //create a new segment starting from the node, and ending at onSegment's destination Point
        final RouteLineSegment insertedSegment = (RouteLineSegment) createLineSegment(point, onSegment.destinationPoint, null, onSegment.destinationNode, onSegment.segmentIndex + 1, onSegment.nodeIndex + 1);

        //and replace onSegment with a version of itself that's truncated to the new node's position
        final RouteLineSegment newOnSegment = (RouteLineSegment) copyLineSegment(onSegment, point, null);
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

        //and notify any observers
        if(observers != null) {
            final LineSegment[] newSegments = {newOnSegment, insertedSegment};
            final List<WaySegmentsObserver> observersToNotify = new ArrayList<>(observers);
            for (final WaySegmentsObserver observer : observersToNotify) {
                observer.waySegmentsAddedSegment(this, onSegment, newSegments);
            }
        }

        return point;
    }

    public void findMatchingLineSegments(final RouteConflator routeConflator) {
        final long timeStartLineComparison = new Date().getTime();

        //loop through all the segments in this RouteLine, compiling a list of OSM ways to check for detailed matches
        Date t0 = new Date();
        RouteLineSegment routeLineSegment;
        int totalIterations = 0;
        for (final LineSegment lineSegment : segments) {
            routeLineSegment = (RouteLineSegment) lineSegment;

            //get the cells which the routeLineSegment overlaps with...
            final List<RouteConflator.Cell> segmentCells = new ArrayList<>(2);
            for(final RouteConflator.Cell cell : RouteConflator.Cell.allCells) {
                if (Region.intersects(routeLineSegment.searchAreaForMatchingOtherSegments, cell.expandedBoundingBox)) {
                    if (!segmentCells.contains(cell)) {
                        segmentCells.add(cell);
                    }
                }
            }

            //and check the ways contained in those cells for overlap with the segment
            for (final RouteConflator.Cell candidateCell : segmentCells) {
                for(final OSMWaySegments candidateLine : candidateCell.containedWays) {
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
                    if (Region.intersects(routeLineSegment.searchAreaForMatchingOtherSegments, candidateLine.boundingBoxForSegmentMatching)) {
                        if (!lineMatchesByOSMSegmentId.containsKey(candidateLine.way.osm_id)) {
                            lineMatchesByOSMSegmentId.put(candidateLine.way.osm_id, new LineMatch(this, candidateLine));
                        }
                        routeLineSegment.addCandidateLine(candidateLine);
                    }
                }
            }
        }
        System.out.println(lineMatchesByOSMSegmentId.size() + "LineMatches in " + (new Date().getTime() - t0.getTime()) + "ms, " + totalIterations + " iterations");

        //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
        OSMLineSegment osmLineSegment;
        SegmentMatch currentMatch;
        System.out.println(routeConflator.candidateLines.size() + " route candidates, " + lineMatchesByOSMSegmentId.size() + " bb matches, " + segments.size() + "segments to match");
        final DebugMatchCounting matchCounting = new DebugMatchCounting();
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

                        //and the respective match indexes
                        addMatchToDependentIndexes(currentMatch);

                        matchCounting.updateCounts(currentMatch.type);
                    }
                }
            }
        }
        System.out.format("%d/%d/%d/%d/%d bbox/dotproduct/travel/distance/precise Matches in %dms\n", matchCounting.bboxMatches, matchCounting.dotProductMatches, matchCounting.travelDirectionMatches, matchCounting.distanceMatches, matchCounting.preciseMatches, new Date().getTime() - t0.getTime());

        //consolidate the segment match data for each matching line
        /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
          may match multiple segments, and there is a typically good deal of overlap).  We want only
           the unique segment matches.
         */
        for (final LineSegment segment : segments) {
            ((RouteLineSegment) segment).summarize();
        }

        for(final LineMatch lineMatch : lineMatchesByOSMSegmentId.values()) {
            lineMatch.summarize();
        }

        System.out.println("Matched lines in " + (new Date().getTime() - timeStartLineComparison) + "ms");
    }

    /**
     * Add the current SegmentMatch to the by-segment, by-OSMWay, etc indexes
     * @param currentMatch
     */
    private void addMatchToDependentIndexes(final SegmentMatch currentMatch) {
        //update the dependent indexes
        final LineMatch lineMatch = lineMatchesByOSMSegmentId.get(currentMatch.matchingSegment.getParent().way.osm_id);
        lineMatch.addMatch(currentMatch);

        List<LineMatch> lineMatchesForRouteLineSegment = lineMatchesByRouteLineSegmentId.get(currentMatch.mainSegment.id);
        if(lineMatchesForRouteLineSegment == null) {
            lineMatchesForRouteLineSegment = new ArrayList<>(4);
            lineMatchesByRouteLineSegmentId.put(currentMatch.mainSegment.id, lineMatchesForRouteLineSegment);
        }
        if(!lineMatchesForRouteLineSegment.contains(lineMatch)) {
            lineMatchesForRouteLineSegment.add(lineMatch);
        }

        //also track the individual segment matches, keyed by the OSM segment's id
        /*List<SegmentMatch> existingMatches = matchingOSMSegments.get(currentMatch.matchingSegment.id);
        if (existingMatches == null) {
            existingMatches = new ArrayList<>(4);
            matchingOSMSegments.put(currentMatch.matchingSegment.id, existingMatches);
        }
        existingMatches.add(currentMatch);*/
    }

    /**
     * Removes the given match from the dependent indexes
     * @param match
     * @return
     */
    private LineMatch removeMatchFromDependentIndexes(final SegmentMatch match) {
        final LineMatch lineMatch = lineMatchesByOSMSegmentId.get(match.matchingSegment.getParent().way.osm_id);
        if(match != null) {
            lineMatchesByRouteLineSegmentId.remove(match.mainSegment.id);
            lineMatch.removeMatch(match);
        }
        return lineMatch;
    }
    /*private void resyncLineMatches() {
        final List<Long> lineMatchesToRemove = new ArrayList<>(lineMatchesByOSMSegmentId.size());
        for(final LineMatch lineMatch : lineMatchesByOSMSegmentId.values()) {
            lineMatch.resyncMatchesForSegments(segments);

            //resummarize the match
            if(lineMatch.isSummarized()) {
                lineMatch.summarize();
                if(lineMatch.matchingSegments.size() == 0) { //and remove if no segment matches present
                    lineMatchesToRemove.add(lineMatch.routeLineSegment.way.osm_id);
                }
            }
        }
        for(final Long id : lineMatchesToRemove) {
            lineMatchesByOSMSegmentId.remove(id);
        }
    }*/
    public SegmentMatch getBestMatchForLineSegment(final OSMLineSegment segment) {
        //final List<SegmentMatch> matches = matchingOSMSegments.get(segment.id);
        return null; //TODO 222
    }
    public LineMatch getMatchForLine(final WaySegments otherLine) {
        return lineMatchesByOSMSegmentId.get(otherLine.way.osm_id);
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

    @Override
    public void waySegmentsWasSplit(WaySegments originalWaySegments, WaySegments[] splitWaySegments) throws InvalidArgumentException {

    }

    @Override
    public void waySegmentsWasDeleted(WaySegments waySegments) throws InvalidArgumentException {

    }

    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment oldSegment, LineSegment[] newSegments) {
        if(waySegments instanceof RouteLineWaySegments) { //case when the RouteLine is split
            //argument casting
            final RouteLineSegment oldRouteLineSegment = (RouteLineSegment) oldSegment;
            final RouteLineSegment[] splitSegments = new RouteLineSegment[newSegments.length];
            int i = 0;
            for(final LineSegment splitSegment : newSegments) {
                splitSegments[i++] = (RouteLineSegment) splitSegment;
            }

            //update any matches related to the segments
            SegmentMatch currentMatch;
            final Map<Long, List<SegmentMatch>> matchListForOriginalSegment = oldRouteLineSegment.getMatchingSegments(SegmentMatch.matchTypeNone);
            final Map<Long,LineMatch> affectedLineMatches = new HashMap<>(matchListForOriginalSegment.size());
            for(final List<SegmentMatch> matchListForLine : matchListForOriginalSegment.values()) {
                for(final SegmentMatch existingMatch : matchListForLine) {
                    //remove the old match from the dependent indexes
                    final LineMatch affectedLineMatch = removeMatchFromDependentIndexes(existingMatch);
                    if(!affectedLineMatches.containsKey(affectedLineMatch.osmLine.way.osm_id)) {
                        affectedLineMatches.put(affectedLineMatch.osmLine.way.osm_id, affectedLineMatch);
                    }

                    //check the existingMatch's matched segment against the newly-split RouteLineSegments
                    for(final RouteLineSegment splitSegment : splitSegments) {
                        currentMatch = SegmentMatch.checkCandidateForMatch(RouteConflator.wayMatchingOptions, splitSegment, existingMatch.matchingSegment);
                        if (currentMatch != null) {
                            splitSegment.addMatch(currentMatch);
                        }
                    }
                }
            }

            //and summarize their matches again, to ensure all dependent SegmentMatch data is updated
            for(final RouteLineSegment splitSegment : splitSegments) {
                splitSegment.summarize();
            }
            affectedLineMatches.values().forEach(LineMatch::summarize);
        } else if(waySegments instanceof OSMWaySegments) { //a matched OSMWay has been updated
            //TODO: finish implementation
            /*final OSMWaySegments osmWaySegments = (OSMWaySegments) waySegments;
            final OSMLineSegment oldOSMSegment = (OSMLineSegment) oldSegment;

            //update the lineMatch object
            final LineMatch existingLineMatch = lineMatchesByOSMSegmentId.get(osmWaySegments.way.osm_id);
            final List<SegmentMatch> existingSegmentMatches = existingLineMatch.getRouteLineMatchesForSegment(oldOSMSegment, SegmentMatch.matchTypeNone);

            for(final SegmentMatch currentMatch : existingSegmentMatches) {
                    //remove the old match from the dependent indexes
                    final LineMatch affectedLineMatch = removeMatchFromDependentIndexes(existingMatch);
                    if(!affectedLineMatches.containsKey(affectedLineMatch.osmLine.way.osm_id)) {
                        affectedLineMatches.put(affectedLineMatch.osmLine.way.osm_id, affectedLineMatch);
                    }

                    //check the existingMatch's matched segment against the newly-split RouteLineSegments
                    for(final RouteLineSegment splitSegment : splitSegments) {
                        currentMatch = SegmentMatch.checkCandidateForMatch(RouteConflator.wayMatchingOptions, splitSegment, existingMatch.matchingSegment);
                        if (currentMatch != null) {
                            splitSegment.addMatch(currentMatch);
                        }
                    }

            }


            //update all matches related to the segment
            for(final LineSegment newSegment : newSegments) {
                final OSMLineSegment osmLineSegment = (OSMLineSegment) newSegment;
            }*/
        }
    }
}
