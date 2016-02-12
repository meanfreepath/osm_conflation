package PathFinding;

import Conflation.LineSegment;
import Conflation.SegmentMatch;
import Conflation.WaySegments;
import Conflation.WaySegmentsObserver;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

/**
 * Represents the portion of a way that is traversed by a route's path
 * Created by nick on 1/27/16.
 */
public class PathSegment implements WaySegmentsObserver {
    private final static double SCORE_FOR_STOP_ON_WAY = 10000.0, SCORE_FOR_ALIGNMENT = 100.0, SCORE_FACTOR_FOR_CORRECT_ONEWAY_TRAVEL = 200.0, SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL = -200.0, SCORE_FACTOR_FOR_NON_ONEWAY_TRAVEL = 100.0;
    public enum TravelDirection {
        forward, backward
    };

    public final Junction originJunction, endJunction;
    private WaySegments line;
    private String id;
    private TravelDirection travelDirection = null;
    private double traveledSegmentLength, alignedPathLength = 0.0; //the length of segments this path aligns with
    private int traveledSegmentCount = 0, alignedSegmentCount = 0;
    public double lengthFactor, pathScore, waypointScore;
    private boolean containsPathOriginNode = false, containsPathDestinationNode = false;
    private List<Path> containingPaths = new ArrayList<>(PathTree.MAX_PATHS_TO_CONSIDER);
    private final PathTree parentPathTree;

    /**
     * Whether the contained line has a good enough match to process
     */
    private boolean lineMatched = false;

