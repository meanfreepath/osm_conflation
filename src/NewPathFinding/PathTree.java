package NewPathFinding;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import OSM.Point;

import java.util.*;

/**
 * Created by nick on 10/12/16.
 */
public class PathTree {
    public final static short matchStatusNone = 0, matchStatusFromStop = 1, matchStatusToStop = 2, matchStatusFromRouteLineNode = 4, getMatchStatusToRouteLineNode = 8;
    public final static short matchMaskAll = matchStatusFromStop | matchStatusToStop | matchStatusFromRouteLineNode | getMatchStatusToRouteLineNode;
    public final static int MAX_PATHS_TO_CONSIDER = 320;
    private final static short NUMBER_OF_FUTURE_SEGMENTS = 5;

    public final RouteLineWaySegments routeLine;
    public final PathTree previousPathTree;
    public final StopArea originStop;
    public StopArea destinationStop = null;
    public Point fromRouteLinePoint = null, toRouteLinePoint = null;
    public short matchStatus;
    public List<RouteLineSegment> routeLineSegments = null;

    public final RoutePathFinder parentPathFinder;
    public final List<Path> candidatePaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public final List<Path> successfulPaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    private final Map<Long, Junction> junctions = new HashMap<>(MAX_PATHS_TO_CONSIDER * 8);
    public Path bestPath = null;
    public static boolean debugEnabled = false;

    public static String idForParameters(final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PT:%d:%d", fromNode.osm_id, toNode.osm_id);
    }
    public PathTree(final RouteLineWaySegments routeLine, final StopArea originStop, final PathTree previousPath, final RoutePathFinder parentPathFinder) {
        this.routeLine = routeLine;
        this.originStop = originStop;
        this.previousPathTree = previousPath;
        matchStatus = matchStatusFromStop;
        this.parentPathFinder = parentPathFinder;

        //the maximum path length is a multiple of the as-the-crow-flies distance between the from/to nodes, to cut down on huge path detours
        //maxPathLength = Point.distance(fromNode.getCentroid(), toNode.getCentroid()) * MAX_PATH_LENGTH_FACTOR;

        //fromLine.addObserver(this);
        //toLine.addObserver(this);
    }
    public void findPaths(final RouteConflator routeConflator) {
        //make sure we have enough of the required information to match this path
        System.out.print("\n\nCheck find path for " + originStop.getPlatform().getTag(OSMEntity.KEY_NAME) + " -> " + destinationStop.getPlatform().getTag(OSMEntity.KEY_NAME) + ": ");
        if(matchStatus != matchMaskAll) {
            System.out.println("FAILED: insufficient match (mask is " + matchStatus + ")");
            return;
        }
        System.out.println("running now…");

        //set up the initial Junction
        final Junction originJunction = createJunction(originStop.getStopPosition());

        //then determine the relative travel direction on the StopArea's stopPosition's way
        final LineMatch lineMatchForBestStopWayMatch = routeLine.getMatchForLine(originStop.bestWayMatch.line);
        final PathSegment.TravelDirection travelDirection = lineMatchForBestStopWayMatch.getAvgDotProduct() > 0.0 ? PathSegment.TravelDirection.forward : PathSegment.TravelDirection.backward;

        //and init the first PathSegment and containing Path for the segment
        final PathSegment originPathSegment = PathSegment.createNewPathSegment(originStop.bestWayMatch.line, originJunction, travelDirection, this);
        final Path initialPath = new Path(this, originPathSegment);
        candidatePaths.add(initialPath);

        //prepopulate the future segments to use in the futureVector calculation
        final ArrayList<RouteLineSegment> routeLineSegmentsToConsider = new ArrayList<>(NUMBER_OF_FUTURE_SEGMENTS);
        short curIdx = 0;
        for(final RouteLineSegment futureSegment : routeLineSegments) {
            routeLineSegmentsToConsider.add(futureSegment);
            if(++curIdx >= NUMBER_OF_FUTURE_SEGMENTS) {
                break;
            }
        }

        final long debugRlSegIdsRaw[] = {85303924L};
        final ArrayList<Long> debugRlSegIds = new ArrayList(debugRlSegIdsRaw.length);
        for(final long rlId : debugRlSegIdsRaw) {
            debugRlSegIds.add(rlId);
        }

        int segmentIndex = 1, futureSegmentIndex = NUMBER_OF_FUTURE_SEGMENTS;
        final int segmentCount = routeLineSegments.size();
        final Iterator<RouteLineSegment> rlIterator = routeLineSegments.listIterator(segmentIndex);
        RouteLineSegment curRouteLineSegment;
        while (rlIterator.hasNext()){
            //update the routeLineSegmentsToConsider, removing the last segment and adding the next
            routeLineSegmentsToConsider.remove(0);
            if(futureSegmentIndex < segmentCount) {
                routeLineSegmentsToConsider.add(routeLineSegments.get(futureSegmentIndex++));
            }
            segmentIndex++;
            curRouteLineSegment = rlIterator.next();

            //now advance the active paths
            final boolean debug = debugRlSegIds.contains(curRouteLineSegment.id);
            if(debug) {
                System.out.format("\n\n*******PROCESS RL SEGMENT[%.01f,%.01f] %s\n", curRouteLineSegment.vectorX, curRouteLineSegment.vectorY, curRouteLineSegment);
            }
            //final List<Path> addedPaths = new ArrayList<>(candidatePaths.size());
            final ListIterator<Path> pathListIterator = candidatePaths.listIterator();
            while (pathListIterator.hasNext()) {
                final Path candidatePath = pathListIterator.next();

                //advance the Path (which may also create new Path forks) in the direction of the RouteLineSegment's position
                boolean didAdvance = candidatePath.advance(routeLineSegmentsToConsider, pathListIterator, this, routeConflator, debug);

                if(didAdvance && candidatePath.outcome == Path.PathOutcome.waypointReached) {
                    successfulPaths.add(candidatePath);
                }
            }

            //System.out.println("IT: " + curRouteLineSegment + " S0/1: " + segmentIndex + "/" + futureSegmentIndex);
        }
        System.out.println(candidatePaths.size() + " possible paths found (" + successfulPaths.size() + " successful)");
        for(final Path path : successfulPaths) {
            System.out.println("\t" + path);
        }
        if(successfulPaths.size() == 0) {
            for (final Path path : candidatePaths) {
                System.out.println("\t" + path);
            }
        }
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
    public void debugOutputPaths(final OSMEntitySpace entitySpace, final int pathIndex) {
        /*for(final Path path : candidatePaths) {
            path.debugOutputPathSegments(entitySpace, pathIndex);
        }*/
        if(bestPath != null) {
            bestPath.debugOutputPathSegments(entitySpace, pathIndex);
        }
    }
    public void setDestinationStop(final StopArea stop) {
        if((matchStatus & matchStatusToStop) != 0) {
            throw new IllegalStateException("To stop is already set");
        }
        matchStatus |= matchStatusToStop;
        this.destinationStop = stop;
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
    protected Junction createJunction(final OSMNode junctionNode) {
        Junction junction = junctions.get(junctionNode.osm_id);
        if(junction != null) {
            return junction;
        }
        junction = new Junction(junctionNode);
        junctions.put(junctionNode.osm_id, junction);
        return junction;
    }
    @Override
    public String toString() {
        return String.format("Path from %s to %s: status %d, %s segments", originStop, destinationStop, matchStatus, routeLineSegments != null ? Integer.toString(routeLineSegments.size()) : "N/A");
    }
}
