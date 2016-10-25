package NewPathFinding;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMNode;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

/**
 * Created by nick on 10/20/16.
 */
public class PathSegment implements WaySegmentsObserver {
    /**
     * Cache for all existing PathSegments, to reduce duplication when Paths traverse a similar location
     * TODO: possible issues with looping, i.e. PathSegments with different endJunctions?
     */
    private final static HashMap<String, PathSegment> allPathSegments = new HashMap<>(1024);
    private final static double SCORE_FOR_STOP_ON_WAY = 10000.0, SCORE_FOR_ALIGNMENT = 100.0, SCORE_FOR_DETOUR = 10.0, SCORE_FACTOR_FOR_CORRECT_ONEWAY_TRAVEL = 200.0, SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL = -200.0, SCORE_FACTOR_FOR_NON_ONEWAY_TRAVEL = 100.0;

    private final static long debugWayId = 370819942L*0;

    /**
     * Whether this PathSegment is traveling with (0->N) or against(N->0) its line's segments' node direction
     */
    public enum TravelDirection {
        forward, backward
    }
    public enum ProcessingStatus {
        inprocess, pendingAdvance, noFirstTraveledSegment, zeroSegmentMatches, failedSegmentMatch, complete, reachedDestination
    }

    private final Junction originJunction;
    private Junction endJunction = null;

    private OSMWaySegments line;
    private String id;
    public final TravelDirection travelDirection;
    protected double traveledSegmentLength, alignedSegmentLength, detourSegmentLength; //the length of segments this path aligns with
    protected int traveledSegmentCount, alignedSegmentCount, detourSegmentCount;
    protected double alignedLengthFactor, detourLengthFactor, alignedPathScore, detourPathScore, waypointScore;
    private List<Path> containingPaths = new ArrayList<>(PathTree.MAX_PATHS_TO_CONSIDER);
    private final PathTree parentPathTree;
    private final List<OSMLineSegment> containedSegments;
    //private OSMLineSegment firstTraveledSegment = null, lastTraveledSegment = null;
    protected ProcessingStatus processingStatus = ProcessingStatus.inprocess;