    public static String idForParameters(final OSMWay way, final OSMNode node) {
        return String.format("%d:%d", way.osm_id, node.osm_id);
    }
    public String getId() {
        return id;
    }
    public PathSegment(final WaySegments line, final Junction fromJunction, final Junction toJunction, final PathSegment originalPathSegment) {
        this.line = line;
        this.line.addObserver(this);
        originJunction = fromJunction;
        endJunction = toJunction;

        //generate a unique ID for this PathSegment
        id = idForParameters(line.way, originJunction.junctionNode);

        lineMatched = originalPathSegment.lineMatched;

        //TODO update matching
        parentPathTree = originalPathSegment.parentPathTree;
    }
    public PathSegment(final WaySegments line, final Junction fromJunction, final PathTree parentPathTree) {
        this.line = line;
        this.line.addObserver(this);
        originJunction = fromJunction;
        this.parentPathTree = parentPathTree;

        //generate a unique ID for this PathSegment
        id = idForParameters(line.way, originJunction.junctionNode);

        pathScore = waypointScore = 0.0;

        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originJunction.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");
        lineMatched = false;

        final int debugWayId = 60914402;

        //determine the direction of this line relative to the direction of route travel
        if(line.getMatchForLine(parentPathTree.parentPathFinder.route.routeLine) == null) {
            parentPathTree.parentPathFinder.logEvent(RoutePathFinder.RouteLogType.error, "No match for line " + line.way.getTag(OSMEntity.KEY_NAME) + "(" + line.way.osm_id + ")", this);
            endJunction = new Junction(fromJunction.junctionNode, this, Junction.JunctionProcessStatus.deadEnd);
            return;
        }
        travelDirection = line.getMatchForLine(parentPathTree.parentPathFinder.route.routeLine).getAvgDotProduct() >= 0.0 ? TravelDirection.forward : TravelDirection.backward;

        final long routeLineId = parentPathTree.parentPathFinder.route.routeLine.way.osm_id;

        //determine the nodes on this path segment, and calculate their score based on their alignment with the main route's path
        final OSMNode firstNode, lastNode;
        Junction tmpEndJunction = null;
        if(travelDirection == TravelDirection.forward) { //i.e. we're traveling along the node order of this way
            firstNode = line.segments.get(0).originNode;
            lastNode = line.segments.get(line.segments.size() - 1).destinationNode;

            //determine whether we're going with or against the oneway tag on the current way
            final double directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.forward, lastNode, parentPathTree.parentPathFinder);

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            boolean inSegment = false;
            final ListIterator<LineSegment> iterator = line.segments.listIterator();
            while (iterator.hasNext()) {
                final LineSegment segment = iterator.next();

                //only process the segments starting at the origin junction
                if(!inSegment) {
                    if(segment.originNode == originJunction.junctionNode) {
                        inSegment = true;
                    } else {
                        continue;
                    }
                }
                traveledSegmentLength += segment.length;
                traveledSegmentCount++;
                //System.out.println("segment " + line.way.getTag("name") + ":" + (segment.originNode != null? segment.originNode.osm_id : "N/A") + "/" + (segment.destinationNode != null? segment.destinationNode.osm_id + " - " + segment.destinationNode.containingWays.size() + " containing ways" : "N/A"));

                //sum up the score
                final SegmentMatch bestMatchForSegment = segment.bestMatchForLine.get(routeLineId);
                if(bestMatchForSegment != null) {
                    pathScore += directionMultiplier * SCORE_FOR_ALIGNMENT * Math.abs(bestMatchForSegment.dotProduct) / bestMatchForSegment.midPointDistance;
                    alignedPathLength += segment.length;
                    alignedSegmentCount++;
                    if(line.way.osm_id == debugWayId) {
                        System.out.println(String.format("WAY:: %d/%s::mult %.02f, score %.01f", line.way.osm_id, bestMatchForSegment, directionMultiplier, pathScore));
                    }
                }

                //flag whether this PathSegment contains the origin node for the entire path
                if(segment.originNode == parentPathTree.fromNode) {
                    containsPathOriginNode = true;
                    waypointScore += SCORE_FOR_STOP_ON_WAY;
                }

                //check if we've found the final destination node on this PathSegment, or we're at a junction, and if so, bail
                if(segment.destinationNode != null) {
                    if(segment.destinationNode == parentPathTree.toNode) {
                        containsPathDestinationNode = true;
                        tmpEndJunction = new Junction(segment.destinationNode, this, Junction.JunctionProcessStatus.continuePath);
                        waypointScore += SCORE_FOR_STOP_ON_WAY;
                        break;
                    } else if(segment.destinationNode.containingWayCount > 1) {
                        tmpEndJunction = new Junction(segment.destinationNode, this, Junction.JunctionProcessStatus.continuePath);
                        break;
                    }
                }
            }
        } else { //i.e. we're traveling against the node order of this way
            firstNode = line.segments.get(line.segments.size() - 1).destinationNode;
            lastNode = line.segments.get(0).originNode;

            final double directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.backward, lastNode, parentPathTree.parentPathFinder);

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
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
                traveledSegmentLength += segment.length;
                traveledSegmentCount++;

                //System.out.println("segment " + line.way.getTag("name") + ":" + (segment.destinationNode != null? segment.destinationNode.osm_id : "N/A") + "/" + (segment.originNode != null? segment.originNode.osm_id : "N/A"));
                final SegmentMatch bestMatchForSegment = segment.bestMatchForLine.get(routeLineId);
                if(bestMatchForSegment != null) {
                    pathScore += directionMultiplier * SCORE_FOR_ALIGNMENT * Math.abs(bestMatchForSegment.dotProduct) / bestMatchForSegment.midPointDistance;
                    if(line.way.osm_id == debugWayId) {
                        System.out.println("WAY:: " + line.way.osm_id + "/SEG " + bestMatchForSegment + ": dm " + directionMultiplier + "/dp " + bestMatchForSegment.dotProduct + "/dist " + bestMatchForSegment.midPointDistance + ":::score " + pathScore);
                    }
                    alignedPathLength += segment.length;
                    alignedSegmentCount++;
                }

                //flag whether this PathSegment contains the origin node for the entire path
                if(segment.destinationNode == parentPathTree.fromNode) {
                    containsPathOriginNode = true;
                    waypointScore += SCORE_FOR_STOP_ON_WAY;
                }

