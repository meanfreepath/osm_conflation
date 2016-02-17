package PathFinding;

import Conflation.LineSegment;
import Conflation.SegmentMatch;
import Conflation.WaySegments;
import Conflation.WaySegmentsObserver;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.*;

/**
 * Represents the portion of a way that is traversed by a route's path
 * Created by nick on 1/27/16.
 */
public class PathSegment implements WaySegmentsObserver {
    private final static double SCORE_FOR_STOP_ON_WAY = 10000.0, SCORE_FOR_ALIGNMENT = 100.0, SCORE_FOR_DETOUR = 10.0, SCORE_FACTOR_FOR_CORRECT_ONEWAY_TRAVEL = 200.0, SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL = -200.0, SCORE_FACTOR_FOR_NON_ONEWAY_TRAVEL = 100.0;
    public enum TravelDirection {
        forward, backward
    }

    private static HashMap<String, PathSegment> allPathSegments = new HashMap<>(1024);

    public final Junction originJunction, endJunction;
    private WaySegments line;
    private String id;
    private TravelDirection travelDirection = null;
    protected double traveledSegmentLength, alignedSegmentLength, detourSegmentLength; //the length of segments this path aligns with
    protected int traveledSegmentCount, alignedSegmentCount, detourSegmentCount;
    protected double alignedLengthFactor, detourLengthFactor, alignedPathScore, detourPathScore, waypointScore;
    private List<Path> containingPaths = new ArrayList<>(PathTree.MAX_PATHS_TO_CONSIDER);
    private final PathTree parentPathTree;
    private final List<LineSegment> containedSegments;

    private final long debugWayId = -1296L;

    private List<List<WaySegments>> splitHistory = new ArrayList<>(16);

    /**
     * Whether the contained line has a good enough match to process
     */
    private boolean lineMatched = false;

    final static String debugId = "PS364419113:53087445->-1257";

    public static String idForParameters(final OSMWay way, final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PS%d:%d->%d", way.osm_id, fromNode.osm_id, toNode.osm_id);
    }
    public String getId() {
        return id;
    }
    public boolean containsPathDestinationNode() {
        return endJunction.junctionNode == parentPathTree.toNode;
    }

