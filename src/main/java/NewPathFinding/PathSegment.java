package NewPathFinding;

import Conflation.*;
import Importer.InvalidArgumentException;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.Point;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

/**
 * Created by nick on 10/20/16.
 */
public class PathSegment implements WaySegmentsObserver {
    private final static double SCORE_FOR_ALIGNMENT = 100.0, SCORE_FACTOR_FOR_TRAVEL_DIRECTION = 100.0;
    private final static long debugWayId = 428243920L;

    /**
     * Whether this PathSegment is traveling with (0->N) or against(N->0) its line's segments' node direction
     */
    public enum TravelDirection {
        forward, backward
    }
    public enum ProcessingStatus {
        inprocess, pendingActivation, pendingAdvance, noFirstTraveledSegment, zeroSegmentMatches, failedSegmentMatch, complete, reachedDestination
    }

    protected final OSMNode originNode;
    private OSMNode endNode = null;

    private OSMWaySegments line;

    public String getId() {
        return id;
    }

    private String id;
    public final TravelDirection travelDirection;
    protected double traveledSegmentLength = 0.0, alignedSegmentLength = 0.0, alignedPathScore = 0.0, alignedPathDistance = 0.0; //the length of segments this path aligns with
    protected int traveledSegmentCount = 0, alignedSegmentCount = 0;
    private List<WeakReference<Path>> containingPaths = new ArrayList<>(PathTree.MAX_PATHS_TO_CONSIDER);

    private ProcessingStatus processingStatus = ProcessingStatus.inprocess;