                //check if we're at a junction, and if so, bail
                if(segment.originNode != null) {
                    if(segment.originNode == parentPathTree.toNode) {
                        containsPathDestinationNode = true;
                        tmpEndJunction = new Junction(segment.originNode, this, Junction.JunctionProcessStatus.continuePath);
                        waypointScore += SCORE_FOR_STOP_ON_WAY;
                        break;
                    } else if(segment.originNode.containingWayCount > 1) {
                        tmpEndJunction = new Junction(segment.originNode, this, Junction.JunctionProcessStatus.continuePath);
                        break;
                    }
                }
            }
        }

        //if endJunction wasn't created above, this is a dead-end PathSegment
        if(tmpEndJunction != null) {
            lineMatched = true;
            if(alignedPathLength > 0) {
                pathScore /= alignedPathLength;
            }
            if(traveledSegmentLength > 0) {
                lengthFactor = alignedPathLength / traveledSegmentLength;
            }
            endJunction = tmpEndJunction;
        } else {
            lengthFactor = 0.0;
            endJunction = new Junction(lastNode, this, Junction.JunctionProcessStatus.deadEnd);
            if(line.way.osm_id == debugWayId) {
                System.out.println("NO END JUNCTION, travelled " + traveledSegmentCount);
            }
        }

        if(line.way.osm_id == debugWayId) {
            System.out.println("WAY:: " + line.way.osm_id + " matched? " + Boolean.toString(lineMatched) + ", % travelled: " + (Math.round(lengthFactor * 10000.0) / 100.0) + ", score " + waypointScore + "/" + pathScore);
        }

        //System.out.println("PROCESSED Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): " + originJunction.junctionNode.osm_id + "->" + endJunction.junctionNode.osm_id + ", length: " + Math.round(100.0 * alignedPathLength / traveledSegmentLength) + "%, score " + getScore());

        //if this segment is the root segment of a Path, default its originating node to the "first" (based on our direction of travel) node on the path
        /*if(parentPathSegment == null && originatingNode == null) {
            originatingNode = firstNode;
        }*/
    }
    public boolean isLineMatchedWithRoutePath() {
        return lineMatched;
    }
    /*private int processSegment(final boolean inSegment, final LineSegment segment, final OSMNode node, final double directionMultiplier) {
        //only process the segments starting at the origin junction
        if(!inSegment) {
            if(node == originJunction.junctionNode) {
                inSegment = true;
            } else {
                return 0;
            }
        }
        System.out.println("segment " + line.way.getTag("name") + ":" + (node != null? node.osm_id : "N/A") + "/" + (segment.destinationNode != null? segment.destinationNode.osm_id + " - " + segment.destinationNode.containingWays.size() + " containing ways" : "N/A"));

        //sum up the scode
        if(segment.bestMatch != null) {
            pathScore += directionMultiplier * SCORE_FOR_ALIGNMENT * Math.abs(segment.bestMatch.dotProduct) / segment.bestMatch.midPointDistance;
            alignedPathLength += segment.length;
        }

        //check if we're at a junction, and if so, bail
        if(segment.destinationNode != null) {
            segmentNodes.add(segment.destinationNode);
            System.out.println(segment.destinationNode.osm_id + ":" + segment.destinationNode.containingWays.size() + " containing");
            if(segment.destinationNode.containingWays.size() > 1) {
                endJunction = new Junction(segment.destinationNode, this);
                break;
            }
        }
    }*/
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
    public double getScore() {
        return lengthFactor * pathScore + waypointScore;
    }
    public boolean containsPathOriginNode() {
        return containsPathOriginNode;
    }
    public boolean containsPathDestinationNode() {
        return containsPathDestinationNode;
    }
    public String toString() {
        return "PathSegment " + line.way.getTag(OSMEntity.KEY_NAME) + " (" + line.way.osm_id + ": " + originJunction.junctionNode.osm_id + "->" + (endJunction != null ? endJunction.junctionNode.osm_id : "MatchEnd") + "), travel: " + (travelDirection != null ? travelDirection.name() : "unknown");
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
    public void retire() {
        line.removeObserver(this);
    }
    private void setLine(final WaySegments newLine) {
        System.out.println("Reassigned " + id + "(" + line.way.getTag("name") + ") from " + line.way.osm_id + " to " + newLine.way.osm_id);
        line.removeObserver(this);
        newLine.addObserver(this);

        line = newLine;

        //regenerate the id since the line changed
        id = idForParameters(line.way, originJunction.junctionNode);
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
        if(!lineMatched) { //no need for unmatched PathSegments to care about splits
            line.removeObserver(this);
            return;
        }

        assert travelDirection != null;

        //System.out.println("-----------------------------------------------------------------------------");
        //System.out.println("CHECK PathSegment SPLIT FOR " + this + ": " + splitWaySegments.length + " splitLines");
        final List<SplitInfo> overlappingLines = new ArrayList<>(splitWaySegments.length - 1); //populated if this PathSegment is being split in the middle
        for(final WaySegments splitLine : splitWaySegments) {
            final List<OSMNode> splitWayNodesOnPathSegment = splitLine.way.getNodes();

            //System.out.println("CHECK " + splitLine + ": " + splitLine.way.debugOutput());
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

        //if we've reached this stage, one or more of the split lines doesn't full contain this PathSegment: we need to split it
        if(overlappingLines.size() > 0) {
            //if travelling forward on the original line, we want to keep the last line in the split list
            //System.out.println(splitWaySegments.length + "/" + overlappingLines.size() +  " NEW PS REQUIRED");
            final List<PathSegment> splitPathSegments = new ArrayList<>(overlappingLines.size());

            Junction.PathSegmentStatus originatingPathSegmentStatus = originJunction.originatingPathSegment;
            PathSegment pathSegmentReplacementOrigin = null, pathSegmentReplacementEnd = null;
            for(final SplitInfo overlappingLine : overlappingLines) {
                final Junction startingJunction = new Junction(overlappingLine.fromNode, originatingPathSegmentStatus.segment, Junction.JunctionProcessStatus.continuePath);
                final PathSegment splitPathSegment = new PathSegment(overlappingLine.line, startingJunction, parentPathTree);
                originatingPathSegmentStatus = startingJunction.addPathSegment(splitPathSegment, originatingPathSegmentStatus.processStatus);
                //System.out.println("New PS " + splitPathSegment + ":vs:" + overlappingLine.fromNode.osm_id + "->" + overlappingLine.toNode.osm_id + ", " + overlappingLine.line.way.debugOutput());
                splitPathSegments.add(splitPathSegment);

                //update the origin/end junction objects of this PathSe
                if(overlappingLine.fromNode == originJunction.junctionNode) {
                    pathSegmentReplacementOrigin = splitPathSegment;
                } else if(overlappingLine.toNode == endJunction.junctionNode) {
                    pathSegmentReplacementEnd = splitPathSegment;
                }
            }

            //reverse the order of the PathSegment array if travelling backward
            if(travelDirection == TravelDirection.backward) {
                Collections.reverse(splitPathSegments);
            }

            //and add the newly-split PathSegments to their respective paths
            for(final Path path : containingPaths) {
                path.replaceSplitPathSegment(this, splitPathSegments);
            }

            //and replace any references to this PathSegment
            containingPaths.clear();
            originJunction.replacePathSegment(this, pathSegmentReplacementOrigin);
            endJunction.replacePathSegment(this, pathSegmentReplacementEnd);
        }
    }
    @Override
    public void waySegmentsWasDeleted(final WaySegments waySegments) throws InvalidArgumentException {

    }
    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment newSegment) {

    }
}
