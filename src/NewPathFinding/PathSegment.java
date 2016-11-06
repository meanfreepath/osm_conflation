package NewPathFinding;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.Point;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Created by nick on 10/20/16.
 */
public class PathSegment implements WaySegmentsObserver {
    /**
     * Cache for all existing PathSegments, to reduce duplication when Paths traverse a similar location
     * TODO: possible issues with looping, i.e. PathSegments with different endJunctions?
     */
    private final static double SCORE_FOR_STOP_ON_WAY = 10000.0, SCORE_FOR_ALIGNMENT = 100.0, SCORE_FOR_DETOUR = 10.0, SCORE_FACTOR_FOR_CORRECT_ONEWAY_TRAVEL = 200.0, SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL = -200.0, SCORE_FACTOR_FOR_NON_ONEWAY_TRAVEL = 100.0;

    private final static long debugWayId = 243738308L;
    public final static double maxFutureVectorDeviation = 0.25;

    /**
     * Whether this PathSegment is traveling with (0->N) or against(N->0) its line's segments' node direction
     */
    public enum TravelDirection {
        forward, backward
    }
    public enum ProcessingStatus {
        inprocess, pendingActivation, pendingAdvance, noFirstTraveledSegment, zeroSegmentMatches, failedSegmentMatch, complete, reachedDestination
    }

    protected final Junction originJunction;
    private Junction endJunction = null;

    private OSMWaySegments line;

    public String getId() {
        return id;
    }

    private String id;
    public final TravelDirection travelDirection;
    protected double traveledSegmentLength, alignedSegmentLength, detourSegmentLength; //the length of segments this path aligns with
    protected int traveledSegmentCount, alignedSegmentCount, detourSegmentCount;
    protected double alignedLengthFactor, detourLengthFactor, alignedPathScore, detourPathScore, waypointScore;
    private List<WeakReference<Path>> containingPaths = new ArrayList<>(PathTree.MAX_PATHS_TO_CONSIDER);

    private ProcessingStatus processingStatus = ProcessingStatus.inprocess;

