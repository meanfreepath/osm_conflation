package NewPathFinding;

import Conflation.*;
import OSM.*;
import com.company.Config;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
     * Takes the stop data and uses it to generate the pathTrees between the RouteLine's stops, for later path discovery
     * Must be called after the Route's stops have been matched to their respective OSM ways
     */
    public void generatePathTrees() {
        System.out.println("Route " + route.routeLine.way.osm_id + ": " + route.stops.size() + " stops");
        //Find the closest segment and point on the routeLineSegment to the stops' positions
        int pathTreeIndex = 0, endIndex = route.stops.size() - 1;
        Point closestPointOnRouteLineToPlatform, lastFoundPoint = null;
        NewPathFinding.PathTree lastLeg = null;
        Point relevantPoints[] = {null, null};
        for(final StopArea curStop: route.stops) {
            //get the closest point on the routeLine to the stop
            relevantPoints[0] = null; //reset the closest point
            determineRouteLinePoints(curStop, lastFoundPoint, route, relevantPoints);
            closestPointOnRouteLineToPlatform = relevantPoints[0];
            lastFoundPoint = relevantPoints[1];

            //cap off the previous PathTree, if any
            if(lastLeg != null) {
                lastLeg.setRouteLineEnd(closestPointOnRouteLineToPlatform);
                lastLeg.setDestinationStop(curStop);
            }

            //don't create a new path originating at the last stop on the route
            if(pathTreeIndex >= endIndex) {
                break;
            }

            //create the PathTree starting at the current stop
            final NewPathFinding.PathTree curLeg = new NewPathFinding.PathTree(route, curStop, closestPointOnRouteLineToPlatform, lastLeg, pathTreeIndex++, this);
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
    private static void determineRouteLinePoints(final StopArea curStop, final Point searchBeginPoint, final Route route, final Point[] returnPoints) {
        final OSMNode stopPosition = curStop.getStopPosition(route.routeType);
        if(stopPosition == null) {
            return;
        }

        //for the first/last stops, automatically set to the first/last segment on the routeLine (prevents issues if provided GTFS route line doesn't extend all the way to first/last stops)
        final Point stopPoint = stopPosition.getCentroid();
        RouteLineSegment closestSegment = null;
        if(route.stops.indexOf(curStop) == 0) {
            closestSegment = (RouteLineSegment) route.routeLine.segments.get(0);
        } else if(route.stops.indexOf(curStop) == route.stops.size() - 1) {
            closestSegment = (RouteLineSegment) route.routeLine.segments.get(route.routeLine.segments.size() - 1);
        } else { //otherwise, scan the routeLine, finding the closest segment to the stop platform
            double minDistance = StopArea.maxDistanceFromPlatformToWay, curDistance;
            Point closestPointOnSegment;
            boolean inSearchZone = searchBeginPoint == null;
            for(final LineSegment segment : route.routeLine.segments) {
                //start checking from the last-found point; prevents issues when routeLine passes a stop multiple times
                if(!inSearchZone && segment.originPoint == searchBeginPoint) {
                    inSearchZone = true;
                }

                if(inSearchZone) {
                    closestPointOnSegment = segment.closestPointToPoint(stopPoint);
                    curDistance = Point.distance(stopPoint, closestPointOnSegment);
                    if (curDistance < minDistance) {
                        minDistance = curDistance;
                        closestSegment = (RouteLineSegment) segment;
                    }
                }
            }

            //if still not found, bail here
            if(closestSegment == null) {
                System.out.println("WARNING: no close by routeLineSegment found for stop " + curStop);
                return;
            }
        }


        //get the closest point on the closest segment, and use it to split the it at that point
        final Point closestPointOnRouteLine = closestSegment.closestPointToPoint(stopPoint);
        final Point closestPointToPlatform;
        final double nodeTolerance = closestSegment.length / 5.0;
        //System.out.format("DEBUG: Closest point to stop %s on routeline is %s (segment %s)", curStop.getPlatform().getTag(OSMEntity.KEY_REF), closestPointOnRouteLine, closestSegment);

        //do a quick tolerance check on the LineSegment's existing points (no need to split segment if close enough to an existing point)
        if(Point.distance(closestPointOnRouteLine, closestSegment.destinationPoint) < nodeTolerance) {
            closestPointToPlatform = closestSegment.destinationPoint;
        } else if(Point.distance(closestPointOnRouteLine, closestSegment.originPoint) < nodeTolerance) {
            closestPointToPlatform = closestSegment.originPoint;
        } else {
            closestPointToPlatform = new Point(closestPointOnRouteLine.x, closestPointOnRouteLine.y);
            route.routeLine.insertPoint(closestPointToPlatform, closestSegment, 0.0);
        }

        //and set up the return values
        returnPoints[0] = closestPointToPlatform;
        returnPoints[1] = closestSegment.destinationPoint;
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
                if(lastPath.bestPath.lastPathSegment.endJunction.processStatus == Junction.JunctionProcessStatus.continuePath && lastPath.bestPath.lastPathSegment.endJunction.junctionNode == pathTree.bestPath.firstPathSegment.originNode.junctionNode) {
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
        for (final PathTree pathTree : routePathTrees) {
            if(pathTree.bestPath != null) {
                try {
                    pathTree.splitWaysAtIntersections(entitySpace, this);
                } catch (InvalidArgumentException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            } else {
                System.out.println(this + " has no bestPath, skipping splits");
            }
        }
    }
    public void addWaysToRouteRelation() {
        //and add the correct ways to the route relation
        PathSegment previousPathSegment = null;
        for(final PathTree pathTree : routePathTrees) {
            if(pathTree.bestPath == null) {
                previousPathSegment = null; //wipe so we don't try to connect ways over this PathTree's gap
                continue;
            }
            //System.out.println("OUTPUT PATH FOR " + pathTree.parentPathFinder.route.routeRelation.osm_id + "::" + pathTree.bestPath);
            for (final PathSegment pathSegment : pathTree.bestPath.getPathSegments()) {
                final OSMWay pathWay = pathSegment.getLine().way;
                //double-check the current and previous PathSegments are connected to each other
                if(previousPathSegment != null && previousPathSegment.getEndNode() != pathSegment.getOriginNode()) {
                    System.err.println("PathSegments don't connect:: " + previousPathSegment +"//" + pathSegment);
                }

                //and add to the route relation if its way doesn't match the previous PathSegment's way
                if(previousPathSegment == null || previousPathSegment.getLine().way != pathWay) {
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
     * @param entitySpace the entity space the debug elements will be added to
     */
    public void debugOutputPaths(final OSMEntitySpace entitySpace) {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());

        int pathIndex = 0;
        for(final PathTree pathTree : routePathTrees) {
            pathTree.debugOutputPaths(segmentSpace, pathIndex++);
        }

        try {
            segmentSpace.outputXml(Config.sharedInstance.debugDirectory + "/pathdebug" + route.routeRelation.osm_id + ".osm");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @Override
    public String toString() {
        return String.format("RoutePathFinder for route %s", route);
    }
}
