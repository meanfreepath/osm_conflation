package PathFinding;

import Conflation.LineSegment;
import Conflation.StopArea;
import Conflation.WaySegmentsObserver;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import Conflation.WaySegments;
import OSM.OSMWay;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.*;

/**
 * Represents the possible paths between 2 nodes
 * Created by nick on 1/27/16.
 */
public class PathTree implements WaySegmentsObserver {
    public final static int MAX_PATHS_TO_CONSIDER = 32;
    private final static Comparator<Path> pathScoreComparator = new Comparator<Path>() {
        @Override
        public int compare(final Path o1, final Path o2) {
            return o1.getTotalScore() > o2.getTotalScore() ? -1 : 1;
        }
    };
    private final static double scoreThresholdToProcessPathSegment = 3.0;

    public final StopArea fromStop, toStop;
    public final OSMNode fromNode, toNode;
    private WaySegments fromLine, toLine;
    public final PathTree previousPathTree;
    public PathTree nextPathTree = null;
    public final RoutePathFinder parentPathFinder;

    public final List<Path> candidatePaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public Path bestPath = null;
    public static boolean debugEnabled = false;

    private class PathIterationException extends Exception {
        public PathIterationException(final String message) {
            super(message);
        }
    }

    public PathTree(final StopArea fromStop, final StopArea toStop, final PathTree previousPath, final RoutePathFinder parentPathFinder) {
        this.fromStop = fromStop;
        this.toStop = toStop;
        this.previousPathTree = previousPath;
        this.parentPathFinder = parentPathFinder;
        fromNode = fromStop.getStopPosition();
        toNode = toStop.getStopPosition();
        fromLine = fromStop.bestWayMatch.line;
        toLine = toStop.bestWayMatch.line;

        fromLine.addObserver(this);
        toLine.addObserver(this);
    }
    private void iteratePath(final Junction startingJunction, final RoutePathFinder routePathFinder, final Path curPath) throws PathIterationException {
        //bail if the junction indicates there's no need to continue processing
        if(!processJunction(startingJunction, routePathFinder, curPath)) {
            return;
        }

        System.out.println("FROM " + (startingJunction.originatingPathSegment != null ? startingJunction.originatingPathSegment.segment.getLine().way.getTag("name") + " (" + startingJunction.originatingPathSegment.segment.getLine().way.osm_id + "): " : "BEGIN: ") + startingJunction.junctionPathSegments.size() + " TO PROCESS: ordered list:");
        for(final Junction.PathSegmentStatus segmentStatus : startingJunction.junctionPathSegments) {
            if(segmentStatus.segment.isLineMatchedWithRoutePath()) {
                System.out.println("\t" + segmentStatus.segment.getLine().way.getTag("name") + "(" + segmentStatus.segment.originJunction.junctionNode.osm_id + "/" + segmentStatus.segment.endJunction.junctionNode.osm_id + "): " + segmentStatus.segment.getScore() + ":" + segmentStatus.processStatus.name());
            } else {
                System.out.println("\t" + segmentStatus.segment.getLine().way.getTag("name") + "(" + segmentStatus.segment.originJunction.junctionNode.osm_id + "/NoMatch): " + segmentStatus.segment.getScore() + ":" + segmentStatus.processStatus.name());
            }
        }

        int processedPathSegments = 0;
        for(final Junction.PathSegmentStatus segmentStatus : startingJunction.junctionPathSegments) {
            routePathFinder.logEvent(RoutePathFinder.RouteLogType.info, "Junction " + startingJunction.junctionNode.osm_id + ": " + segmentStatus.segment + ": " +  Math.round(100.0 * segmentStatus.segment.pathScore)/100.0 + "/" + Math.round(100.0 * segmentStatus.segment.lengthFactor)/100.0 + "%/" + Math.round(100.0 * segmentStatus.segment.waypointScore)/100.0 + "=" + Math.round(segmentStatus.segment.getScore()) + ", STATUS " + segmentStatus.processStatus.name(), this);
            if(segmentStatus.processStatus != Junction.PathSegmentProcessStatus.yes) { //don't process the segment if it's a bad match, or if it's the originating segment for the junction
                continue;
            }

            //bail if we've iterated too many paths - unlikely to find anything if we've reached this stage
            if(candidatePaths.size() > MAX_PATHS_TO_CONSIDER) {
                routePathFinder.logEvent(RoutePathFinder.RouteLogType.warning, "Reached maximum paths to consider - unable to find a suitable path", this);
                throw new PathIterationException("Reached maximum paths");
            }

            //process the ending junction of the current segment, but only if its line is properly matched with the route path
            if(segmentStatus.segment.isLineMatchedWithRoutePath()) {
                final Path newPath = createPath(curPath, segmentStatus.segment);
                iteratePath(segmentStatus.segment.endJunction, routePathFinder, newPath);
            } else {
                System.out.println("NOROUTEMATCH");
                routePathFinder.logEvent(RoutePathFinder.RouteLogType.warning, "PathSegment for " + segmentStatus.segment.getLine().way.getTag(OSMEntity.KEY_NAME) + "(" + segmentStatus.segment.getLine().way.osm_id + ") doesn't match route line - skipping", this);
            }
            processedPathSegments++;
        }

        if(processedPathSegments == 0) { //dead end path
            routePathFinder.logEvent(RoutePathFinder.RouteLogType.notice, "Dead end at node #" + startingJunction.junctionNode.osm_id, this);
            curPath.outcome = Path.PathOutcome.deadEnded;
        }
    }
    private Path createPath(final Path parent, final PathSegment segmentToAdd) {
        final Path newPath = new Path(parent, segmentToAdd); //create a clone of parent, with the new segment added to it
        candidatePaths.add(newPath);
        return newPath;
    }
    private boolean processJunction(final Junction junction, final RoutePathFinder parentPath, final Path currentPath) {
        if(junction.processStatus == Junction.JunctionProcessStatus.deadEnd) {
            System.out.println("ZOMG SHOULDNT PROCESS");
            return false;
        }

        for(final OSMWay junctionWay : junction.junctionNode.containingWays.values()) {
            //look up the respective WaySegments object for the way
            final WaySegments curLine = parentPath.candidateLines.get(junctionWay.osm_id);
            if(curLine == null) {
                parentPath.logEvent(RoutePathFinder.RouteLogType.error, "Unable to find way #" + junctionWay.osm_id + " in candidate Lines", this);
                continue;
            }

            //create a PathSegment for it
            final PathSegment wayPathSegment = new PathSegment(curLine, junction, this);

            //if wayPathSegment contains toNode, we've found a successful path.  Mark it and bail
            if(wayPathSegment.containsPathDestinationNode()) {
                parentPath.logEvent(RoutePathFinder.RouteLogType.info, "FOUND destination on " + wayPathSegment.getLine().way.getTag(OSMEntity.KEY_NAME)  + "(" + wayPathSegment.getLine().way.osm_id + "), node " + toNode.getTag(OSMEntity.KEY_NAME), this);
                currentPath.markAsSuccessful(wayPathSegment);
                junction.addPathSegment(wayPathSegment, Junction.PathSegmentProcessStatus.containsDestination);
                return false;
            }

            final Junction.PathSegmentProcessStatus processStatus;
            if(wayPathSegment.isLineMatchedWithRoutePath()) {
                if (wayPathSegment.getScore() >= scoreThresholdToProcessPathSegment) {
                    processStatus = Junction.PathSegmentProcessStatus.yes;
                } else {
                    processStatus = Junction.PathSegmentProcessStatus.belowScoreThreshold;
                }
            } else {
                processStatus = Junction.PathSegmentProcessStatus.nonMatchingLine;
            }
            junction.addPathSegment(wayPathSegment, processStatus);
        }
        junction.sortPathSegmentsByScore();

        return true; //indicate further processing is necessary
    }
    public void findPaths(final RoutePathFinder parentPath) {
        //starting with the first line, determine the initial direction of travel
        final Junction startingJunction = new Junction(fromNode, null, Junction.JunctionProcessStatus.continuePath);
        final Path initialPath = new Path(this, null); //this path is always empty, since there are 3+ possible paths from each junction
        try {
            iteratePath(startingJunction, parentPath, initialPath);
        } catch (PathIterationException ignored) {}

        //compile a list of the paths that successfully reached their destination
        final List<Path> successfulPaths = new ArrayList<>(candidatePaths.size());
        for(final Path path : candidatePaths) {
            if(path.outcome == Path.PathOutcome.waypointReached) {
                successfulPaths.add(path);
            }
        }

        successfulPaths.sort(pathScoreComparator);
        bestPath = successfulPaths.size() > 0 ? successfulPaths.get(0) : null;

        System.out.println(successfulPaths.size() + " successful paths: ");
        for(final Path path :successfulPaths) {
            System.out.println(path + ": " + path.getTotalScore());
        }


        //get the closest segment on the routeLine to fromNode
//        fromLine.getMatchForLine(parentPath.route.routeLine).getAvgDotProduct();


        //we then determine the best path based on score (TODO maybe try shortcuts etc here)
        //bestPath =
    }
    public Path splitWaysAtIntersections(final OSMEntitySpace entitySpace, final Path previousPath, final RoutePathFinder parentPathFinder) throws InvalidArgumentException {
        if(bestPath == null || bestPath.outcome != Path.PathOutcome.waypointReached) {
            return null;
        }
        bestPath.splitWaysAtIntersections(entitySpace, previousPath, parentPathFinder);
        return bestPath;
    }
    public void debugOutputPaths(final OSMEntitySpace entitySpace, final int pathIndex) {
        /*for(final Path path : candidatePaths) {
            path.debugOutputPathSegments(entitySpace, pathIndex);
        }*/
        if(bestPath != null) {
            bestPath.debugOutputPathSegments(entitySpace, pathIndex);
        }
    }
    public WaySegments getFromLine() {
        return fromLine;
    }
    public WaySegments getToLine() {
        return toLine;
    }
    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        //reassign the from/to lines as needed, depending on whether they contain the from/to nodes
        if(originalWaySegments == fromLine) {
            if(!fromLine.way.getNodes().contains(fromNode)) {
                for (final WaySegments ws : splitWaySegments) {
                    if(ws.way.getNodes().contains(fromNode)) {
                        fromLine = ws;
                        break;
                    }
                }
            }
        } else if(originalWaySegments == toLine) {
            if(!toLine.way.getNodes().contains(toNode)) {
                for (final WaySegments ws : splitWaySegments) {
                    if(ws.way.getNodes().contains(toNode)) {
                        toLine = ws;
                        break;
                    }
                }
            }
        }
    }
    @Override
    public void waySegmentsWasDeleted(final WaySegments waySegments) throws InvalidArgumentException {
        if(fromLine == waySegments || toLine == waySegments) {
            final String[] errMsg = {"Can't delete line - referred to by this PathTree"};
            throw new InvalidArgumentException(errMsg);
        }

        //check if the path should be deleted
    }
    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment newSegment) {

    }
}