    public static String idForParameters(final OSMWaySegments line, final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PS%d:%d->%d", line.way.osm_id, fromNode.osm_id, toNode != null ? toNode.osm_id : 0);
    }
    protected static PathSegment createNewPathSegment(final OSMWaySegments line, final Junction fromJunction, final TravelDirection travelDirection) {
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
        return new PathSegment(line, fromJunction, travelDirection); //TODO: determine if caching is feasible or not
    }
    private PathSegment(final OSMWaySegments line, final Junction fromJunction, final TravelDirection travelDirection) {
        originJunction = fromJunction;
        this.travelDirection = travelDirection;
        setLine(line);

        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originJunction.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");

        //generate a unique ID for this PathSegment
        id = idForParameters(line, originJunction.junctionNode, null);
    }
    public boolean advance(final List<RouteLineSegment> routeLineSegmentsToConsider, final PathTree parentPathTree, final boolean debug) {
        //get a handle on the LineSegments involved in this step
        final RouteLineSegment targetSegment = routeLineSegmentsToConsider.get(0);

        /*check that this PathSegment's origin Junction is within the RouteLineSegment's search area.  If not,
          we've just got ahead of ourselves - bail for now
         */
        if(!targetSegment.searchAreaForMatchingOtherSegments.containsPoint(originJunction.junctionNode.getCentroid())) {
            processingStatus = ProcessingStatus.pendingActivation;
            if(line.way.osm_id == debugWayId) {
                System.out.format("\t\tSTILL WAITING FOR ACTIVATION: %.01f: %s\n",Point.distance(originJunction.junctionNode.getCentroid(), targetSegment.midPoint), targetSegment.searchAreaForMatchingOtherSegments);
            }
            return false;
        }

        final OSMLineSegment firstTraveledSegment = findFirstTraveledSegment();
        ProcessingStatus segmentStatus;
        if(firstTraveledSegment == null) { //Shouldn't happen
            System.out.println("ERROR: no firstTraveledSegment found for " + originJunction.junctionNode + " traveling " + travelDirection.toString());
            return false;
        }

        //check if the candidate segment is a match for the targetSegment
        final RouteLineWaySegments routeLine = (RouteLineWaySegments) targetSegment.getParent();
        final LineMatch lineMatches = routeLine.lineMatchesByOSMWayId.get(line.way.osm_id);
        if(lineMatches == null) { //ERROR
            processingStatus = ProcessingStatus.zeroSegmentMatches;
            return false;
        }

        /**
         * Check the future vector to see if it has a good component with the first-traveled segment.
         * if not, mark as pending advance and wait for the RouteLine to pass it (which isn't guaranteed)
         */
        final double futureVector[] = {0.0, 0.0};
        calculateFutureVector(routeLineSegmentsToConsider, futureVector);
        final double futureVectorMagnitude = Math.sqrt(futureVector[0] * futureVector[0] + futureVector[1] * futureVector[1]), futureVectorDotProduct;
        if(travelDirection == TravelDirection.forward) {
            futureVectorDotProduct = (futureVector[0] * firstTraveledSegment.vectorX + futureVector[1] * firstTraveledSegment.vectorY) / (firstTraveledSegment.vectorMagnitude * futureVectorMagnitude);
        } else {
            futureVectorDotProduct = (-futureVector[0] * firstTraveledSegment.vectorX - futureVector[1] * firstTraveledSegment.vectorY) / (firstTraveledSegment.vectorMagnitude * futureVectorMagnitude);
        }

        if(debug) {
            System.out.format("\t\tPS way %d origin %d travel %s: Dot product of %.02f of fs [%.01f, %.01f] with fv [%.01f, %.01f]\n", line.way.osm_id, originJunction.junctionNode.osm_id, travelDirection.toString(), futureVectorDotProduct, -firstTraveledSegment.vectorX, -firstTraveledSegment.vectorY, futureVector[0], futureVector[1]);
        }

        if(futureVectorDotProduct < maxFutureVectorDeviation) {
            /*if(originJunction.junctionNode.osm_id == 53015856L) {
                System.out.format("\t\tOrigin " + originJunction.junctionNode.osm_id + "(way " + line.way.osm_id + " traveling " + travelDirection.toString() + "): Dot product of %.02f of fs [%.01f, %.01f] with fv [%.01f, %.01f]\n", futureVectorDotProduct, -firstTraveledSegment.vectorX, -firstTraveledSegment.vectorY, futureVector[0], futureVector[1]);
                System.out.println("\t\tFirst OSM segment is " + firstTraveledSegment);
            }*/
            processingStatus = ProcessingStatus.pendingAdvance;
            return false;
        }

        /**
         * Check if the lastTraveledSegment contains a possible Path Junction point
         * If the candidate segment is part of the middle of the OSM way, then we're OK for now.
         * If the candidate segment contains a node which joins 2 or more other ways, we need to decide how to proceed.
         * i.e. check the path options and decide whether to create a fork here
         */
        final OSMNode lastNode; //the last node reachable on the line, depending on direction of travel
        final int firstTraveledSegmentIndex = line.segments.indexOf(firstTraveledSegment);
        final short matchMask = SegmentMatch.matchTypeBoundingBox | SegmentMatch.matchTypeTravelDirection | SegmentMatch.matchTypeDotProduct;// SegmentMatch.matchTypeNone;
        if (travelDirection == TravelDirection.forward) {
            lastNode = line.way.getLastNode();

            final ListIterator<LineSegment> iterator = line.segments.listIterator(firstTraveledSegmentIndex);
            while (iterator.hasNext()) { //starting at the firstTraveledSegment, iterate over the way's LineSegments
                final LineSegment segment = iterator.next();
                if(line.way.osm_id == debugWayId) {
                    System.out.println("PDB check FWD " + this + ":::SEG:::" + segment);
                }

                segmentStatus = checkSegment(lineMatches, (OSMLineSegment) segment, segment.destinationNode, parentPathTree, lastNode, matchMask);
                if(segmentStatus != ProcessingStatus.inprocess) { //TODO: add fallback/scoring instead of requiring full match?
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

                segmentStatus = checkSegment(lineMatches, (OSMLineSegment) segment, segment.originNode, parentPathTree, lastNode, matchMask);
                if(segmentStatus != ProcessingStatus.inprocess) { //TODO: add fallback/scoring instead of requiring full match?
                    processingStatus = segmentStatus;
                    break;
                }
            }
        }
        return true;
    }
    private ProcessingStatus checkSegment(final LineMatch lineMatch, final OSMLineSegment segment, final OSMNode nodeToCheck, final PathTree parentPathTree, final OSMNode endingWayNode, final short matchMask) {
        //get all the routeLineSegment's that matched with this OSM segment
        final List<SegmentMatch> osmSegmentMatches = lineMatch.getRouteLineMatchesForSegment(segment, matchMask);

        //and iterate over them, ensuring that ALL meet the match mask requirements
        boolean segmentMatched = false;
        for(final SegmentMatch segmentMatch : osmSegmentMatches) {
            if(segment.getParent().way.osm_id == debugWayId) {
                System.out.println("\tSEGMATCH: " + segmentMatch);
            }
            if((segmentMatch.type & matchMask) != SegmentMatch.matchTypeNone) {
                segmentMatched = true;
                break;
            }
        }
        /*if(osmSegmentMatches.size() == 0 && segment.getParent().way.osm_id == debugWayId) {
            System.out.println("FAILED SEGMATCH FOR:" + segment + ":: " + lineMatch);
            for(final LineSegment inSegment : segment.getParent().segments) {
                System.out.println("\tSEGMENT: " + inSegment);
            }
            for(final SegmentMatch segmentMatch : lineMatch.matchingSegments) {
                System.out.println("\tSEGMATCH: " + segmentMatch);
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }*/
        if(!segmentMatched) {
            return ProcessingStatus.failedSegmentMatch;
        }

        //check if we've found the final destination node for this PathSegment's PathTree, or we're at a junction, and if so, bail
        if(nodeToCheck != null) {
            if(nodeToCheck == parentPathTree.destinationStop.getStopPosition()) {
                setEndJunction(parentPathTree.createJunction(nodeToCheck), ProcessingStatus.reachedDestination);
                return processingStatus;
            } else if(nodeToCheck == endingWayNode || nodeToCheck.containingWayCount > 1) {
                setEndJunction(parentPathTree.createJunction(nodeToCheck), ProcessingStatus.complete);
                return processingStatus;
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
        containingPaths.add(new WeakReference<Path>(path));
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
    private void setLine(final OSMWaySegments newLine) {
        if(line != null) {
            line.removeObserver(this);
        }
        line = newLine;
        if(line != null) {
            if(line.way.getNodes().indexOf(originJunction.junctionNode) == -1) {
                System.out.format("ERROR: new line %s doesn't contain origin junction node %s", line, originJunction.junctionNode);
            }
            if(endJunction != null && line.way.getNodes().indexOf(endJunction.junctionNode) == -1) {
                System.out.format("ERROR: new line %s doesn't contain ending junction node %s", line, endJunction.junctionNode);
            }
            line.addObserver(this); //track all changes to the underlying line, i.e. for splits
        }
    }
    public Junction getOriginJunction() {
        return originJunction;
    }
    public Junction getEndJunction() {
        return endJunction;
    }
    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }
    public void setEndJunction(final Junction endJunction, final ProcessingStatus processingStatus) {
        this.endJunction = endJunction;
        this.processingStatus = processingStatus;
        //endJunction.junctionPathSegments.add(this);

        //update the PathSegment cache
        id = idForParameters(line, originJunction.junctionNode, endJunction.junctionNode); //update the id as well
    }

    @Override
    public String toString() {
        if(processingStatus == ProcessingStatus.inprocess) {
            return String.format("%s (%d): from %d going %s [status “%s”]", line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originJunction.junctionNode.osm_id, travelDirection.toString(), processingStatus.toString());
        } else {
            return String.format("%s (%d): %d to %s going %s [status “%s”]", line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originJunction.junctionNode.osm_id, endJunction != null ? Long.toString(endJunction.junctionNode.osm_id) : "NONE", travelDirection.toString(), processingStatus.toString());
        }
    }
    @Override
    public void finalize() throws Throwable {
        System.out.println("PATHSEGDESTROY " + this);
        super.finalize();
    }

    @Override
    public void waySegmentsWasSplit(WaySegments originalWaySegments, OSMNode[] splitNodes, WaySegments[] splitWaySegments) throws InvalidArgumentException {
        //check whether this PathSegment needs to update its line property
        if(processingStatus == ProcessingStatus.complete || processingStatus == ProcessingStatus.reachedDestination) {
            //get a handle on the parent pathTree
            PathTree parentPathTree = null;
            for(final WeakReference<Path> containingPathReference : containingPaths) {
                final Path containingPath = containingPathReference.get();
                if(containingPath != null) {
                    parentPathTree = containingPath.parentPathTree;
                    break;
                }
            }
            assert parentPathTree != null;

            //create an ArrayList containing the split ways
            final ArrayList<WaySegments> splitSegmentsList = new ArrayList<>(splitWaySegments.length);
            Collections.addAll(splitSegmentsList, splitWaySegments);
            if (travelDirection == TravelDirection.backward) { //and reverse it if this PathSegment is traveling backwards
                Collections.reverse(splitSegmentsList);
            }

            //run the PathSegment analysis, which will decide how to assign PathSegments to the split ways
            final List<PathSegment> pathSegmentsToCreate = new ArrayList<>(splitWaySegments.length - 1);
            final ProcessingStatus originalStatus = processingStatus;
            determinePathSegmentSplitAssignments(originJunction, endJunction, splitSegmentsList, this, pathSegmentsToCreate, parentPathTree);

            //and add the newly-created PathSegments (if any) to this PathSegment's containing Path objects
            if(pathSegmentsToCreate.size() > 0) {
                System.out.println("SPLIT CREATE " + pathSegmentsToCreate.size() + " PS");

                //add the new PathSegments to this PathSegment's containing Path objects
                PathSegment previousPathSegment = this;
                for(final PathSegment newPathSegment : pathSegmentsToCreate) {
                    for(final WeakReference<Path> containingPathReference : containingPaths) {
                        final Path containingPath = containingPathReference.get();
                        if(containingPath != null) {
                            containingPath.insertPathSegment(newPathSegment, previousPathSegment);
                        }
                    }
                    previousPathSegment = newPathSegment;
                }

                //special case: if this PathSegment had reached the PathTree's destination waypoint, it's been superseded by the newly-added PathSegments
                if(previousPathSegment.processingStatus != originalStatus) {
                    previousPathSegment.processingStatus = originalStatus;
                }
            }

            //DEBUG
            for(final WeakReference<Path> containingPathReference : containingPaths) {
                final Path containingPath = containingPathReference.get();
                if(containingPath != null) {
                    int errorCount = containingPath.validatePath();
                    if (errorCount > 0) {

                    }
                }
            }
        }
        //NOTE: we ignore notifications on splits affecting unprocessed PathSegments (i.e. for PathTrees that don't have a bestPath)
    }

    @Override
    public void waySegmentsWasDeleted(WaySegments waySegments) throws InvalidArgumentException {

    }

    @Override
    public void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment oldSegment, final LineSegment[] newSegments) {

    }

    private static void determinePathSegmentSplitAssignments(final Junction originalOriginJunction, final Junction originalEndJunction, final List<WaySegments> splitWaySegments, PathSegment curSegment, List<PathSegment> pathSegmentsToCreate, final PathTree parentPathTree) {
        boolean inOriginalPathSegment = false, creatingNewPathSegments = false;
        int debugIndex = 0;
        System.out.println("\n\tCHECK SPLIT on PS " + curSegment);
        PathSegment originalPathSegment = curSegment, lastPathSegment = curSegment;
        for(final WaySegments splitLine : splitWaySegments) {
            //get the index of the start/end junctions for the current PathSegment
            final int wayNodeMaxIndex = splitLine.way.getNodes().size() - 1;
            int originIndex = splitLine.way.indexOfNode(originalOriginJunction.junctionNode), endIndex = splitLine.way.indexOfNode(originalEndJunction.junctionNode);
            final OSMNode lastReachedNodeOnWay;
            System.out.format("\n\t\t%s: way %d/%d (id %d, %d->%d, %dnodes): Indexes: %d/%d\n", curSegment.travelDirection, ++debugIndex, splitWaySegments.size(), splitLine.way.osm_id, splitLine.way.getFirstNode().osm_id, splitLine.way.getLastNode().osm_id, splitLine.way.getNodes().size(), originIndex, endIndex);

            //and adjust for travel direction
            if(curSegment.travelDirection == TravelDirection.forward) {
                lastReachedNodeOnWay = splitLine.way.getLastNode();
            } else {
                if(originIndex >= 0) {
                    originIndex = wayNodeMaxIndex - originIndex;
                }
                if(endIndex >= 0) {
                    endIndex = wayNodeMaxIndex - endIndex;
                }

                lastReachedNodeOnWay = splitLine.way.getFirstNode();
                System.out.format("\t\t%s NIDK: %d/%d\n", curSegment.travelDirection, originIndex, endIndex);
            }

            //start processing this pathSegment once we hit a splitLine that begins with it
            if(!inOriginalPathSegment) {
                if(originIndex >= 0 && originIndex < wayNodeMaxIndex) {
                    inOriginalPathSegment = true;
                    System.out.println("\t\tPS NOW IN WAY");
                } else {
                    System.out.println("\t\tPS SKIPPING WAY");
                    continue;
                }
            }

            if(!creatingNewPathSegments) {
                //if this way contains this PathSegment's ending junction, it's fully contained within the way: no need to process this PathSegment
                if(endIndex >= 0) {
                    System.out.println("\t\tFULLY CONTAINED: NO ACTION TAKEN:");
                    creatingNewPathSegments = false;
                    if (splitLine != curSegment.line) { //if the line is different from splitLine, update it here
                        System.out.println("\t\t" + curSegment.travelDirection.toString() + ": CHANGED2 FROM " + curSegment.line.way.osm_id + " to " + splitLine.way.osm_id);
                        curSegment.setLine((OSMWaySegments) splitLine);
                    }
                    break;
                }

                //if curSegment's endJunction isn't on the line, then we need to start creating new PathSegments to fill out the original PathSegment's length

                //create a new ending junction for curSegment at the last-traveled node on the splitWay
                final Junction newEndJunction = parentPathTree.createJunction(lastReachedNodeOnWay);
                curSegment.setEndJunction(newEndJunction, ProcessingStatus.complete);

                if (splitLine != curSegment.line) { //if the line is different from splitLine, update it here
                    System.out.println("\t\t" + curSegment.travelDirection.toString() + ": CHANGED FROM " + curSegment.line.way.osm_id + " to " + splitLine.way.osm_id);
                    curSegment.setLine((OSMWaySegments) splitLine);

                } else { //otherwise, no additional action needs to be taken on this PathSegment
                    System.out.println("\t\t" + curSegment.travelDirection.toString() + ": PRESERVED AT " + curSegment.line.way.osm_id);
                }

                //flag that we need to create more PathSegments
                creatingNewPathSegments = true;
                System.out.println("\t\tWILL CREATE NEW");
            } else {
                //and create a new PathSegment with the last PathSegment's ending junction as its origin
                curSegment = createNewPathSegment((OSMWaySegments) splitLine, lastPathSegment.getEndJunction(), curSegment.travelDirection);

                //and create a new end junction at the last-traveled node on the way, and cap the current segment at it
                if(endIndex >= 0) {
                    curSegment.setEndJunction(originalEndJunction, ProcessingStatus.complete);
                    pathSegmentsToCreate.add(curSegment);
                    creatingNewPathSegments = false;
                    System.out.println("\t\tFINISHED REGENERATING ON " + curSegment);
                    break;
                } else {
                    final Junction newJunction = parentPathTree.createJunction(lastReachedNodeOnWay);
                    curSegment.setEndJunction(newJunction, ProcessingStatus.complete);
                    pathSegmentsToCreate.add(curSegment);
                    System.out.println("\t\tADDED " + curSegment + ", CONTINUING...");
                }
            }

            lastPathSegment = curSegment;
        }
        System.out.println("\t\tSPLIT COMPLETE: " + pathSegmentsToCreate.size() + " NEW PS CREATED");
        System.out.println("\t\t\t" + originalPathSegment + "[updated]");
        if(pathSegmentsToCreate.size() > 0) {
            for(final PathSegment newSegment : pathSegmentsToCreate) {
                System.out.println("\t\t\t" + newSegment + "[created]");
            }
        }
        if(creatingNewPathSegments) {
            System.out.println("\t\tERROR: UNFINISHED SPLIT PROCESS!");
        }
    }
}