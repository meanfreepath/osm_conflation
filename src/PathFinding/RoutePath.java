package PathFinding;

import Conflation.Route;
import Conflation.StopArea;
import Conflation.WaySegments;
import OSM.OSMEntity;
import OSM.OSMWay;

import java.util.*;

/**
 * Contains all the stop-to-stop paths for a route
 * Created by nick on 1/27/16.
 */
public class RoutePath {
    public final Route route;
    public final List<WaypointPath> allPaths;
    public final List<Path> calculatedPaths;
    public final WaySegments routeLine;
    public final HashMap<Long, WaySegments> candidateLines;

    public enum RouteLogType {
        info, notice, warning, error
    }
    private class RouteLog {
        public final RouteLogType type;
        public final String message;
        public final Date logTime;
        public RouteLog(final RouteLogType type, final String message, final Object sender) {
            this.type = type;
            this.message = message;
            logTime = new Date();
            System.out.println(logTime.toString() + ": " + type.name() + ": " + message);
        }
    }
    public final List<RouteLog> logs = new ArrayList<>(128);

    public RoutePath(final Route route, final HashMap<Long, WaySegments> candidateLines) {
        this.route = route;
        routeLine = route.routeLine;
        this.candidateLines = candidateLines;
        allPaths = new ArrayList<>(route.stops.size() + 1);
        calculatedPaths = new ArrayList<>(allPaths.size());

        //get the stop matches from the route, creating a new
        StopArea lastStop = null;
        for(final StopArea curStop : route.stops) {
            if(lastStop != null) {
                allPaths.add(new WaypointPath(lastStop, curStop));
            }
            lastStop = curStop;
        }
    }
    public void findPaths() {
        //start the pathfinding code for each stop pair (TODO: may be able to multithread)
        for(final WaypointPath waypointPath : allPaths) {
            try {
                waypointPath.findPaths(this);
            }catch (Exception e) {
                e.printStackTrace();
            }

            //break;
        }

        //and consolidate into a single path
        WaypointPath lastPath = null;
        for(final WaypointPath waypointPath : allPaths) {
            final Path bestPath = waypointPath.bestPathTree.bestPath;
            if(bestPath == null) {
                logEvent(RouteLogType.warning, "No path found between " + waypointPath.fromStop.platform.getTag(OSMEntity.KEY_NAME) + " and " + waypointPath.toStop.platform.getTag(OSMEntity.KEY_NAME), this);
                continue;
            } else {
                logEvent(RouteLogType.info, "SUCCESSFUL PATH found between " + waypointPath.fromStop.platform.getTag(OSMEntity.KEY_NAME) + " and " + waypointPath.toStop.platform.getTag(OSMEntity.KEY_NAME), this);
            }

            for(final OSMWay way : bestPath.getPathWays()) {
                route.routeRelation.addMember(way, "");
            }

            //check the paths are connected (start/end at same node)
            if(lastPath != null) {
                if(lastPath.bestPathTree.bestPath.lastPathSegment.getEndJunction().junctionNode == waypointPath.bestPathTree.bestPath.firstPathSegment.originJunction.junctionNode) {
                    calculatedPaths.add(waypointPath.bestPathTree.bestPath);
                } else {
                    logEvent(RouteLogType.warning, "Ends don't match for paths (" + lastPath + ") and (" + waypointPath + ")", this);
                }
            } else {
                calculatedPaths.add(waypointPath.bestPathTree.bestPath);
            }

            lastPath = waypointPath;
        }

        //split ways as needed


        //and add the correct ways to the route relation
    }
    public void logEvent(final RouteLogType type, final String message, final Object sender) {
        logs.add(new RouteLog(type, message, sender));
    }
}