    public static String idForParameters(final OSMWaySegments line, final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PS%d:%d->%d", line.way.osm_id, fromNode.osm_id, toNode != null ? toNode.osm_id : 0);
    }
    protected static PathSegment createNewPathSegment(final OSMWaySegments line, final Junction fromJunction, final TravelDirection travelDirection, final PathTree parentPathTree) {
        /*final PathSegment newPathSegment = new PathSegment(line, fromJunction, parentPathTree);
        final String newPSID = idForParameters(line, fromJunction.junctionNode, null);
        PathSegment existingPathSegment = allPathSegments.get(newPathSegment.id);
        if(existingPathSegment == null) {
            newPathSegment.determineScore();
            allPathSegments.put(newPathSegment.id, newPathSegment);
            existingPathSegment = newPathSegment;
        } else {
            newPathSegment.retire(); //properly discard the newPathSegment, since it's not going to be used
        }
        return existingPathSegment;*/
        return new PathSegment(line, fromJunction, travelDirection, parentPathTree); //TODO: determine if caching is feasible or not
    }
    private PathSegment(final OSMWaySegments line, final Junction fromJunction, final TravelDirection travelDirection, final PathTree parentPathTree) {
        this.line = line;
        this.line.addObserver(this); //track all changes to the underlying line, i.e. for splits
        originJunction = fromJunction;
        this.parentPathTree = parentPathTree;
        this.travelDirection = travelDirection;
        containedSegments = new ArrayList<>(line.segments.size());

        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originJunction.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");

        //generate a unique ID for this PathSegment
        id = idForParameters(line, originJunction.junctionNode, null);

        originJunction.junctionPathSegments.add(this);
    }
    public void advance(final List<RouteLineSegment> routeLineSegmentsToConsider, final boolean debug) {
        //get a handle on the LineSegments involved in this step
        final RouteLineSegment targetSegment = routeLineSegmentsToConsider.get(0);
        final OSMLineSegment firstTraveledSegment = findFirstTraveledSegment();
        ProcessingStatus segmentStatus;
        if(firstTraveledSegment == null) { //Shouldn't happen
            System.out.println("ERROR: no firstTraveledSegment found for " + originJunction.junctionNode + " traveling " + travelDirection.toString());
            processingStatus = ProcessingStatus.noFirstTraveledSegment;
            return;
        }

        //check if the candidate segment is a match for the targetSegment
        //TODO: add fallback/scoring instead of requiring full match?
        final RouteLineWaySegments routeLine = (RouteLineWaySegments) targetSegment.getParent();
        final LineMatch lineMatches = routeLine.lineMatches.get(line.way.osm_id);
        //final List<SegmentMatch> rlSegmentMatchesForLine = targetSegment.getMatchingSegmentsForLine(line.way.osm_id, (short)(SegmentMatch.matchTypeDotProduct | SegmentMatch.matchTypeTravelDirection));
        if(lineMatches == null) { //ERROR
            processingStatus = ProcessingStatus.zeroSegmentMatches;
            return;
        }

        /**
         * Check the future vector to see if it has a positive component with the first-traveled segment.
         * if not, mark as pending advance and wait for the RouteLine to pass it (which isn't guaranteed)
         */
        final double futureVector[] = {0.0, 0.0};
        calculateFutureVector(routeLineSegmentsToConsider, futureVector);
        double futureVectorMagnitude = Math.sqrt(futureVector[0] * futureVector[0] + futureVector[1]* futureVector[1]), futureVectorDotProduct = (futureVector[0] * firstTraveledSegment.vectorX + futureVector[1] * firstTraveledSegment.vectorY) / (firstTraveledSegment.vectorMagnitude * futureVectorMagnitude);
        if(debug) {
            System.out.format("Dot product of %.02f of fs [%.01f, %.01f] with fv [%.01f, %.01f]\n", futureVectorDotProduct, firstTraveledSegment.vectorX, firstTraveledSegment.vectorY, futureVector[0], futureVector[1]);
        }
        if(travelDirection == TravelDirection.backward) {
            futureVectorDotProduct *= -1.0;
        }
        if(futureVectorDotProduct < 0.0) {
            processingStatus = ProcessingStatus.pendingAdvance;
            return;
        }

        /**
         * Check if the lastTraveledSegment contains a possible Path Junction point
         * If the candidate segment is part of the middle of the OSM way, then we're OK for now.
         * If the candidate segment contains a node which joins 2 or more other ways, we need to decide how to proceed.
         * i.e. check the path options and decide whether to create a fork here
         */
        final OSMNode lastNode; //the last node reachable on the line, depending on direction of travel
        final OSMNode destinationStopPosition = parentPathTree.destinationStop.getStopPosition();
        final int firstTraveledSegmentIndex = line.segments.indexOf(firstTraveledSegment);
        if (travelDirection == TravelDirection.forward) {
            lastNode = line.way.getLastNode();

            if(line.way.osm_id == debugWayId) {
                for(final SegmentMatch segmentMatch : lineMatches.matchingSegments) {
                    System.out.println("PDB SEGMATCHES: " + segmentMatch);
                }
            }

            final ListIterator<LineSegment> iterator = line.segments.listIterator(firstTraveledSegmentIndex);
            while (iterator.hasNext()) { //starting at the firstTraveledSegment, iterate over the way's LineSegments
                final LineSegment segment = iterator.next();
                if(line.way.osm_id == debugWayId) {
                    System.out.println("PDB check FWD " + this + ":::SEG:::" + segment);
                }

                segmentStatus = checkSegment(lineMatches, (OSMLineSegment) segment, segment.destinationNode, destinationStopPosition, lastNode);
                if(segmentStatus != ProcessingStatus.inprocess) {
                    processingStatus = segmentStatus;
                    break;
                }
            }
        } else if (travelDirection == TravelDirection.backward) {
            lastNode = line.way.getFirstNode();
            final ListIterator<LineSegment> iterator = line.segments.listIterator(firstTraveledSegmentIndex + 1);
            while (iterator.hasPrevious()) { //starting at the firstTraveledSegment, iterate over the way's LineSegments
                final LineSegment segment = iterator.previous();
                if(line.way.osm_id == debugWayId) {
                    System.out.println("PDB check BKW " + this + ":::SEG:::" + segment);
                }

                segmentStatus = checkSegment(lineMatches, (OSMLineSegment) segment, segment.originNode, destinationStopPosition, lastNode);
                if(segmentStatus != ProcessingStatus.inprocess) {
                    processingStatus = segmentStatus;
                    break;
                }
            }
        }
    }
    private ProcessingStatus checkSegment(final LineMatch lineMatches, final OSMLineSegment segment, final OSMNode nodeToCheck, final OSMNode destinationStopPosition, final OSMNode endingWayNode) {
        boolean segmentMatched = false;
        for(final SegmentMatch segmentMatch : lineMatches.matchingSegments) {
            if(segmentMatch.type == SegmentMatch.matchMaskAll && segmentMatch.matchingSegment == segment) {
                segmentMatched = true;
                break;
            }
        }
        if(!segmentMatched) {
            return ProcessingStatus.failedSegmentMatch;
        }

        containedSegments.add(segment);

        //check if we've found the final destination node for this PathSegment's PathTree, or we're at a junction, and if so, bail
        if(nodeToCheck != null) {
            if(nodeToCheck == destinationStopPosition) {
                setEndJunction(parentPathTree.createJunction(nodeToCheck));
                return ProcessingStatus.reachedDestination;
            } else if(nodeToCheck == endingWayNode || nodeToCheck.containingWayCount > 1) {
                setEndJunction(parentPathTree.createJunction(nodeToCheck));
                return ProcessingStatus.complete;
            }
        }
        return ProcessingStatus.inprocess;
    }
    private OSMLineSegment findFirstTraveledSegment() {
        //scan the line's segment list for the segment, returning it if found
        //if this is the first check, scan the line's segment list for the segment originating from the Junction's node
        if (travelDirection == TravelDirection.forward) {
            for(final LineSegment segment : line.segments) {
                if (segment.originNode == originJunction.junctionNode) {
                    return (OSMLineSegment) segment;
                }
            }
        } else {
            for(final LineSegment segment : line.segments) {
                if (segment.destinationNode == originJunction.junctionNode) {
                    return (OSMLineSegment) segment;
                }
            }
        }
        return null;
    }
    private static void calculateFutureVector(final List<RouteLineSegment> routeLineSegments, final double[] futureVector) {
        for(final RouteLineSegment futureSegment : routeLineSegments) {
            futureVector[0] += futureSegment.vectorX;
            futureVector[1] += futureSegment.vectorY;
        }
    }
    public void addContainingPath(final Path path) {
        containingPaths.add(path);
    }
    private void determineScore() {
        waypointScore = alignedPathScore = detourPathScore = alignedLengthFactor = traveledSegmentLength = detourSegmentLength = 0.0;
        traveledSegmentCount = alignedSegmentCount = detourSegmentCount = 0;

       /* //add scores if the origin and/or destination stops are present on this PathSegment
        if(originJunction.junctionNode == parentPathTree.fromNode) {
            waypointScore += SCORE_FOR_STOP_ON_WAY;
        }
        if(endJunction.junctionNode == parentPathTree.toNode) {
            waypointScore += SCORE_FOR_STOP_ON_WAY;
        }

        //determine the match score by iterating over the contained LineSegments on this PathSegment
        final long routeLineId = parentPathTree.parentPathFinder.route.routeLineSegment.way.osm_id;
        final double directionMultiplier;
        if(travelDirection == TravelDirection.forward) { //i.e. we're traveling along the node order of this way
            //determine whether we're going with or against the oneway tag on the current way
            directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.forward, endJunction.junctionNode, parentPathTree.parentPathFinder);
        } else { //i.e. we're traveling against the node order of this way
            directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.backward, endJunction.junctionNode, parentPathTree.parentPathFinder);
        }
        //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
        for (LineSegment containedSegment : containedSegments) {
            processSegmentScore(containedSegment, routeLineId, directionMultiplier);
        }

        if(alignedSegmentLength > 0.0) {
            alignedPathScore /= alignedSegmentLength;
        }
        if(detourSegmentLength > 0.0) {
            detourPathScore /= detourSegmentLength;
        }
        if(traveledSegmentLength > 0.0) {
            alignedLengthFactor = alignedSegmentLength / traveledSegmentLength;
            detourLengthFactor = detourSegmentLength / traveledSegmentLength;
        }

        if(line.way.osm_id == debugWayId) {
            System.out.println("WAY:: " + line.way.osm_id + " matched? " + Boolean.toString(lineMatched) + ", % travelled: " + (Math.round(alignedLengthFactor * 10000.0) / 100.0) + ", score " + waypointScore + "/" + alignedPathScore);
        }

        //System.out.println("PROCESSED Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): " + originJunction.junctionNode.osm_id + "->" + endJunction.junctionNode.osm_id + ", length: " + Math.round(100.0 * alignedSegmentLength / traveledSegmentLength) + "%, score " + getScore());

        //if this segment is the root segment of a Path, default its originating node to the "first" (based on our direction of travel) node on the path
        /*if(parentPathSegment == null && originatingNode == null) {
            originatingNode = firstNode;
        }*/
    }
    public OSMWaySegments getLine() {
        return line;
    }
    public Junction getOriginJunction() {
        return originJunction;
    }
    public Junction getEndJunction() {
        return endJunction;
    }
    public void setEndJunction(final Junction endJunction) {
        this.endJunction = endJunction;
        endJunction.junctionPathSegments.add(this);

        //update the PathSegment cache
        allPathSegments.remove(id);
        id = idForParameters(line, originJunction.junctionNode, endJunction.junctionNode); //update the id as well
        allPathSegments.put(id, this);
    }

    @Override
    public String toString() {
        if(processingStatus == ProcessingStatus.inprocess) {
            return String.format("%s (%d): from %d [status “%s”]", line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originJunction.junctionNode.osm_id, processingStatus.toString());
        } else {
            return String.format("%s (%d): %d to %s [status “%s”]", line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originJunction.junctionNode.osm_id, endJunction != null ? Long.toString(endJunction.junctionNode.osm_id) : "NONE", processingStatus.toString());
        }
    }

    @Override
    public void waySegmentsWasSplit(WaySegments originalWaySegments, WaySegments[] splitWaySegments) throws InvalidArgumentException {

    }

    @Override
    public void waySegmentsWasDeleted(WaySegments waySegments) throws InvalidArgumentException {

    }

    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment newSegment) {

    }
}