    protected static void clearPathSegmentCache() {
        allPathSegments.clear();
    }
    protected static PathSegment createNewPathSegment(final WaySegments line, final Junction fromJunction, final PathTree parentPathTree) {
        final PathSegment newPathSegment = new PathSegment(line, fromJunction, parentPathTree);
        PathSegment existingPathSegment = allPathSegments.get(newPathSegment.id);
        if(existingPathSegment == null) {
            newPathSegment.determineScore();
            allPathSegments.put(newPathSegment.id, newPathSegment);
            existingPathSegment = newPathSegment;
        } else {
            newPathSegment.retire(); //properly discard the newPathSegment, since it's not going to be used
        }
        return existingPathSegment;
    }
    private static PathSegment createSplitPathSegment(final WaySegments line, final Junction fromJunction, final Junction toJunction, final PathSegment originalPathSegment) {
        final String id = idForParameters(line.way, fromJunction.junctionNode, toJunction.junctionNode);
        PathSegment existingPathSegment = allPathSegments.get(id);
        if(existingPathSegment == null) {
            existingPathSegment = new PathSegment(line, fromJunction, toJunction, originalPathSegment);
            existingPathSegment.determineScore();
            allPathSegments.put(existingPathSegment.id, existingPathSegment);
        }
        return existingPathSegment;
    }
    private PathSegment(final WaySegments line, final Junction fromJunction, final Junction toJunction, final PathSegment originalPathSegment) {
        this.line = line;
        this.line.addObserver(this); //track all changes to the underlying line, i.e. for splits
        originJunction = fromJunction;
        endJunction = toJunction;
        containedSegments = new ArrayList<>(line.segments.size());

        //generate a unique ID for this PathSegment
        id = idForParameters(line.way, originJunction.junctionNode, endJunction.junctionNode);

        parentPathTree = originalPathSegment.parentPathTree;
        travelDirection = originalPathSegment.travelDirection;
    }
    private PathSegment(final WaySegments line, final Junction fromJunction, final PathTree parentPathTree) {
        this.line = line;
        this.line.addObserver(this); //track all changes to the underlying line, i.e. for splits
        originJunction = fromJunction;
        this.parentPathTree = parentPathTree;
        containedSegments = new ArrayList<>(line.segments.size());

        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originJunction.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");
        lineMatched = false;

        endJunction = determineEndJunction();

        //generate a unique ID for this PathSegment
        id = idForParameters(line.way, originJunction.junctionNode, endJunction.junctionNode);
    }
    public void retire() {
        line.removeObserver(this);
    }
    private Junction determineEndJunction() {
        //determine the direction of this line relative to the direction of route travel
        final WaySegments.LineMatch lineMatch = line.getMatchForLine(parentPathTree.parentPathFinder.route.routeLine);
        if(lineMatch == null) {
            parentPathTree.parentPathFinder.logEvent(RoutePathFinder.RouteLogType.error, "No match for line " + line.way.getTag(OSMEntity.KEY_NAME) + "(" + line.way.osm_id + ")", this);
            return new Junction(originJunction.junctionNode, this, Junction.JunctionProcessStatus.deadEnd);
        }
        travelDirection = lineMatch.getAvgDotProduct() >= 0.0 ? TravelDirection.forward : TravelDirection.backward;
        if(line.way.osm_id == debugWayId) {
            System.out.println("PDB check DIRECTION " + this);
        }

        //determine the nodes on this path segment, and calculate their score based on their alignment with the main route's path
        final OSMNode lastNode; //the last node reachable on the line, depending on direction of travel
        if(travelDirection == TravelDirection.forward) { //i.e. we're traveling along the node order of this way
            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            lastNode = line.way.getLastNode();
            boolean inSegment = false;
            final ListIterator<LineSegment> iterator = line.segments.listIterator();
            while (iterator.hasNext()) {
                final LineSegment segment = iterator.next();
                if(line.way.osm_id == debugWayId) {
                    System.out.println("PDB check " + this + ":::SEG:::" + segment);
                }

                //only process the segments starting at the origin junction
                if(!inSegment) {
                    if(segment.originNode == originJunction.junctionNode) {
                        inSegment = true;
                    } else {
                        continue;
                    }
                }

                containedSegments.add(segment);

                //check if we've found the final destination node for this PathSegment's PathTree, or we're at a junction, and if so, bail
                if(segment.destinationNode != null) {
                    if(segment.destinationNode == parentPathTree.toNode || segment.destinationNode.containingWayCount > 1) {
                        return new Junction(segment.destinationNode, this, Junction.JunctionProcessStatus.continuePath);
                    }
                }
            }
        } else { //i.e. we're traveling against the node order of this way
            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            lastNode = line.way.getFirstNode();
            boolean inSegment = false;
            final ListIterator<LineSegment> iterator = line.segments.listIterator(line.segments.size());
            while (iterator.hasPrevious()) {
                final LineSegment segment = iterator.previous();

                //only process the segments starting at the origin junction
                if(!inSegment) {
                    if(segment.destinationNode == originJunction.junctionNode) {
                        inSegment = true;
                    } else {
                        continue;
                    }
                }

                containedSegments.add(segment);

                //check if we've found the final destination node for this PathSegment's PathTree, or we're at a junction, and if so, bail
                if(segment.originNode != null) {
                    if(segment.originNode == parentPathTree.toNode) {
                        return new Junction(segment.originNode, this, Junction.JunctionProcessStatus.continuePath);
                    } else if(segment.originNode.containingWayCount > 1) {
                        return new Junction(segment.originNode, this, Junction.JunctionProcessStatus.continuePath);
                    }
                }
            }
        }

        //if endJunction wasn't created above, this is a dead-end PathSegment
        if(line.way.osm_id == debugWayId) {
            System.out.println("NO END JUNCTION, travelled " + traveledSegmentCount);
        }
        return new Junction(lastNode, this, Junction.JunctionProcessStatus.deadEnd);
    }
    private void determineScore() {
        waypointScore = alignedPathScore = detourPathScore = alignedLengthFactor = traveledSegmentLength = detourSegmentLength = 0.0;
        traveledSegmentCount = alignedSegmentCount = detourSegmentCount = 0;

        //add scores if the origin and/or destination stops are present on this PathSegment
        if(originJunction.junctionNode == parentPathTree.fromNode) {
            waypointScore += SCORE_FOR_STOP_ON_WAY;
        }
        if(endJunction.junctionNode == parentPathTree.toNode) {
            waypointScore += SCORE_FOR_STOP_ON_WAY;
        }

        //determine the match score by iterating over the contained LineSegments on this PathSegment
        final long routeLineId = parentPathTree.parentPathFinder.route.routeLine.way.osm_id;
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

        //if endJunction wasn't created above, this is a dead-end PathSegment
        lineMatched = true;
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
    public boolean isLineMatchedWithRoutePath() {
        return lineMatched;
    }
    private void processSegmentScore(final LineSegment segment, final long routeLineId, final double directionMultiplier) {
        traveledSegmentLength += segment.length;
        traveledSegmentCount++;

        //System.out.println("segment " + line.way.getTag("name") + ":" + (segment.destinationNode != null? segment.destinationNode.osm_id : "N/A") + "/" + (segment.originNode != null? segment.originNode.osm_id : "N/A"));
        final SegmentMatch bestMatchForSegment = segment.bestMatchForLine.get(routeLineId);
        if(bestMatchForSegment != null) {
            alignedPathScore += directionMultiplier * SCORE_FOR_ALIGNMENT * Math.abs(bestMatchForSegment.dotProduct) / bestMatchForSegment.midPointDistance;
            if(line.way.osm_id == debugWayId) {
                System.out.println("WAY:: " + line.way.osm_id + "/SEG " + bestMatchForSegment + ": dm " + directionMultiplier + "/dp " + bestMatchForSegment.dotProduct + "/dist " + bestMatchForSegment.midPointDistance + ":::score " + alignedPathScore);
            }
            alignedSegmentLength += segment.length;
            alignedSegmentCount++;
        } else {
            detourPathScore += directionMultiplier * SCORE_FOR_DETOUR;
            detourSegmentLength += segment.length;
            detourSegmentCount++;
        }
    }
    private double calculateDirectionMultiplier(final WaySegments.OneWayDirection travelDirection, final OSMNode originatingNodeForTravel, final RoutePathFinder parentPath) {
        final double directionMultiplier;
        final boolean wayIsOneWay = line.oneWayDirection != WaySegments.OneWayDirection.none;
        if(wayIsOneWay) {
            if(line.oneWayDirection != travelDirection) { //if a oneway is present, and runs counter our direction of travel
                //parentPath.logEvent(RoutePathFinder.RouteLogType.notice, "PATH BLOCKS ENTRY TO " + line.way.getTag("name") + " (" + line.way.osm_id + "): " + line.oneWayDirection.name() + "/" + travelDirection.name(), this);
                directionMultiplier = SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL;
            } else if(originJunction.junctionNode == originatingNodeForTravel) { //if entering from the originating node would cause us to go counter to the oneway, also bail
                //parentPath.logEvent(RoutePathFinder.RouteLogType.notice, "REVPATH BLOCKS ENTRY TO " + line.way.getTag("name") + " (" + line.way.osm_id + ")", this);
                directionMultiplier = SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL;
            } else {
                directionMultiplier = SCORE_FACTOR_FOR_CORRECT_ONEWAY_TRAVEL;
            }
        } else {
            directionMultiplier = SCORE_FACTOR_FOR_NON_ONEWAY_TRAVEL;
        }
        return directionMultiplier;
    }
    public double getPathScore() {
        return alignedLengthFactor * alignedPathScore + detourLengthFactor * detourPathScore;
    }
    public double getWaypointScore() {
        return waypointScore;
    }
    public double getTotalScore() {
        return waypointScore + alignedLengthFactor * alignedPathScore + detourLengthFactor * detourPathScore;
    }
    public String toString() {
        return String.format("PathSegment@%d: line \"%s\" (%d:%d->%s), travel: %s", hashCode(), line.way.getTag(OSMEntity.KEY_NAME), line.way.osm_id, originJunction.junctionNode.osm_id, endJunction != null ? Long.toString(endJunction.junctionNode.osm_id) : "N/D", travelDirection != null ? travelDirection.name() : "unknown");
    }
    public WaySegments getLine() {
        return line;
    }
    public TravelDirection getTravelDirection() {
        return travelDirection;
    }
    public void addContainingPath(final Path path) {
        containingPaths.add(path);
    }
    public void removeContainingPath(final Path path) {
        containingPaths.remove(path);
    }
    private void setLine(final WaySegments newLine) {
        //System.out.println("Reassigned " + id + "(" + line.way.getTag("name") + ") from " + line.way.osm_id + " to " + newLine.way.osm_id);
        line.removeObserver(this);
        newLine.addObserver(this);

        /*if(id.equals(debugId)) {
            System.out.println("SET LINE");
        }*/

        line = newLine;

        //regenerate the id since the line changed
        id = idForParameters(line.way, originJunction.junctionNode, endJunction.junctionNode);
    }
    private class SplitInfo {
        public final WaySegments line;
        public final OSMNode fromNode, toNode;
        public SplitInfo(final WaySegments line, final OSMNode fromNode, final OSMNode toNode) {
            this.line = line;
            this.fromNode = fromNode;
            this.toNode = toNode;
        }
    }
    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {

        if(id.equals(debugId)) {
            List<WaySegments> splitSegs = new ArrayList<>(splitWaySegments.length);
            for (final WaySegments waySegments : splitWaySegments) {
                splitSegs.add(waySegments);
            }
            splitHistory.add(splitSegs);
        }

        if(travelDirection == null) { //no need for unprocessed PathSegments to care about splits
            line.removeObserver(this);
            return;
        }

        if(id.equals(debugId)) {
            System.out.println("-----------------------------------------------------------------------------");
            System.out.println("CHECK PathSegment SPLIT FOR " + this + ": " + splitWaySegments.length + " splitLines");
        }
        final List<SplitInfo> overlappingLines = new ArrayList<>(splitWaySegments.length - 1); //populated if this PathSegment is being split in the middle
        for(final WaySegments splitLine : splitWaySegments) {
            final List<OSMNode> splitWayNodesOnPathSegment = splitLine.way.getNodes();

            //System.out.println("CHECK " + splitLine + ": " + splitLine.way);
            //check the overlap of the newly-split lines - if one or more lines begin/end within this PathSegment, it needs to be split
            final boolean wayContainsOriginNode = splitWayNodesOnPathSegment.contains(originJunction.junctionNode), wayContainsEndNode = splitWayNodesOnPathSegment.contains(endJunction.junctionNode);
            if (wayContainsOriginNode && wayContainsEndNode) { //line encompasses (or is equal to) entire PathSegment
                if(splitLine != line) {
                    setLine(splitLine);
                }
                return; //bail, since we've found the line that fully contains this PathSegment
            } else if(wayContainsOriginNode) { //if the splitLine contains this PathSegment's origin node, make sure splitLine starts or ends at that node
                /*check if the way intrudes partially into this PathSegment via the originJunction:
                  traveling forward: way ends somewhere in the middle of this PathSegment (i.e. not on the originJunction)
                  traveling backward: way starts somewhere in the middle of this PathSegment (i.e. not on the originJunction)
                  */
                if(travelDirection == TravelDirection.forward && splitLine.way.getLastNode() != originJunction.junctionNode) {
                    overlappingLines.add(new SplitInfo(splitLine, originJunction.junctionNode, splitLine.way.getLastNode()));
                } else if(travelDirection == TravelDirection.backward && splitLine.way.getFirstNode() != originJunction.junctionNode) {
                    overlappingLines.add(new SplitInfo(splitLine, originJunction.junctionNode, splitLine.way.getFirstNode()));
                }
            } else if(wayContainsEndNode) { //if the splitLine contains this PathSegment's end node, make sure splitLine starts or ends at that node
                /*check if the way intrudes partially into this PathSegment via the endJunction:
                  traveling forward: way starts somewhere in the middle of this PathSegment (i.e. not on the endJunction)
                  traveling backward: way ends somewhere in the middle of this PathSegment (i.e. not on the endJunction)
                  */
                if(travelDirection == TravelDirection.forward && splitLine.way.getFirstNode() != endJunction.junctionNode) {
                    overlappingLines.add(new SplitInfo(splitLine, splitLine.way.getFirstNode(), endJunction.junctionNode));
                } else if(travelDirection == TravelDirection.backward && splitLine.way.getLastNode() != endJunction.junctionNode) {
                    overlappingLines.add(new SplitInfo(splitLine, splitLine.way.getLastNode(), endJunction.junctionNode));
                }
            } else { //if the splitLine is fully contained within this PathSegment
                if(travelDirection == TravelDirection.forward) {
                    overlappingLines.add(new SplitInfo(splitLine, splitLine.way.getFirstNode(), splitLine.way.getLastNode()));
                } else if(travelDirection == TravelDirection.backward){
                    overlappingLines.add(new SplitInfo(splitLine, splitLine.way.getLastNode(), splitLine.way.getFirstNode()));
                }
            }
        }

        //DEBUG info
        //OSMNode splitNode = originJunction.junctionNode, lastNode = endJunction.junctionNode;
        List<OSMNode> segNodes = new ArrayList<>(containedSegments.size());
        List<List<OSMNode>> lineNodes = new ArrayList<>(splitWaySegments.length);
        for(final WaySegments splitSeg : splitWaySegments) {
            List<OSMNode> sNodes = new ArrayList<>(splitSeg.way.getNodes());
            lineNodes.add(sNodes);
        }
        for(final LineSegment segment : containedSegments) {
            if(segment.originNode != null) {
                segNodes.add(segment.originNode);
            }
            if(segment.destinationNode != null) {
                segNodes.add(segment.destinationNode);
            }
        }
        if(travelDirection == TravelDirection.backward) {
            Collections.reverse(segNodes);
        }

        if(id.equals(debugId)) {
            System.out.println("DEBUG");
        }

        //if we've reached this stage, one or more of the split lines doesn't full contain this PathSegment: we need to split it
        if(overlappingLines.size() > 0) {
            //if travelling forward on the original line, we want to keep the last line in the split list
            System.out.println(splitWaySegments.length + "/" + overlappingLines.size() +  " NEW PS REQUIRED");
            final List<PathSegment> splitPathSegments = new ArrayList<>(overlappingLines.size());

            Junction.PathSegmentStatus originatingPathSegmentStatus = originJunction.originatingPathSegment;
            Junction lastEndingJunction = null;
            for(final SplitInfo overlappingLine : overlappingLines) {
                final PathSegment splitPathSegment;
                final Junction startingJunction, endingJunction;
                if(originJunction.junctionNode == overlappingLine.fromNode) {
                    startingJunction = originJunction;
                } else if(lastEndingJunction != null) {
                    startingJunction = lastEndingJunction;
                } else {
                    continue; //NOTE: we should never have to create a starting junction - always reuse either the originJunction or the last ending junction
                }

                if(endJunction.junctionNode == overlappingLine.toNode) {
                    endingJunction = endJunction;
                } else {
                    //NOTE: the new endingJunction's originatingPathSegment should NOT be this PathSegment: it's replaced at the end of the loop with the new splitPathSegment
                    endingJunction = new Junction(overlappingLine.toNode, this, Junction.JunctionProcessStatus.continuePath);
                }
                splitPathSegment = createSplitPathSegment(overlappingLine.line, startingJunction, endingJunction, this);

                //System.out.println("New PS " + splitPathSegment + ":vs:" + overlappingLine.fromNode.osm_id + "->" + overlappingLine.toNode.osm_id + ", " + overlappingLine.line.way);
                splitPathSegments.add(splitPathSegment);

                //update the origin/end junction objects of this PathSegment
                if(startingJunction == originJunction) {
                    originJunction.replacePathSegment(this, splitPathSegment);
                } else {
                    startingJunction.addPathSegment(splitPathSegment, originatingPathSegmentStatus.processStatus);
                }

                //ensure the endingJunction has the correct originatingPathSegment value (it's incorrect as of this point, and needs to be set to splitPathSegment)
                originatingPathSegmentStatus = endingJunction.replacePathSegment(this, splitPathSegment);

                lastEndingJunction = endingJunction;
            }

            //reverse the order of the PathSegment array if travelling backward
            if(travelDirection == TravelDirection.backward) {
                Collections.reverse(splitPathSegments);
            }

            //and add the newly-split PathSegments to their respective paths
            final List<Path> containingPathsToNotify = new ArrayList<>(containingPaths);
            for(final Path path : containingPathsToNotify) {
                path.replaceSplitPathSegment(this, splitPathSegments);
            }

            //and stop subscribing to any updates etc
            retire();
        }
    }
    @Override
    public void waySegmentsWasDeleted(final WaySegments waySegments) throws InvalidArgumentException {

    }
    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment newSegment) {

    }
}