    public static String idForParameters(final OSMWaySegments line, final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PS%d:%d->%d", line.way.osm_id, fromNode.osm_id, toNode != null ? toNode.osm_id : 0);
    }
    protected static PathSegment createNewPathSegment(final OSMWaySegments line, final OSMNode fromNode, final TravelDirection travelDirection) {
        /*final PathSegment newPathSegment = new PathSegment(line, fromNode, parentPathTree);
        final String newPSID = idForParameters(line, fromNode.junctionNode, null);
        PathSegment existingPathSegment = allPathSegments.get(newPathSegment.id);
        if(existingPathSegment == null) {
            newPathSegment.determineScore();
            allPathSegments.put(newPathSegment.id, newPathSegment);
            existingPathSegment = newPathSegment;
        } else {
            newPathSegment.retire(); //properly discard the newPathSegment, since it's not going to be used
        }
        return existingPathSegment;*/
        return new PathSegment(line, fromNode, travelDirection);
    }
    private PathSegment(final OSMWaySegments line, final OSMNode fromNode, final TravelDirection travelDirection) {
        originNode = fromNode;
        this.travelDirection = travelDirection;
        setLine(line);

        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originNode.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");

        //generate a unique ID for this PathSegment
        id = idForParameters(line, originNode, null);
    }
    public boolean advance(final List<RouteLineSegment> routeLineSegmentsToConsider, final PathTree parentPathTree, final RouteConflator routeConflator, final boolean debug) {
        //get a handle on the LineSegments involved in this step
        final RouteLineSegment targetSegment = routeLineSegmentsToConsider.get(0);

        /*check that this PathSegment's origin Junction is within the RouteLineSegment's search area.  If not,
          we've just got ahead of ourselves - bail for now
         */
        if(!targetSegment.searchAreaForMatchingOtherSegments.containsPoint(originNode.getCentroid())) {
            processingStatus = ProcessingStatus.pendingActivation;
            if(debug && line.way.osm_id == debugWayId) {
                System.out.format("\t\tSTILL WAITING FOR ACTIVATION ON RL#%d/%d: %.01f: %s\n",targetSegment.segmentIndex, targetSegment.nodeIndex, Point.distance(originNode.getCentroid(), targetSegment.midPoint), targetSegment.searchAreaForMatchingOtherSegments);
            }
            return false;
        }

        final OSMLineSegment firstTraveledSegment = findFirstTraveledSegment();
        ProcessingStatus segmentStatus;
        if(firstTraveledSegment == null) { //Shouldn't happen
            System.out.println("ERROR: no firstTraveledSegment found for " + originNode + " traveling " + travelDirection.toString());
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

        if(debug && line.way.osm_id == debugWayId) {
            System.out.format("\t\tPDB: RL#%d/%d on PS way %d origin %d travel %s: Dot product of %.02f of fs [%.01f, %.01f] with fv [%.01f, %.01f]\n", targetSegment.segmentIndex, targetSegment.nodeIndex, line.way.osm_id, originNode.osm_id, travelDirection.toString(), futureVectorDotProduct, -firstTraveledSegment.vectorX, -firstTraveledSegment.vectorY, futureVector[0], futureVector[1]);
        }

        if(futureVectorDotProduct < routeConflator.wayMatchingOptions.getMinFutureVectorDotProduct()) {
            /*if(originNode.junctionNode.osm_id == 53015856L) {
                System.out.format("\t\tOrigin " + originNode.junctionNode.osm_id + "(way " + line.way.osm_id + " traveling " + travelDirection.toString() + "): Dot product of %.02f of fs [%.01f, %.01f] with fv [%.01f, %.01f]\n", futureVectorDotProduct, -firstTraveledSegment.vectorX, -firstTraveledSegment.vectorY, futureVector[0], futureVector[1]);
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
                if(debug && line.way.osm_id == debugWayId) {
                    System.out.println("\t\t\tPDB check FWD " + this + ":::SEG:::" + segment);
                }

                segmentStatus = checkSegment(targetSegment, lineMatches, (OSMLineSegment) segment, segment.destinationNode, parentPathTree, routeConflator, lastNode, matchMask, debug);
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
                if(debug && line.way.osm_id == debugWayId) {
                    System.out.println("\t\t\tPDB check BKW " + this + ":::SEG:::" + segment);
                }

                segmentStatus = checkSegment(targetSegment, lineMatches, (OSMLineSegment) segment, segment.originNode, parentPathTree, routeConflator, lastNode, matchMask, debug);
                if(segmentStatus != ProcessingStatus.inprocess) { //TODO: add fallback/scoring instead of requiring full match?
                    processingStatus = segmentStatus;
                    break;
                }
            }
        }
        return true;
    }
    private ProcessingStatus checkSegment(final RouteLineSegment targetSegment, final LineMatch lineMatch, final OSMLineSegment segment, final OSMNode nodeToCheck, final PathTree parentPathTree, final RouteConflator routeConflator, final OSMNode endingWayNode, final short matchMask, final boolean debug) {
        //get all the routeLineSegment's that matched with this OSM segment
        final List<SegmentMatch> osmSegmentMatches = lineMatch.getRouteLineMatchesForSegment(segment, matchMask);

        if(debug && segment.getParent().way.osm_id == debugWayId) {
            System.out.format("\t\t\tPDB: targetSegment [%d/%d]: %d total matches for line %s\n", targetSegment.segmentIndex, targetSegment.nodeIndex, osmSegmentMatches.size(), segment.getParent().way);
        }

        //and iterate over them, ensuring that ALL meet the match mask requirements
        /*NOTE: this will check ALL matching RouteLineSegments with this line - including ones that may
          traverse this line earlier/later in the matching process.  This should be offset by the earlier futureVector check*/
        SegmentMatch bestSegmentMatch = null;
        double minDistance = routeConflator.wayMatchingOptions.segmentSearchBoxSize;
        for(final SegmentMatch segmentMatch : osmSegmentMatches) {
            if(debug && segment.getParent().way.osm_id == debugWayId) {
                //System.out.println("\t\t\t\tSEGMATCH: " + segmentMatch);
            }
            if((segmentMatch.type & matchMask) != SegmentMatch.matchTypeNone && segmentMatch.midPointDistance <= minDistance) {
                bestSegmentMatch = segmentMatch;
                minDistance = segmentMatch.midPointDistance;
            }
        }
        if(debug && segment.getParent().way.osm_id == debugWayId) {
            System.out.println("\t\t\t\t\tBEST SEGMATCH: " + bestSegmentMatch);
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
        traveledSegmentCount++;
        traveledSegmentLength += segment.length;
        if(bestSegmentMatch == null) {
            return ProcessingStatus.failedSegmentMatch;
        }

        //update the scores based on the segment match's quality
        if((bestSegmentMatch.type & SegmentMatch.matchTypeDotProduct) > 0) {
            alignedPathScore += SCORE_FOR_ALIGNMENT * segment.length * bestSegmentMatch.dotProduct * bestSegmentMatch.dotProduct * bestSegmentMatch.dotProduct * bestSegmentMatch.dotProduct;
            alignedSegmentCount++;
            alignedSegmentLength += segment.length;
            alignedPathDistance += bestSegmentMatch.midPointDistance;
        }
        if((bestSegmentMatch.type & SegmentMatch.matchTypeTravelDirection) > 0) {
            alignedPathScore += SCORE_FACTOR_FOR_TRAVEL_DIRECTION;
        }

        //check if we've found the final destination node for this PathSegment's PathTree, or we're at a junction, and if so, bail
        if(nodeToCheck != null) {
            if(nodeToCheck == parentPathTree.destinationStop.getStopPosition(parentPathTree.route.routeType)) {
                setEndNode(nodeToCheck, ProcessingStatus.reachedDestination);
                return processingStatus;
            } else if(nodeToCheck == endingWayNode || nodeToCheck.getContainingWayCount() > 1) { //reached the end of the way, or this node is a possible junction node
                setEndNode(nodeToCheck, ProcessingStatus.complete);
                return processingStatus;
            }
        }
        return ProcessingStatus.inprocess;
    }
    public double getScore() {
        return alignedSegmentCount > 0.0 ? alignedPathScore / Math.max(0.1, alignedPathDistance / alignedSegmentCount): 0.0;
    }
    private OSMLineSegment findFirstTraveledSegment() {
        //scan the line's segment list for the segment, returning it if found
        //if this is the first check, scan the line's segment list for the segment originating from the Junction's node
        if (travelDirection == TravelDirection.forward) {
            for(final LineSegment segment : line.segments) {
                if (segment.originNode == originNode) {
                    return (OSMLineSegment) segment;
                }
            }
        } else {
            for(final LineSegment segment : line.segments) {
                if (segment.destinationNode == originNode) {
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
        containingPaths.add(new WeakReference<>(path));
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
            if(line.way.getNodes().indexOf(originNode) == -1) {
                System.out.format("ERROR: new line %s doesn't contain origin junction node %s", line, originNode);
            }
            if(endNode != null && line.way.getNodes().indexOf(endNode) == -1) {
                System.out.format("ERROR: new line %s doesn't contain ending junction node %s", line, endNode);
            }
            line.addObserver(this); //track all changes to the underlying line, i.e. for splits
        }
    }
    public OSMNode getOriginNode() {
        return originNode;
    }
    public OSMNode getEndNode() {
        return endNode;
    }
    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }
    public void setEndNode(final OSMNode endNode, final ProcessingStatus processingStatus) {
        this.endNode = endNode;
        this.processingStatus = processingStatus;
        //endNode.junctionPathSegments.add(this);

        //update the PathSegment cache
        id = idForParameters(line, originNode, endNode); //update the id as well
    }

    @Override
    public String toString() {
        if(processingStatus == ProcessingStatus.inprocess) {
            return String.format("%s (%d): from %d going %s [status “%s”]", line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originNode.osm_id, travelDirection.toString(), processingStatus.toString());
        } else {
            return String.format("%s (%d): %d to %s going %s [status “%s”]", line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originNode.osm_id, endNode != null ? Long.toString(endNode.osm_id) : "NONE", travelDirection.toString(), processingStatus.toString());
        }
    }

    @Override
    public void waySegmentsWasSplit(WaySegments originalWaySegments, OSMNode[] splitNodes, WaySegments[] splitWaySegments) throws InvalidArgumentException {
        //check whether this PathSegment needs to update its line property
        if(processingStatus == ProcessingStatus.complete || processingStatus == ProcessingStatus.reachedDestination) {
            //create an ArrayList containing the split ways
            final ArrayList<WaySegments> splitSegmentsList = new ArrayList<>(splitWaySegments.length);
            Collections.addAll(splitSegmentsList, splitWaySegments);
            if (travelDirection == TravelDirection.backward) { //and reverse it if this PathSegment is traveling backwards
                Collections.reverse(splitSegmentsList);
            }

            //run the PathSegment analysis, which will decide how to assign PathSegments to the split ways
            final List<PathSegment> pathSegmentsToCreate = new ArrayList<>(splitWaySegments.length - 1);
            final ProcessingStatus originalStatus = processingStatus;
            determinePathSegmentSplitAssignments(originNode, endNode, splitSegmentsList, this, pathSegmentsToCreate);

            //and add the newly-created PathSegments (if any) to this PathSegment's containing Path objects
            if(pathSegmentsToCreate.size() > 0) {
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

    private static void determinePathSegmentSplitAssignments(final OSMNode originalOriginJunction, final OSMNode originalEndJunction, final List<WaySegments> splitWaySegments, PathSegment curSegment, List<PathSegment> pathSegmentsToCreate) {
        boolean inOriginalPathSegment = false, creatingNewPathSegments = false;
        int debugIndex = 0;
        //System.out.println("\n\tCHECK SPLIT on PS " + curSegment);
        PathSegment originalPathSegment = curSegment, lastPathSegment = curSegment;
        for(final WaySegments splitLine : splitWaySegments) {
            //get the index of the start/end junctions for the current PathSegment
            final int wayNodeMaxIndex = splitLine.way.getNodes().size() - 1;
            int originIndex = splitLine.way.indexOfNode(originalOriginJunction), endIndex = splitLine.way.indexOfNode(originalEndJunction);
            final OSMNode lastReachedNodeOnWay;
            //System.out.format("\n\t\t%s: way %d/%d (id %d, %d->%d, %dnodes): Indexes: %d/%d\n", curSegment.travelDirection, ++debugIndex, splitWaySegments.size(), splitLine.way.osm_id, splitLine.way.getFirstNode().osm_id, splitLine.way.getLastNode().osm_id, splitLine.way.getNodes().size(), originIndex, endIndex);

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
                //System.out.format("\t\t%s NIDK: %d/%d\n", curSegment.travelDirection, originIndex, endIndex);
            }

            //start processing this pathSegment once we hit a splitLine that begins with it
            if(!inOriginalPathSegment) {
                if(originIndex >= 0 && originIndex < wayNodeMaxIndex) {
                    inOriginalPathSegment = true;
                    //System.out.println("\t\tPS NOW IN WAY");
                } else {
                    //System.out.println("\t\tPS SKIPPING WAY");
                    continue;
                }
            }

            if(!creatingNewPathSegments) {
                //if this way contains this PathSegment's ending junction, it's fully contained within the way: no need to process this PathSegment
                if(endIndex >= 0) {
                    //System.out.println("\t\tFULLY CONTAINED: NO ACTION TAKEN:");
                    creatingNewPathSegments = false;
                    if (splitLine != curSegment.line) { //if the line is different from splitLine, update it here
                       // System.out.println("\t\t" + curSegment.travelDirection.toString() + ": CHANGED2 FROM " + curSegment.line.way.osm_id + " to " + splitLine.way.osm_id);
                        curSegment.setLine((OSMWaySegments) splitLine);
                    }
                    break;
                }

                //if curSegment's endNode isn't on the line, then we need to start creating new PathSegments to fill out the original PathSegment's length

                //create a new ending junction for curSegment at the last-traveled node on the splitWay
                curSegment.setEndNode(lastReachedNodeOnWay, ProcessingStatus.complete);

                if (splitLine != curSegment.line) { //if the line is different from splitLine, update it here
                    //System.out.println("\t\t" + curSegment.travelDirection.toString() + ": CHANGED FROM " + curSegment.line.way.osm_id + " to " + splitLine.way.osm_id);
                    curSegment.setLine((OSMWaySegments) splitLine);

                } else { //otherwise, no additional action needs to be taken on this PathSegment
                    //System.out.println("\t\t" + curSegment.travelDirection.toString() + ": PRESERVED AT " + curSegment.line.way.osm_id);
                }

                //flag that we need to create more PathSegments
                creatingNewPathSegments = true;
                //System.out.println("\t\tWILL CREATE NEW");
            } else {
                //and create a new PathSegment with the last PathSegment's ending junction as its origin
                curSegment = createNewPathSegment((OSMWaySegments) splitLine, lastPathSegment.getEndNode(), curSegment.travelDirection);

                //and create a new end junction at the last-traveled node on the way, and cap the current segment at it
                if(endIndex >= 0) {
                    curSegment.setEndNode(originalEndJunction, ProcessingStatus.complete);
                    pathSegmentsToCreate.add(curSegment);
                    creatingNewPathSegments = false;
                    //System.out.println("\t\tFINISHED REGENERATING ON " + curSegment);
                    break;
                } else {
                    curSegment.setEndNode(lastReachedNodeOnWay, ProcessingStatus.complete);
                    pathSegmentsToCreate.add(curSegment);
                    //System.out.println("\t\tADDED " + curSegment + ", CONTINUING...");
                }
            }

            lastPathSegment = curSegment;
        }

        /*System.out.println("\t\tSPLIT COMPLETE: " + pathSegmentsToCreate.size() + " NEW PS CREATED");
        System.out.println("\t\t\t" + originalPathSegment + "[updated]");
        if(pathSegmentsToCreate.size() > 0) {
            for(final PathSegment newSegment : pathSegmentsToCreate) {
                System.out.println("\t\t\t" + newSegment + "[created]");
            }
        }
        if(creatingNewPathSegments) {
            System.out.println("\t\tERROR: UNFINISHED SPLIT PROCESS!");
        }//*/
    }
}
