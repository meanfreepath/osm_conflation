package PathFinding;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMWay;
import com.company.InvalidArgumentException;

import java.io.IOException;
import java.util.*;

/**
 * Contains all the stop-to-stop paths for a route
 * Created by nick on 1/27/16.
 */
public class RoutePathFinder implements WaySegmentsObserver {
    public final Route route;
    public final List<PathTree> allPathTrees;
    public final List<Path> calculatedPaths;
    public final HashMap<Long, OSMWaySegments> candidateLines;
    private int successfulPaths = 0, failedPaths = 0;
    protected final Map<String, List<String>> requiredWayTags;

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
            //System.out.println(logTime.toString() + ": " + type.name() + ": " + message);
        }
    }
    public final List<RouteLog> logs = new ArrayList<>(128);

    public RoutePathFinder(final Route route, final HashMap<Long, OSMWaySegments> candidateLines, final Map<String, List<String>> requiredWayTags) {
        this.route = route;
        this.candidateLines = candidateLines;
        this.requiredWayTags = requiredWayTags;
        allPathTrees = new ArrayList<>(route.stops.size() + 1);
        calculatedPaths = new ArrayList<>(allPathTrees.size());

        //get the stop matches from the route, creating a new
        StopArea lastStop = null;
        PathTree lastPathTree = null;
        for(final StopArea curStop : route.stops) {
            PathTree pathTree = null;
            if(lastStop != null) {
                pathTree = new PathTree(lastStop, curStop, lastPathTree, this);
                allPathTrees.add(pathTree);
            }
            if(lastPathTree != null) {
                lastPathTree.nextPathTree = pathTree;
            }
            lastStop = curStop;
            lastPathTree = pathTree;
        }
    }
    public void findPaths(final OSMEntitySpace entitySpace) {
        PathSegment.clearPathSegmentCache();

        //start the pathfinding code for each stop pair (TODO: may be able to multithread)
        for(final PathTree pathTree : allPathTrees) {
            pathTree.findPaths(this);
        }

        //and consolidate into a single path
        PathTree lastPath = null;
        successfulPaths = failedPaths = 0;
        for(final PathTree pathTree : allPathTrees) {
            if(pathTree.bestPath == null) {
                logEvent(RouteLogType.warning, "NO PATH found between " + pathTree.fromStop + " and " + pathTree.toStop, this);
                lastPath = null;
                for (final RouteLog event : eventLogsForObject(pathTree, null)) {
                    System.out.println("\t" + event.message);
                }
                failedPaths++;
                continue;
            } else {
                successfulPaths++;
                logEvent(RouteLogType.info, "SUCCESSFUL PATH found between " + pathTree.fromStop + " and " + pathTree.toStop, this);
            }

            /*for(final OSMWay way : bestPath.getPathWays()) {
                if(!route.routeRelation.containsMember(way)) {
                    route.routeRelation.addMember(way, "");
                }
            }*/

            //check the paths are connected (start/end at same node)
            if(lastPath != null) {
                if(lastPath.bestPath.lastPathSegment.endJunction.processStatus == Junction.JunctionProcessStatus.continuePath && lastPath.bestPath.lastPathSegment.endJunction.junctionNode == pathTree.bestPath.firstPathSegment.originJunction.junctionNode) {
                    calculatedPaths.add(pathTree.bestPath);
                } else {
                    logEvent(RouteLogType.warning, "Ends don't match for paths (" + lastPath.bestPath + ") and (" + pathTree.bestPath + ")", this);
                }
            } else {
                calculatedPaths.add(pathTree.bestPath);
            }

            lastPath = pathTree;
        }
    }
    public void splitWaysAtIntersections(final OSMEntitySpace entitySpace) {
        //split ways as needed
        Path previousPath = null;
        for (final PathTree pathTree : allPathTrees) {
            if (pathTree.bestPath == null) {
                previousPath = null;
                continue;
            }
            try {
                previousPath = pathTree.splitWaysAtIntersections(entitySpace, previousPath, this);
            } catch (InvalidArgumentException e) {
                e.printStackTrace();
            }
        }
    }
    public void addWaysToRouteRelation() {
        //and add the correct ways to the route relation
        for(final PathTree pathTree : allPathTrees) {
            if(pathTree.bestPath == null) {
                continue;
            }
            //System.out.println("OUTPUT PATH FOR " + pathTree.parentPathFinder.route.routeRelation.osm_id + "::" + pathTree.bestPath);
            PathSegment previousPathSegment = null;
            for (final PathSegment pathSegment : pathTree.bestPath.getPathSegments()) {
                final OSMWay pathWay = pathSegment.getLine().way;
                //double-check the current and previous PathSegments are connected to each other
                if(previousPathSegment != null && previousPathSegment.endJunction.junctionNode != pathSegment.originJunction.junctionNode) {
                    System.err.println("PathSegments don't connect:: " + previousPathSegment +"//" + pathSegment);
                }
                if (!route.routeRelation.containsMember(pathWay)) {
                    //System.out.println(pathSegment + ": USED " + pathWay.getTag("name") + "(" + pathWay.osm_id + ")");
                    route.routeRelation.addMember(pathWay, OSMEntity.MEMBERSHIP_DEFAULT);
                }
                previousPathSegment = pathSegment;
            }
        }
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

    /**
     * Outputs the paths to an OSM xml file for debugging purposes
     * @param entitySpace
     */
    public void debugOutputPaths(final OSMEntitySpace entitySpace) {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());

        int pathIndex = 0;
        for(final PathTree pathTree : allPathTrees) {
            pathTree.debugOutputPaths(segmentSpace, pathIndex++);
        }

        try {
            segmentSpace.outputXml("pathdebug" + route.routeRelation.osm_id + ".osm");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        for(final WaySegments ws : splitWaySegments) {
            if(ws != originalWaySegments) {
                candidateLines.put(ws.way.osm_id, (OSMWaySegments) ws);
            }
        }
    }
    @Override
    public void waySegmentsWasDeleted(final WaySegments waySegments) {
        candidateLines.remove(waySegments.way.osm_id);
    }
    @Override
    public void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment oldSegment, final LineSegment[] newSegments) {

    }
}
