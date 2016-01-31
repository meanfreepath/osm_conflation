package PathFinding;

import Conflation.LineSegment;
import Conflation.StopArea;
import Conflation.StopWayMatch;
import Conflation.WaySegments;
import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Represents the portion of a way that is traversed by a route's path
 * Created by nick on 1/27/16.
 */
public class PathSegment {
    private final static double SCORE_FOR_DETOUR = -40.0, SCORE_FOR_STOP_ON_WAY = 100.0, SCORE_FOR_ALIGNMENT = 1.0, SCORE_FOR_TRAVEL_DIRECTION = 50.0, SCORE_FACTOR_FOR_CORRECT_ONEWAY_TRAVEL = 2.0, SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL = 0.5,  SCORE_FACTOR_FOR_NON_ONEWAY_TRAVEL = 1.0;
    public final Junction originJunction;
    private Junction endJunction;
    public final WaySegments line;
    public final String id;
    public double pathScore, waypointScore;
    public double alignedPathLength = 0.0; //the length of segments this path aligns with
    private boolean processed = false;
    private boolean containsPathOriginNode = false, containsPathDestinationNode = false;

    public PathSegment(final WaySegments line, final Junction fromJunction) {
        this.line = line;
        originJunction = fromJunction;

        //generate a unique ID for this PathSegment
        id = idForParameters(line.way, originJunction.junctionNode);

        pathScore = waypointScore = 0.0;
    }
    public static String idForParameters(final OSMWay way, final OSMNode node) {
        return String.format("%d:%d", way.osm_id, node.osm_id);
    }

    public void determineScore(final RoutePath parentPath, final OSMNode initialOriginNode, final OSMNode finalDestinationNode) {
        //System.out.println("Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): from " + originJunction.junctionNode.osm_id + " (traveling " + (line.matchObject.getAvgDotProduct() >= 0.0 ? "forward" : "backward") + ")");
        if(processed) {
            System.out.println("ALREADY PROCEEDED");
            return;
        }

        //determine the direction of this line relative to the direction of route travel
        final List<OSMNode> segmentNodes = new ArrayList<>(line.way.getNodes().size());
        final boolean ourDirectionForward = line.matchObject.getAvgDotProduct() >= 0.0;

        //determine the nodes on this path segment, and calculate their score based on their alignment with the main route's path
        final OSMNode firstNode, lastNode;
        if(ourDirectionForward) { //i.e. we're traveling along the node order of this way
            firstNode = line.segments.get(0).originNode;
            lastNode = line.segments.get(line.segments.size() - 1).destinationNode;

            //determine whether we're going with or against the oneway tag on the current way
            final double directionMultiplier = calculateDirectionMultiplier(WaySegments.OneWayDirection.forward, lastNode, parentPath);

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            segmentNodes.add(firstNode); //add the first node
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
                //System.out.println("segment " + line.way.getTag("name") + ":" + (segment.originNode != null? segment.originNode.osm_id : "N/A") + "/" + (segment.destinationNode != null? segment.destinationNode.osm_id + " - " + segment.destinationNode.containingWays.size() + " containing ways" : "N/A"));

                //sum up the scode
                if(segment.bestMatch != null) {
                    pathScore += directionMultiplier * SCORE_FOR_ALIGNMENT * Math.abs(segment.bestMatch.dotProduct) / segment.bestMatch.midPointDistance;
                    alignedPathLength += segment.length;
                }

                //flag whether this PathSegment contains the origin node for the entire path
                if(segment.originNode == initialOriginNode) {
                    containsPathOriginNode = true;
                    waypointScore += SCORE_FOR_STOP_ON_WAY;
                }

                //check if we've found the final destination node on this PathSegment, or we're at a junction, and if so, bail
                if(segment.destinationNode != null) {
                    segmentNodes.add(segment.destinationNode);
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
            segmentNodes.add(firstNode); //add the first node
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

                //System.out.println("segment " + line.way.getTag("name") + ":" + (segment.destinationNode != null? segment.destinationNode.osm_id : "N/A") + "/" + (segment.originNode != null? segment.originNode.osm_id : "N/A"));
                if(segment.bestMatch != null) {
                    pathScore += directionMultiplier * SCORE_FOR_ALIGNMENT * Math.abs(segment.bestMatch.dotProduct) / segment.bestMatch.midPointDistance;
                    alignedPathLength += segment.length;
                }

                //flag whether this PathSegment contains the origin node for the entire path
                if(segment.destinationNode == initialOriginNode) {
                    containsPathOriginNode = true;
                    waypointScore += SCORE_FOR_STOP_ON_WAY;
                }

                //check if we're at a junction, and if so, bail
                if(segment.originNode != null) {
                    segmentNodes.add(segment.originNode);

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
        if(endJunction == null) {
            endJunction = new Junction(lastNode, this);
        }

        //System.out.println("PROCESSED Segment of " + line.way.getTag("name") + " (" + line.way.osm_id + "): " + originJunction.junctionNode.osm_id + "->" + endJunction.junctionNode.osm_id);

        //if this segment is the root segment of a Path, default its originating node to the "first" (based on our direction of travel) node on the path
        /*if(parentPathSegment == null && originatingNode == null) {
            originatingNode = firstNode;
        }*/
        processed = true;
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
    private double calculateDirectionMultiplier(final WaySegments.OneWayDirection travelDirection, final OSMNode originatingNodeForTravel, final RoutePath parentPath) {
        final double directionMultiplier;
        final boolean wayIsOneWay = line.oneWayDirection != WaySegments.OneWayDirection.none;
        if(wayIsOneWay) {
            if(line.oneWayDirection != travelDirection) { //if a oneway is present, and runs counter our direction of travel
                parentPath.logEvent(RoutePath.RouteLogType.notice, "PATH BLOCKS ENTRY TO " + line.way.getTag("name") + " (" + line.way.osm_id + "): " + line.oneWayDirection.name() + "/" + travelDirection.name(), this);
                directionMultiplier = SCORE_FACTOR_FOR_INCORRECT_ONEWAY_TRAVEL;
            } else if(originJunction.junctionNode == originatingNodeForTravel) { //if entering from the originating node would cause us to go counter to the oneway, also bail
                parentPath.logEvent(RoutePath.RouteLogType.notice, "REVPATH BLOCKS ENTRY TO " + line.way.getTag("name") + " (" + line.way.osm_id + ")", this);
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
        return pathScore + waypointScore;
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
}
