package Conflation;

import Importer.InvalidArgumentException;
import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;
import OSM.Region;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
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
    public final HashMap<Long,LineMatch> lineMatchesByOSMWayId = new HashMap<>(1024);

    /**
     * OSM way matches, keyed by RouteLineSegments' ids, then OSM way id
     */
    public final HashMap<Long,Map<Long, LineMatch>> lineMatchesByRouteLineSegmentId;

    protected RouteLineWaySegments(final OSMWay way, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        super(way, wayMatchingOptions);
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
            final List<WeakReference<WaySegmentsObserver>> observersToNotify = new ArrayList<>(observers);
            for (final WeakReference<WaySegmentsObserver> observerReference : observersToNotify) {
                final WaySegmentsObserver observer = observerReference.get();
                if(observer != null) {
                    observer.waySegmentsAddedSegment(this, onSegment, newSegments);
                }
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
        final DebugMatchCounting matchCounting = new DebugMatchCounting();
        for (final LineSegment lineSegment : segments) {
            routeLineSegment = (RouteLineSegment) lineSegment;

            //get the cells which the routeLineSegment overlaps with...
            final List<Cell> segmentCells = new ArrayList<>(2);
            for(final Cell cell : Cell.allCells) {
                if (Region.intersects(routeLineSegment.searchAreaForMatchingOtherSegments, cell.expandedBoundingBox)) {
                    if (!segmentCells.contains(cell)) {
                        segmentCells.add(cell);
                    }
                }
            }

            //and check the ways contained in those cells for overlap with the segment
            for (final Cell candidateCell : segmentCells) {
                for(final OSMWaySegments candidateLine : candidateCell.containedLines) {
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
                    if (!isValidWay || candidateLine.way.hasTag(RouteConflator.GTFS_IGNORE)) {
                        continue;
                    }

                    //check for candidate lines whose bounding box intersects this segment
                    if (Region.intersects(routeLineSegment.searchAreaForMatchingOtherSegments, candidateLine.boundingBoxForSegmentMatching)) {
                        //and run the detailed per-segment checks with the line
                        matchSegmentsOnLine(routeLineSegment, candidateLine, matchCounting);
                    }
                }
            }
        }

        System.out.format("%d Line matches, %d/%d/%d/%d/%d bbox/dotproduct/travel/distance/precise Matches in %dms\n", lineMatchesByOSMWayId.size(), matchCounting.bboxMatches, matchCounting.dotProductMatches, matchCounting.travelDirectionMatches, matchCounting.distanceMatches, matchCounting.preciseMatches, new Date().getTime() - t0.getTime());

        //consolidate the segment match data for each matching line
        /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
          may match multiple segments, and there is a typically good deal of overlap).  We want only
           the unique segment matches.
         */
        for (final LineSegment segment : segments) {
            ((RouteLineSegment) segment).summarize();
        }

        for(final LineMatch lineMatch : lineMatchesByOSMWayId.values()) {
            lineMatch.summarize();
        }

        System.out.println("Matched lines in " + (new Date().getTime() - timeStartLineComparison) + "ms");
    }
    /**
     * Matches routeLineSegment against the osmLineSegments in candidateLine
     * @param routeLineSegment
     * @param candidateLine
     * @param matchCounting
     */
    private void matchSegmentsOnLine(final RouteLineSegment routeLineSegment, final OSMWaySegments candidateLine, final DebugMatchCounting matchCounting) {
        OSMLineSegment osmLineSegment;
        SegmentMatch currentMatch;
        //now check the given OSM way's segments against this segment
        for (final LineSegment candidateSegment : candidateLine.segments) {
            osmLineSegment = (OSMLineSegment) candidateSegment;

            //run a quick hash check to see if the given RouteLineSegment-OSMLineSegment match has already been checked
            /*if(routeLineSegment.checkSegmentMatchPresent(SegmentMatch.idForParameters(routeLineSegment, osmLineSegment))) {
                //System.out.println("DUPE NO GO");
                continue;
            }*/

            currentMatch = SegmentMatch.checkCandidateForMatch(wayMatchingOptions, routeLineSegment, osmLineSegment);

            //if there was a reasonable match with the OSMLineSegment, add it to the various match indexes
            if(currentMatch != null && routeLineSegment.addMatch(currentMatch)) {
                addMatchToDependentIndexes(currentMatch);

                matchCounting.updateCounts(currentMatch.type);
            }
        }
    }

    /**
     * Add the current SegmentMatch to the by-segment, by-OSMWay, etc indexes
     * @param currentMatch
     */
    private LineMatch addMatchToDependentIndexes(final SegmentMatch currentMatch) {
        //update the dependent indexes
        final OSMWaySegments candidateLine = (OSMWaySegments) currentMatch.matchingSegment.getParent();

        //create a LineMatch for the OSMWay, if not already present
        LineMatch lineMatchForOSMWay = lineMatchesByOSMWayId.get(candidateLine.way.osm_id);
        if (lineMatchForOSMWay == null) {
            lineMatchForOSMWay = new LineMatch(this, candidateLine);
            lineMatchesByOSMWayId.put(candidateLine.way.osm_id, lineMatchForOSMWay);
            candidateLine.addObserver(this);
        }
        lineMatchForOSMWay.addMatch(currentMatch); //and add the current match to it

        //also index the LineMatch by the RouteLineSegment's id
        Map<Long,LineMatch> lineMatchesForRouteLineSegment = lineMatchesByRouteLineSegmentId.get(currentMatch.mainSegment.id);
        if(lineMatchesForRouteLineSegment == null) {
            lineMatchesForRouteLineSegment = new HashMap<>(4);
            lineMatchesByRouteLineSegmentId.put(currentMatch.mainSegment.id, lineMatchesForRouteLineSegment);
        }
        if(!lineMatchesForRouteLineSegment.containsKey(candidateLine.way.osm_id)) {
            lineMatchesForRouteLineSegment.put(candidateLine.way.osm_id, lineMatchForOSMWay);
        }
        return lineMatchForOSMWay;
    }

    /**
     * Removes the given match from the dependent indexes
     * @param match
     * @return
     */
    private LineMatch removeMatchFromDependentIndexes(final SegmentMatch match) {
        final OSMWaySegments candidateLine = (OSMWaySegments) match.matchingSegment.getParent();
        final LineMatch routeLineMatch = lineMatchesByOSMWayId.get(candidateLine.way.osm_id);

        //remove the SegmentMatch from the LineMatch
        routeLineMatch.removeMatch(match);

        //and delete the lineMatch from this WaySegments' indexes if it no longer has any SegmentMatches
        if(routeLineMatch.matchingSegments.size() == 0) {
            lineMatchesByOSMWayId.remove(candidateLine.way.osm_id);
            candidateLine.removeObserver(this);

            //remove the LineMatch from the index keyed by the RouteLineSegment's id
            final Map<Long, LineMatch> rlSegmentLineMatches = lineMatchesByRouteLineSegmentId.get(match.mainSegment.id);
            rlSegmentLineMatches.remove(candidateLine.way.osm_id);

            //and remove the entry for the RouteLineSegment if no more LineMatches for it
            if(rlSegmentLineMatches.size() == 0) {
                lineMatchesByRouteLineSegmentId.remove(match.mainSegment.id);
            }
        }

        return routeLineMatch;
    }
    public void flushMatchIndexes() {
        for(final LineSegment segment : segments) {
            final RouteLineSegment routeLineSegment = (RouteLineSegment) segment;
            routeLineSegment.flushMatches();
        }
        for(final LineMatch lineMatch : lineMatchesByOSMWayId.values()) {
            lineMatch.osmLine.removeObserver(this);
        }
        lineMatchesByOSMWayId.clear();
        lineMatchesByRouteLineSegmentId.clear();
    }
    /*private void resyncLineMatches() {
        final List<Long> lineMatchesToRemove = new ArrayList<>(lineMatchesByOSMWayId.size());
        for(final LineMatch lineMatch : lineMatchesByOSMWayId.values()) {
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
            lineMatchesByOSMWayId.remove(id);
        }
    }*/
    public LineMatch getMatchForWay(final long wayOsmId) {
        return lineMatchesByOSMWayId.get(wayOsmId);
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
    public void waySegmentsWasSplit(@NotNull WaySegments originalWaySegments, @NotNull OSMNode[] splitNodes, @NotNull WaySegments[] splitWaySegments) throws InvalidArgumentException {
        if(originalWaySegments instanceof RouteLineWaySegments) { //case when the RouteLine is split
            //TODO unimplemented
        } else if(originalWaySegments instanceof OSMWaySegments) {
            //get a handle on the original line's matching segments
            final LineMatch originalLineMatch = lineMatchesByOSMWayId.get(originalWaySegments.way.osm_id);
            if(originalLineMatch == null) { //if there isn't one, bail.  Will happen when after match index is flushed
                return;
            }
            final List<SegmentMatch> originalLineSegmentMatches = new ArrayList<>(originalLineMatch.matchingSegments);

            //remove all the existing SegmentMatches from the various indexes, keeping a list of RouteLineSegments that need to be rematched
            final Map<Long,RouteLineSegment> affectedRouteLineSegments = new HashMap<>(256);
            for(final SegmentMatch originalMatch : originalLineSegmentMatches) {
                originalLineMatch.removeMatch(originalMatch); //remove the match from the original LineMatch object
                addMatchToDependentIndexes(originalMatch); //and re-run the per line match checks to sync the match with it's new way's OSM id

                //track the RouteLineSegments that need to update their match indexes
                if(!affectedRouteLineSegments.containsKey(originalMatch.mainSegment.id)) {
                    affectedRouteLineSegments.put(originalMatch.mainSegment.id, originalMatch.mainSegment);
                }
            }

            //and using the affected RouteLineSegments above, rematch them to the OSMLineSegments in the newly-split lines
            for(final WaySegments splitWaySegment : splitWaySegments) {
                //and run the match on the segments, adding to the various indexes as needed
                for(final RouteLineSegment affectedRouteLineSegment : affectedRouteLineSegments.values()) {
                    affectedRouteLineSegment.resyncMatchesForWay(splitWaySegment.way);
                    affectedRouteLineSegment.summarize();
                }

                //and summarize the LineMatch for the split way, if any
                final LineMatch curMatch = lineMatchesByOSMWayId.get(splitWaySegment.way.osm_id);
                if(curMatch != null) {
                    curMatch.summarize();
                }
            }

        }
    }

    @Override
    public void waySegmentsWasDeleted(@NotNull WaySegments waySegments) throws InvalidArgumentException {

    }

    @Override
    public void waySegmentsAddedSegment(@NotNull WaySegments waySegments, @NotNull LineSegment oldSegment, @NotNull LineSegment[] newSegments) {
        if(waySegments instanceof RouteLineWaySegments) { //case when the RouteLine has been updated
            //argument casting
            final RouteLineSegment oldRouteLineSegment = (RouteLineSegment) oldSegment;
            final RouteLineSegment[] newRouteLineSegments = new RouteLineSegment[newSegments.length];
            int i = 0;
            for(final LineSegment newSegment : newSegments) {
                newRouteLineSegments[i++] = (RouteLineSegment) newSegment;
            }

            //update any matches related to the segments
            SegmentMatch currentMatch;
            final Map<Long, List<SegmentMatch>> matchListForOriginalSegment = oldRouteLineSegment.getMatchingSegments(SegmentMatch.matchTypeNone);
            final Map<Long,LineMatch> affectedLineMatches = new HashMap<>(matchListForOriginalSegment.size());
            for(final List<SegmentMatch> matchListForLine : matchListForOriginalSegment.values()) {
                for(final SegmentMatch existingMatch : matchListForLine) {
                    //remove the old match from the dependent indexes
                    LineMatch affectedLineMatch = removeMatchFromDependentIndexes(existingMatch);
                    affectedLineMatches.put(affectedLineMatch.osmLine.way.osm_id, affectedLineMatch);

                    //check the existingMatch's matched segment against the newly-added RouteLineSegments
                    for(final RouteLineSegment newSegment : newRouteLineSegments) {
                        currentMatch = SegmentMatch.checkCandidateForMatch(wayMatchingOptions, newSegment, existingMatch.matchingSegment);
                        if (currentMatch != null && newSegment.addMatch(currentMatch)) {
                            affectedLineMatch = addMatchToDependentIndexes(currentMatch);
                            affectedLineMatches.put(affectedLineMatch.osmLine.way.osm_id, affectedLineMatch);
                        }
                    }
                }
            }

            //and summarize their matches again, to ensure all dependent SegmentMatch data is updated
            for(final RouteLineSegment newSegment : newRouteLineSegments) {
                newSegment.summarize();
            }
            affectedLineMatches.values().forEach(LineMatch::summarize);
        } else if(waySegments instanceof OSMWaySegments) { //a matched OSMWay has been updated
            //argument casting
            OSMLineSegment oldOSMLineSegment = (OSMLineSegment) oldSegment;
            final OSMLineSegment[] newOSMSegments = new OSMLineSegment[newSegments.length];
            int i = 0;
            for(final LineSegment newSegment : newSegments) {
                newOSMSegments[i++] = (OSMLineSegment) newSegment;
            }

            //Update the LineMatch indexes, and SegmentMatches for all affected RouteLineSegments
            final LineMatch lineMatch = lineMatchesByOSMWayId.get(oldOSMLineSegment.getParent().way.osm_id);
            assert lineMatch != null;
            final Map<Long, Boolean> affectedRouteLineSegments = new HashMap<>(32);
            final List<SegmentMatch> matchesToRemove = new ArrayList<>(lineMatch.getRouteLineMatchesForSegment(oldOSMLineSegment, SegmentMatch.matchTypeNone));
            for(final SegmentMatch removeMatch : matchesToRemove) {
                //remove the match from the RouteLineSegment and the LineMatch indexes
                removeMatch.mainSegment.removeMatch(removeMatch);
                removeMatchFromDependentIndexes(removeMatch);

                //track the RouteLineSegments that need to be matched with the new segments
                if(!affectedRouteLineSegments.containsKey(removeMatch.mainSegment.id)) {
                    affectedRouteLineSegments.put(removeMatch.mainSegment.id, true);

                    //now, match the new OSM segments with the RouteLineSegment
                    SegmentMatch newSegmentMatch;
                    for (final OSMLineSegment newSegment : newOSMSegments) {
                        newSegmentMatch = SegmentMatch.checkCandidateForMatch(wayMatchingOptions, removeMatch.mainSegment, newSegment);
                        if (newSegmentMatch != null && removeMatch.mainSegment.addMatch(newSegmentMatch)) {
                            addMatchToDependentIndexes(newSegmentMatch);
                        }
                    }
                    //and summarize
                    removeMatch.mainSegment.summarize();
                }
            }
            lineMatch.summarize();
        }
    }
}
