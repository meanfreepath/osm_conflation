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
    public final List<PathTree> allPathTrees;
    public final List<Path> calculatedPaths;
    public final HashMap<Long, WaySegments> candidateLines;
    private int successfulPaths = 0, failedPaths = 0;

    public enum RouteLogType {
        info, notice, warning, error
    }
    private class RouteLog {
        public final RouteLogType type;
        public final String message;
        public final Date logTime;
        public final Object sender;
        public RouteLog(final RouteLogType type, final String message, final Object sender) {
            this.type = type;
            this.message = message;
            this.sender = sender;
            logTime = new Date();
            System.out.println(logTime.toString() + ": " + type.name() + ": " + message);
        }
    }
    public final List<RouteLog> logs = new ArrayList<>(128);

    public RoutePath(final Route route, final HashMap<Long, WaySegments> candidateLines) {
        this.route = route;
        this.candidateLines = candidateLines;
        allPathTrees = new ArrayList<>(route.stops.size() + 1);
        calculatedPaths = new ArrayList<>(allPathTrees.size());

        //get the stop matches from the route, creating a new
        StopArea lastStop = null;
        for(final StopArea curStop : route.stops) {
            if(lastStop != null) {
                allPathTrees.add(new PathTree(lastStop, curStop));
            }
            lastStop = curStop;
        }
    }
    public void findPaths() {
        //start the pathfinding code for each stop pair (TODO: may be able to multithread)
        for(final PathTree pathTree : allPathTrees) {
            try {
                pathTree.findPaths(this, route.routeLine);
            }catch (Exception e) {
                e.printStackTrace();
            }

            //break;
        }

        //and consolidate into a single path
        PathTree lastPath = null;
        successfulPaths = failedPaths = 0;
        for(final PathTree pathTree : allPathTrees) {
            final Path bestPath = pathTree.bestPath;
            if(bestPath == null) {
                logEvent(RouteLogType.warning, "NO PATH found between " + pathTree.fromStop.platform.getTag(OSMEntity.KEY_NAME) + " and " + pathTree.toStop.platform.getTag(OSMEntity.KEY_NAME), this);
                lastPath = null;
                for (final RouteLog event : eventLogsForObject(pathTree, null)) {
                    System.out.println("\t" + event.message);
                }
                failedPaths++;
                continue;
            } else {
                successfulPaths++;
                logEvent(RouteLogType.info, "SUCCESSFUL PATH found between " + pathTree.fromStop.platform.getTag(OSMEntity.KEY_NAME) + " and " + pathTree.toStop.platform.getTag(OSMEntity.KEY_NAME), this);
            }

            for(final OSMWay way : bestPath.getPathWays()) {
                if(!route.routeRelation.containsMember(way)) {
                    route.routeRelation.addMember(way, "");
                }
            }

            //check the paths are connected (start/end at same node)
            if(lastPath != null) {
                if(lastPath.bestPath.lastPathSegment.getEndJunction().junctionNode == pathTree.bestPath.firstPathSegment.originJunction.junctionNode) {
                    calculatedPaths.add(pathTree.bestPath);
                } else {
                    logEvent(RouteLogType.warning, "Ends don't match for paths (" + lastPath.bestPath + ") and (" + pathTree.bestPath + ")", this);
                }
            } else {
                calculatedPaths.add(pathTree.bestPath);
            }

            lastPath = pathTree;
        }

        //split ways as needed


        //and add the correct ways to the route relation
    }
    public void logEvent(final RouteLogType type, final String message, final Object sender) {
        logs.add(new RouteLog(type, message, sender));
    }
    public List<RouteLog> eventLogsForObject(final Object object, final RouteLogType types[]) {
        final List<RouteLog> matchingLogs = new ArrayList<>(logs.size());
        for(final RouteLog log : logs) {
            if(log.sender == object) {
                if(types == null) {
                    matchingLogs.add(log);
                } else {
                    for(final RouteLogType type : types) {
                        if(log.type == type) {
                            matchingLogs.add(log);
                        }
                    }
                }
            }
        }
        return matchingLogs;
    }
    public int getSuccessfulPaths() {
        return successfulPaths;
    }
    public int getFailedPaths() {
        return failedPaths;
    }
}
