package NewPathFinding;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 10/12/16.
 */
public class PathTree {
    public final static short matchStatusNone = 0, matchStatusFromStop = 1, matchStatusToStop = 2, matchStatusFromRouteLineNode = 4, getMatchStatusToRouteLineNode = 8;
    public final static short matchMaskAll = matchStatusFromStop | matchStatusToStop | matchStatusFromRouteLineNode | getMatchStatusToRouteLineNode;

    public final RouteLineWaySegments routeLine;
    public final PathTree previousPathTree;
    public final StopArea fromStop;
    public StopArea toStop = null;
    public Point fromRouteLinePoint = null, toRouteLinePoint = null;
    public short matchStatus;
    public List<RouteLineSegment> routeLineSegments = null;

    public static String idForParameters(final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PT:%d:%d", fromNode.osm_id, toNode.osm_id);
    }
    public PathTree(final RouteLineWaySegments routeLine, final StopArea fromStop, final PathTree previousPath) {//, final RoutePathFinder parentPathFinder) {
        this.routeLine = routeLine;
        this.fromStop = fromStop;
        this.previousPathTree = previousPath;
        matchStatus = matchStatusFromStop;
        //this.parentPathFinder = parentPathFinder;
        //fromLine = fromStop.bestWayMatch.line;
        //toLine = toStop.bestWayMatch.line;

        //the maximum path length is a multiple of the as-the-crow-flies distance between the from/to nodes, to cut down on huge path detours
        //maxPathLength = Point.distance(fromNode.getCentroid(), toNode.getCentroid()) * MAX_PATH_LENGTH_FACTOR;

        //fromLine.addObserver(this);
        //toLine.addObserver(this);
    }
    public void compileRouteLineSegments() {
        if(matchStatus != matchMaskAll) {
            return;
        }
        routeLineSegments = new ArrayList<>(512);
        boolean inLeg = false;
        for(final LineSegment segment : routeLine.segments) {
            //check if we've reached the beginning point of the leg
            if(!inLeg && segment.originPoint == fromRouteLinePoint) {
                inLeg = true;
            }

            if(inLeg) {
                routeLineSegments.add((RouteLineSegment) segment);
                if(segment.destinationPoint == toRouteLinePoint) {
                    break;
                }
            }
        }
    }
    public void setToStop(final StopArea stop) {
        if((matchStatus & matchStatusToStop) != 0) {
            throw new IllegalStateException("To stop is already set");
        }
        matchStatus |= matchStatusToStop;
        this.toStop = stop;
    }
    public void setRouteLineStart(final Point start) {
        if((matchStatus & matchStatusFromRouteLineNode) != 0) {
            throw new IllegalStateException("From node is already set");
        }
        matchStatus |= matchStatusFromRouteLineNode;
        this.fromRouteLinePoint = start;
    }
    public void setRouteLineEnd(final Point end) {
        if((matchStatus & getMatchStatusToRouteLineNode) != 0) {
            throw new IllegalStateException("From node is already set");
        }
        matchStatus |= getMatchStatusToRouteLineNode;
        this.toRouteLinePoint = end;
    }
    @Override
    public String toString() {
        return String.format("Path from %s to %s: status %d, %s segments", fromStop.platform.getTag(OSMEntity.KEY_NAME), toStop.platform.getTag(OSMEntity.KEY_NAME), matchStatus, routeLineSegments != null ? Integer.toString(routeLineSegments.size()) : "N/A");
    }
}
