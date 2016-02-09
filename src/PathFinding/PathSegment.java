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

    public final Junction originJunction;
    private Junction endJunction;
    private OSMNode lastCheckedNode = null;
    private WaySegments line;
    private String id;
    private TravelDirection travelDirection = null;
    private double traveledSegmentLength, alignedPathLength = 0.0; //the length of segments this path aligns with
    private int traveledSegmentCount = 0, alignedSegmentCount = 0;
    public double lengthFactor, pathScore, waypointScore;
    private boolean containsPathOriginNode = false, containsPathDestinationNode = false;

    /**
     * Whether the contained line has a good enough match to process
     */
    private boolean lineMatched = false;

    public PathSegment(final WaySegments line, final Junction fromJunction) {
        this.line = line;
        originJunction = fromJunction;

        //generate a unique ID for this PathSegment
        id = idForParameters(line.way, originJunction.junctionNode);

        pathScore = waypointScore = 0.0;

        line.addObserver(this);
    }
    public static String idForParameters(final OSMWay way, final OSMNode node) {
        return String.format("%d:%d", way.osm_id, node.osm_id);
    }
    public String getId() {
        return id;
    }

    public void determineScore(final RoutePathFinder parentPath, final OSMNode initialOriginNode, final OSMNode finalDestinationNode) {
        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originJunction.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");
        lineMatched = false;

        final int debugWayId = 243738297;

        //determine the direction of this line relative to the direction of route travel
        if(line.getMatchForLine(parentPath.route.routeLine) == null) {
            parentPath.logEvent(RoutePathFinder.RouteLogType.error, "No match for line " + line.way.getTag(OSMEntity.KEY_NAME) + "(" + line.way.osm_id + ")", this);
            return;
        }
        travelDirection = line.getMatchForLine(parentPath.route.routeLine).getAvgDotProduct() >= 0.0 ? TravelDirection.forward : TravelDirection.backward;

        final long routeLineId = parentPath.route.routeLine.way.osm_id;

        //determine the nodes on this path segment, and calculate their score based on their alignment with the main route's path
        final OSMNode firstNode, lastNode;
        if(travelDirection == TravelDirection.forward) { //i.e. we're traveling along the node order of this way
            firstNode = line.segments.get(0).originNode;
            lastNode = line.segments.get(line.segments.size() - 1).destinationNode;

            //determine whether we're going with or against the oneway tag on the current way
            final double directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.forward, lastNode, parentPath);

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            lastCheckedNode = firstNode; //add the first node
            boolean inSegment = false;
            final ListIterator<LineSegment> iterator = line.segments.listIterator();
            while (iterator.hasNext()) {
                final LineSegment segment = iterator.next();
                if(segment.destinationNode != null) {
                    lastCheckedNode = segment.destinationNode;
                }

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
                        System.out.println("WAY:: " + line.way.osm_id + "/SEG " + bestMatchForSegment + ": dm " + directionMultiplier + "/dp " + bestMatchForSegment.dotProduct + "/dist " + bestMatchForSegment.midPointDistance + ":::score " + pathScore);
                    }
                }

                //flag whether this PathSegment contains the origin node for the entire path
                if(segment.originNode == initialOriginNode) {
                    containsPathOriginNode = true;
                    waypointScore += SCORE_FOR_STOP_ON_WAY;
                }

                //check if we've found the final destination node on this PathSegment, or we're at a junction, and if so, bail
                if(segment.destinationNode != null) {
                    if(segment.destinationNode == finalDestinationNode) {
                        containsPathDestinationNode = true;
                        endJunction = new Junction(segment.destinationNode, this);
                        waypointScore += SCORE_FOR_STOP_ON_WAY;
                        break;
                    } else if(segment.destinationNode.containingWayCount > 1) {
                        endJunction = new Junction(segment.destinationNode, this);
                        break;
                    }
                }
            }
        } else { //i.e. we're traveling against the node order of this way
            firstNode = line.segments.get(line.segments.size() - 1).destinationNode;
            lastNode = line.segments.get(0).originNode;

            final double directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.backward, lastNode, parentPath);

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            lastCheckedNode = firstNode; //add the first node
            boolean inSegment = false;
            final ListIterator<LineSegment> iterator = line.segments.listIterator(line.segments.size());
            while (iterator.hasPrevious()) {
                final LineSegment segment = iterator.previous();

                if(segment.originNode != null) {
                    lastCheckedNode = segment.originNode;
                }

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
                if(segment.destinationNode == initialOriginNode) {
                    containsPathOriginNode = true;
                    waypointScore += SCORE_FOR_STOP_ON_WAY;
                }

                //check if we're at a junction, and if so, bail
                if(segment.originNode != null) {
                    if(segment.originNode == finalDestinationNode) {
                        containsPathDestinationNode = true;
                        endJunction = new Junction(segment.originNode, this);
                        waypointScore += SCORE_FOR_STOP_ON_WAY;
                        break;
                    } else if(segment.originNode.containingWayCount > 1) {
                        endJunction = new Junction(segment.originNode, this);
                        break;
                    }
                }
            }
        }

        //if endJunction wasn't created above, this is a dead-end PathSegment
        if(endJunction != null) {
            lineMatched = true;
            if(alignedPathLength > 0) {
                pathScore /= alignedPathLength;
            }
            if(traveledSegmentLength > 0) {
                lengthFactor = alignedPathLength / traveledSegmentLength;
            }
        } else {
            lengthFactor = 0.0;
            //endJunction = new Junction(lastNode, this);
            System.out.println("NO END JUNCTION, travelled " + traveledSegmentCount);
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
    public Junction getEndJunction() {
        return endJunction;
    }
    public String toString() {
        return "PathSegment " + line.way.getTag(OSMEntity.KEY_NAME) + " (" + line.way.osm_id + ": " + originJunction.junctionNode.osm_id + "->" + (endJunction != null ? endJunction.junctionNode.osm_id : "MatchEnd") + ")";
    }
    public WaySegments getLine() {
        return line;
    }
    public TravelDirection getTravelDirection() {
        return travelDirection;
    }
    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        final OSMNode originNode = originJunction.junctionNode, destinationNode = endJunction != null ? endJunction.junctionNode : lastCheckedNode;

        for(final WaySegments splitLine : splitWaySegments) {
            if(splitLine != line && splitLine.way.getNodes().contains(originNode) && splitLine.way.getNodes().contains(destinationNode)) {
                System.out.println("Reassigned from " + line.way.osm_id + " to " + splitLine.way.osm_id);
                line = splitLine;

                //update the lastCheckedNode value if endJunction is null, so it's not on a node that's no longer on the new line
                if(endJunction == null) {
                    lastCheckedNode = line.way.getFirstNode() == originJunction.junctionNode ? line.way.getLastNode() : line.way.getFirstNode();
                }

                //regenerate the id since the line changed
                id = idForParameters(line.way, originJunction.junctionNode);
                break;
            }
        }
    }
    @Override
    public void waySegmentsWasDeleted(final WaySegments waySegments) throws InvalidArgumentException {

    }
    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment newSegment) {

    }
}
