package NewPathFinding;

import Conflation.OSMWaySegments;
import Conflation.RouteConflator;
import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 10/20/16.
 */
public class Junction {
    public final OSMNode junctionNode;
    //public final List<PathSegment> junctionPathSegments;

    public Junction(final OSMNode node) {
        junctionNode = node;
        //junctionPathSegments = new ArrayList<>(node.containingWayCount);
    }
    /*public void addPathSegment(final PathSegment pathSegment) {
        junctionPathSegments.add(pathSegment);
    }*/
    protected List<PathSegment> determineOutgoingPathSegments(final RouteConflator routeConflator, final PathSegment incomingPathSegment, final PathTree parentPathTree) {
        final List<PathSegment> divergingPathSegments = new ArrayList<>(junctionNode.containingWayCount - 1);

        for(final OSMWay containingWay : junctionNode.containingWays.values()) {
            final OSMWaySegments line = routeConflator.getCandidateLines().get(containingWay.osm_id);
            if(line == null) {
                System.out.println("ERROR: no WaySegments found for " + containingWay);
                continue;
            }

            //determine the travel direction (relative to the way) that will take us AWAY from the previous endJunction
            if(incomingPathSegment == null || line.way.osm_id != incomingPathSegment.getLine().way.osm_id) { //i.e. first junction on a PathTree, or transitioning to a new line
                //if the junction is an ending point for the way, create a single diverging PathSegments, traveling away from the junction
                if (junctionNode == containingWay.getFirstNode()) { //junction is first node on way: just travel forward
                    checkCreateNewPathSegment(line, this, PathSegment.TravelDirection.forward, parentPathTree, divergingPathSegments);
                } else if (junctionNode == containingWay.getLastNode()) { //junction is last node on way: just travel backward
                    checkCreateNewPathSegment(line, this, PathSegment.TravelDirection.backward, parentPathTree, divergingPathSegments);
                } else { //if the junction is a midpoint for the way, create 2 PathSegments, one for each possible direction
                    checkCreateNewPathSegment(line, this, PathSegment.TravelDirection.forward, parentPathTree, divergingPathSegments);
                    checkCreateNewPathSegment(line, this, PathSegment.TravelDirection.backward, parentPathTree, divergingPathSegments);
                }
            } else if (line.way.osm_id == incomingPathSegment.getLine().way.osm_id) {
                //if the junction is in the middle of the incomingPathSegment's containing way, create a new one beginning at the junction and continuing in the same direction
                if (junctionNode != containingWay.getFirstNode() && junctionNode != containingWay.getLastNode()) {
                    checkCreateNewPathSegment(line, this, incomingPathSegment.travelDirection, parentPathTree, divergingPathSegments);
                }
            }
        }
        return divergingPathSegments;
    }
    private static void checkCreateNewPathSegment(final OSMWaySegments line, final Junction originJunction, final PathSegment.TravelDirection travelDirection, final PathTree parentPathTree, final List<PathSegment> divergingPathSegments) {
        //TODO implement filtering?
        //if the line has a decent SegmentMatch

        //and if we travel it we're OK with the future Vector

        //then add it to the list
        divergingPathSegments.add(PathSegment.createNewPathSegment(line, originJunction, travelDirection, parentPathTree));
    }
}
