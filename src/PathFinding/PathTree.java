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
    private final static double scoreThresholdToProcessPathSegment = 3.0, MAX_DETOUR_LENGTH = 0.0;

    public final String id;
    public final StopArea fromStop, toStop;
    public final OSMNode fromNode, toNode;
    private WaySegments fromLine, toLine;
    public final PathTree previousPathTree;
    public PathTree nextPathTree = null;
    public final RoutePathFinder parentPathFinder;

    public final List<Path> candidatePaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public final List<Path> successfulPaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
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
        id = idForParameters(fromNode, toNode);

        fromLine.addObserver(this);
        toLine.addObserver(this);
    }
    public static String idForParameters(final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PT:%d:%d", fromNode.osm_id, toNode.osm_id);
    }
    private void iteratePath(final Junction startingJunction, final RoutePathFinder routePathFinder, final Path curPath, final int debugDepth) throws PathIterationException {
        //bail if the junction indicates there's no need to continue processing
        if(!processJunction(startingJunction, routePathFinder, curPath, debugDepth)) {
            return;
        }

        String debugPadding = null;
        if(debugEnabled) {
            debugPadding = "";
            for(int d=0;d<debugDepth;d++) {
                debugPadding += "  ";
            }
        }

        /*use a copy of the junction's PathSegments list, in case it's crossed again later in the path (i.e. looping).  On this iteration
          we only care about the *current* pathSegments*/
        final List<Junction.PathSegmentStatus> junctionSegmentsToProcess = new ArrayList<>(startingJunction.junctionPathSegments);
        int processedPathSegments = 0;
        for(final Junction.PathSegmentStatus segmentStatus : junctionSegmentsToProcess) {
            //routePathFinder.logEvent(RoutePathFinder.RouteLogType.info, "Junction " + startingJunction.junctionNode.osm_id + ": " + segmentStatus.segment + ": " +  Math.round(100.0 * segmentStatus.segment.alignedPathScore)/100.0 + "/" + Math.round(100.0 * segmentStatus.segment.alignedLengthFactor)/100.0 + "%/" + Math.round(100.0 * segmentStatus.segment.waypointScore)/100.0 + "=" + Math.round(segmentStatus.segment.getTotalScore()) + ", STATUS " + segmentStatus.processStatus.name(), this);
            System.out.println(debugPadding + "L" + debugDepth + ":Junction " + startingJunction.junctionNode.osm_id + ": " + segmentStatus.segment + ": " +  Math.round(100.0 * segmentStatus.segment.alignedPathScore)/100.0 + "/" + Math.round(100.0 * segmentStatus.segment.alignedLengthFactor)/100.0 + "%/" + Math.round(100.0 * segmentStatus.segment.waypointScore)/100.0 + "=" + Math.round(segmentStatus.segment.getTotalScore()) + ", STATUS " + segmentStatus.processStatus.name());
            if(segmentStatus.processStatus != Junction.PathSegmentProcessStatus.yes) { //don't process the segment if it's a bad match, or if it's the originating segment for the junction
                continue;
            }

            //bail if we've iterated too many paths - unlikely to find anything if we've reached this stage
            if(candidatePaths.size() > MAX_PATHS_TO_CONSIDER) {
                routePathFinder.logEvent(RoutePathFinder.RouteLogType.warning, "Reached maximum paths to consider - unable to find a suitable path", this);
                throw new PathIterationException("Reached maximum paths");
            }

            //process the ending junction of the current segment, but only if its line is properly matched with the route path
            final Path newPath = createPath(curPath, segmentStatus.segment);
            if(newPath.detourSegmentLength <= MAX_DETOUR_LENGTH) { //also enforce detour limits - don't continue the path if it exceeds them
                iteratePath(segmentStatus.segment.endJunction, routePathFinder, newPath, debugDepth + 1);
                processedPathSegments++;
            } else { //mark path as reaching detour limit
                newPath.outcome = Path.PathOutcome.detourLimitReached;
            }
        }

        if(processedPathSegments == 0) { //dead end junction: mark this path as such
            routePathFinder.logEvent(RoutePathFinder.RouteLogType.notice, "Dead end at node #" + startingJunction.junctionNode.osm_id, this);
            curPath.outcome = Path.PathOutcome.deadEnded;
        }
    }
    private Path createPath(final Path parent, final PathSegment segmentToAdd) {
        final Path newPath = new Path(parent, segmentToAdd); //create a clone of parent, with the new segment added to it
        candidatePaths.add(newPath);
        return newPath;
    }
    private boolean processJunction(final Junction junction, final RoutePathFinder parentPath, final Path currentPath, final int debugDepth) {
        String debugPadding = null;
        if(debugEnabled) {
            debugPadding = "";
            for(int d=0;d<debugDepth;d++) {
                debugPadding += "  ";
            }
        }

        if(junction.processStatus == Junction.JunctionProcessStatus.deadEnd) {
            System.out.println(debugPadding + "L" + debugDepth + ": junction #" + junction.junctionNode.osm_id + " DEAD END");
            return false;
        }

        for(final OSMWay junctionWay : junction.junctionNode.containingWays.values()) {
            //look up the respective WaySegments object for the way
            final WaySegments curLine = parentPath.candidateLines.get(junctionWay.osm_id);
            if(curLine == null) {
                System.out.println(debugPadding + "L" + debugDepth + ": Unable to find way #" + junctionWay.osm_id + " in candidate Lines");
                parentPath.logEvent(RoutePathFinder.RouteLogType.error, "Unable to find way #" + junctionWay.osm_id + " in candidate Lines", this);
                continue;
            }

            //create a PathSegment for it
            final PathSegment wayPathSegment = PathSegment.createNewPathSegment(curLine, junction, this);

            //if wayPathSegment contains toNode, we've found a successful path.  Mark it and bail
            if(wayPathSegment.containsPathDestinationNode()) {
                System.out.println(debugPadding + "L" + debugDepth + ": FOUND destination on " + wayPathSegment.getLine().way.getTag(OSMEntity.KEY_NAME)  + "(" + wayPathSegment.getLine().way.osm_id + "), node " + toNode.getTag(OSMEntity.KEY_NAME));
                parentPath.logEvent(RoutePathFinder.RouteLogType.info, "FOUND destination on " + wayPathSegment.getLine().way.getTag(OSMEntity.KEY_NAME)  + "(" + wayPathSegment.getLine().way.osm_id + "), node " + toNode.getTag(OSMEntity.KEY_NAME), this);
                currentPath.markAsSuccessful(wayPathSegment);
                if(junction.containsPathSegment(wayPathSegment) == null) {
                    junction.addPathSegment(wayPathSegment, Junction.PathSegmentProcessStatus.containsDestination);
                }
                return false;
            }

            //add the PathSegment to the junction, along with a decision based on its score
            if(junction.containsPathSegment(wayPathSegment) == null) {
                junction.addPathSegment(wayPathSegment, statusForPathSegment(wayPathSegment));
            }
        }
        junction.sortPathSegmentsByScore();

        return true; //indicate further processing is necessary
    }
    private Junction.PathSegmentProcessStatus statusForPathSegment(final PathSegment segment) {
        if(segment.isLineMatchedWithRoutePath()) {
            if (segment.getTotalScore() >= scoreThresholdToProcessPathSegment) {
                return Junction.PathSegmentProcessStatus.yes;
            } else {
                return Junction.PathSegmentProcessStatus.belowScoreThreshold;
            }
        } else {
            return Junction.PathSegmentProcessStatus.nonMatchingLine;
        }
    }
    public void findPaths(final RoutePathFinder parentPath) {
        if(debugEnabled) {
            System.out.println("\n\n-----------------------------------------------------------------------------------\nBEGIN PATHTREE " + this);
        }

        //starting with the first line, determine the initial direction of travel
        final Junction startingJunction = new Junction(fromNode, null, Junction.JunctionProcessStatus.continuePath);
        final Path initialPath = new Path(this, null); //this path is always empty, since there are 3+ possible paths from each junction
        candidatePaths.add(initialPath);
        try {
            iteratePath(startingJunction, parentPath, initialPath, 0);
        } catch (PathIterationException ignored) {}

        //compile a list of the paths that successfully reached their destination
        for(final Path path : candidatePaths) {
            if(path.outcome == Path.PathOutcome.waypointReached) {
                successfulPaths.add(path);
            }
        }

        //and choose the path with the best score
        successfulPaths.sort(pathScoreComparator);
        bestPath = successfulPaths.size() > 0 ? successfulPaths.get(0) : null;

        System.out.println(this + ": ");
        for(final Path path :successfulPaths) {
            System.out.println(path + ": " + path.getTotalScore());
        }
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
    @Override
    public String toString() {
        return String.format("PathTree@%d #%s (%d->%d): %d/%d matched/total paths", hashCode(), id, fromNode.osm_id, toNode.osm_id, successfulPaths.size(), candidatePaths.size());
    }
}
