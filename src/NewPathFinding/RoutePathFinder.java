package NewPathFinding;

import Conflation.Route;
import Conflation.RouteConflator;
import Conflation.RouteLineSegment;
import Conflation.StopArea;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMWay;
import OSM.Point;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.*;

/**
 * Contains all the stop-to-stop paths for a route
 * Created by nick on 1/27/16.
 */
public class RoutePathFinder {
    public final Route route;
    public final List<PathTree> routePathTrees;
    public final List<Path> calculatedPaths;
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
            //System.out.println(logTime.toString() + ": " + type.name() + ": " + message);
        }
    }
    public final List<RouteLog> logs = new ArrayList<>(128);

    public RoutePathFinder(final Route route) {
        this.route = route;
        routePathTrees = new ArrayList<>(route.stops.size() + 1);
        calculatedPaths = new ArrayList<>(routePathTrees.size());
    }
    /**
     * Takes the stop data and uses it to split the RouteLine into route legs, for later path discovery
     * Must be called after the Route's stops have been matched to their respective OSM ways
     */
    public void splitRouteLineByStops() {
        final HashMap<String, String> debugTags = new HashMap<>(2);
        debugTags.put(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_STOP_POSITION);
        debugTags.put(OSMEntity.KEY_BUS, OSMEntity.TAG_YES);

        System.out.println("Route " + route.routeLine.way.osm_id + ": " + route.stops.size() + " stops");
        //Find the closest segment and point on the routeLineSegment to the stops' positions
        int pathTreeIndex = 0, endIndex = route.stops.size() - 1;
        Point closestPointOnRouteLine;
        NewPathFinding.PathTree lastLeg = null;
        for(final StopArea curStop: route.stops) {
            //get the closest point on the routeLine to the stop
            closestPointOnRouteLine = determineRouteLinePoints(curStop, route);

            //cap off the previous PathTree, if any
            if(lastLeg != null) {
                lastLeg.setDestinationStop(curStop);
                lastLeg.setRouteLineEnd(closestPointOnRouteLine);
            }

            //don't create a new path originating at the last stop on the route
            if(pathTreeIndex >= endIndex) {
                break;
            }

            //create the PathTree starting at the current stop
            final NewPathFinding.PathTree curLeg = new NewPathFinding.PathTree(route.routeLine, curStop, closestPointOnRouteLine, lastLeg, pathTreeIndex++, this);
            routePathTrees.add(curLeg);
            if(lastLeg != null) {
                lastLeg.nextPathTree = curLeg;
            }

            lastLeg = curLeg;
        }

        //and do some final processing on the PathTrees to get them ready for the path matching stage
        for(final NewPathFinding.PathTree routeLeg : routePathTrees) {
            routeLeg.compileRouteLineSegments();
        }
    }
    private static Point determineRouteLinePoints(final StopArea curStop, final Route route) {
        if(curStop.getStopPosition() != null) {
            final Point stopPoint = curStop.getStopPosition().getCentroid();
            RouteLineSegment closestSegment = (RouteLineSegment) route.routeLine.closestSegmentToPoint(stopPoint, StopArea.maxDistanceFromPlatformToWay);

            //no closest segment found (may happen if provided GTFS route line doesn't extend all the way to first/last stops)
            if(closestSegment == null) {
                if(route.stops.indexOf(curStop) == 0) {
                    closestSegment = (RouteLineSegment) route.routeLine.segments.get(0);
                } else if(route.stops.indexOf(curStop) == route.stops.size() - 1) {
                    closestSegment = (RouteLineSegment) route.routeLine.segments.get(route.routeLine.segments.size() - 1);
                } else {
                    System.out.println("WARNING: no close by routeLineSegment found for stop " + curStop);
                    return null;
                }
            }

            //get the closest point on the closest segment, and use it to split the it at that point
            final Point closestPointOnRouteLine = closestSegment.closestPointToPoint(stopPoint);
            final Point closestPointToPlatform;
            final double nodeTolerance = closestSegment.length / 5.0;

            //do a quick tolerance check on the LineSegment's existing points (no need to split segment if close enough to an existing point)
            if(Point.distance(closestPointOnRouteLine, closestSegment.destinationPoint) < nodeTolerance) {
                closestPointToPlatform = closestSegment.destinationPoint;
            } else if(Point.distance(closestPointOnRouteLine, closestSegment.originPoint) < nodeTolerance) {
                closestPointToPlatform = closestSegment.originPoint;
            } else {
                closestPointToPlatform = new Point(closestPointOnRouteLine.x, closestPointOnRouteLine.y);
                route.routeLine.insertPoint(closestPointToPlatform, closestSegment, 0.0);
            }
            return closestPointToPlatform;
        }
        return null;
    }

    public void findPaths(final RouteConflator routeConflator) {
        for(final PathTree pathTree : routePathTrees) {
            pathTree.findPaths(routeConflator);
        }


        /*PathSegment.clearPathSegmentCache();

        //start the pathfinding code for each stop pair (TODO: may be able to multithread)
        for(final PathTree pathTree : routePathTrees) {
            pathTree.findPaths(this);
        }

        //and consolidate into a single path
        PathTree lastPath = null;
        successfulPaths = failedPaths = 0;
        for(final PathTree pathTree : routePathTrees) {
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
            }*

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
        }*/
    }
    public void splitWaysAtIntersections(final OSMEntitySpace entitySpace) {
        //split ways as needed
        /*Path previousPath = null;
        for (final PathTree pathTree : routePathTrees) {
            if (pathTree.bestPath == null) {
                previousPath = null;
                continue;
            }
            try {
                previousPath = pathTree.splitWaysAtIntersections(entitySpace, previousPath, this);
            } catch (InvalidArgumentException e) {
                e.printStackTrace();
            }
        }*/
    }
    public void addWaysToRouteRelation() {
        //and add the correct ways to the route relation
        for(final PathTree pathTree : routePathTrees) {
            if(pathTree.bestPath == null) {
                continue;
            }
            //System.out.println("OUTPUT PATH FOR " + pathTree.parentPathFinder.route.routeRelation.osm_id + "::" + pathTree.bestPath);
            PathSegment previousPathSegment = null;
            for (final PathSegment pathSegment : pathTree.bestPath.getPathSegments()) {
                final OSMWay pathWay = pathSegment.getLine().way;
                //double-check the current and previous PathSegments are connected to each other
                if(previousPathSegment != null && previousPathSegment.getEndJunction().junctionNode != pathSegment.getOriginJunction().junctionNode) {
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
        for(final PathTree pathTree : routePathTrees) {
            pathTree.debugOutputPaths(segmentSpace, pathIndex++);
        }

        try {
            segmentSpace.outputXml("pathdebug" + route.routeRelation.osm_id + ".osm");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
