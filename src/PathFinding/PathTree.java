package PathFinding;

import Conflation.*;
import OSM.*;
import com.company.InvalidArgumentException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final static double scoreThresholdToProcessPathSegment = 3.0, MAX_PATH_LENGTH_FACTOR = 3.0, MAX_DETOUR_LENGTH = 50.0;

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
    private final double maxPathLength;

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

        //the maximum path length is a multiple of the as-the-crow-flies distance between the from/to nodes, to cut down on huge path detours
        maxPathLength = Point.distance(fromNode.getCentroid(), toNode.getCentroid()) * MAX_PATH_LENGTH_FACTOR;

        fromLine.addObserver(this);
        toLine.addObserver(this);
    }
    public static String idForParameters(final OSMNode fromNode, final OSMNode toNode) {
        return String.format("PT:%d:%d", fromNode.osm_id, toNode.osm_id);
    }
    private void iteratePath(final Junction startingJunction, final RoutePathFinder routePathFinder, final Path originatingPath, final int debugDepth) throws PathIterationException {
        //bail if the junction indicates there's no need to continue processing
        if(!processJunction(startingJunction, routePathFinder, originatingPath, debugDepth)) {
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
        Path exitingPath = null;
        for(final Junction.PathSegmentStatus segmentStatus : junctionSegmentsToProcess) {
            //routePathFinder.logEvent(RoutePathFinder.RouteLogType.info, "Junction " + startingJunction.junctionNode.osm_id + ": " + segmentStatus.segment + ": " +  Math.round(100.0 * segmentStatus.segment.alignedPathScore)/100.0 + "/" + Math.round(100.0 * segmentStatus.segment.alignedLengthFactor)/100.0 + "%/" + Math.round(100.0 * segmentStatus.segment.waypointScore)/100.0 + "=" + Math.round(segmentStatus.segment.getTotalScore()) + ", STATUS " + segmentStatus.processStatus.name(), this);
            System.out.format("\n%sL%d: Junction %d: %s: %.02f/%.02f%%/%.02f=%.02f, STATUS %s", debugPadding, debugDepth, startingJunction.junctionNode.osm_id, segmentStatus.segment, segmentStatus.segment.alignedPathScore, segmentStatus.segment.alignedLengthFactor, segmentStatus.segment.waypointScore, segmentStatus.segment.getTotalScore(), segmentStatus.processStatus.name());
            if(segmentStatus.processStatus != Junction.PathSegmentProcessStatus.yes) { //don't process the segment if it's a bad match, or if it's the originating segment for the junction
                continue;
            }

            //bail if we've iterated too many paths - unlikely to find anything if we've reached this stage
            if(candidatePaths.size() > MAX_PATHS_TO_CONSIDER) {
                routePathFinder.logEvent(RoutePathFinder.RouteLogType.warning, "Reached maximum paths to consider - unable to find a suitable path", this);
                throw new PathIterationException("Reached maximum paths");
            }

            //process the ending junction of the current PathSegment, but only if its line is properly matched with the route path
            if(exitingPath == null) { //the best matching PathSegment is simply added to the current path
                exitingPath = originatingPath;
                exitingPath.addPathSegment(segmentStatus.segment);
            } else { //the next-best PathSegments are added to a clone of the current path, branched at this junction
                exitingPath = new Path(originatingPath, segmentStatus.segment);
                candidatePaths.add(exitingPath);
            }

            System.out.format(" DETOUR %.02f/%.02f", originatingPath.detourSegmentLength, exitingPath.detourSegmentLength);

            if(exitingPath.totalSegmentLength > maxPathLength) {
                exitingPath.outcome = Path.PathOutcome.lengthLimitReached;
            } else if(exitingPath.detourSegmentLength > MAX_DETOUR_LENGTH) { //also enforce detour limits - don't continue the path if it exceeds them
                exitingPath.outcome = Path.PathOutcome.detourLimitReached;
            } else { //if path limits aren't reached, process the PathSegment's end Junction
                iteratePath(segmentStatus.segment.endJunction, routePathFinder, exitingPath, debugDepth + 1);
                processedPathSegments++;
            }
        }

        //dead end junction: mark this path as such
        if(processedPathSegments == 0) {
            System.out.format("\n%sL%d: Dead end at node #%d (%s)", debugPadding, debugDepth, startingJunction.junctionNode.osm_id, originatingPath.outcome.name());
            routePathFinder.logEvent(RoutePathFinder.RouteLogType.notice, "Dead end at node #" + startingJunction.junctionNode.osm_id + "(" + originatingPath.outcome.name() + ")", this);
            if(originatingPath.outcome == Path.PathOutcome.unknown) {
                originatingPath.outcome = Path.PathOutcome.deadEnded;
            }
        }
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
            System.out.format("%sL%d: junction #%d DEAD END\n", debugPadding, debugDepth, junction.junctionNode.osm_id);
            return false;
        }

        for(final OSMWay junctionWay : junction.junctionNode.containingWays.values()) {
            //look up the respective WaySegments object for the way
            final OSMWaySegments curLine = parentPath.candidateLines.get(junctionWay.osm_id);
            if(curLine == null) {
                System.out.format("%sL%d: Unable to find way #%d in candidate Lines\n", debugPadding, debugDepth,  junctionWay.osm_id);
                parentPath.logEvent(RoutePathFinder.RouteLogType.error, "Unable to find way #" + junctionWay.osm_id + " in candidate Lines", this);
                continue;
            }

            //create a PathSegment for it
            final PathSegment wayPathSegment = PathSegment.createNewPathSegment(curLine, junction, this);

            //if wayPathSegment contains toNode, we've found a successful path.  Mark it and bail
            if(wayPathSegment.containsPathDestinationNode()) {
                System.out.format("%sL%d: FOUND destination on %s, node %s\n", debugPadding, debugDepth, wayPathSegment.getLine(), toNode.getTag(OSMEntity.KEY_NAME));
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

        if(bestPath != null) {
            System.out.println(this + " SUCCESS: ");
            for (final Path path : successfulPaths) {
                System.out.println(path + ": " + path.getTotalScore());
            }
        } else {
            System.out.println(this + " FAILED: ");
            for (final Path path : candidatePaths) {
                System.out.println(path + ": " + path.getTotalScore());
            }
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
    public void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment oldSegment, final LineSegment[] newSegments) {

    }
    @Override
    public String toString() {
        return String.format("PathTree@%d #%s (%d->%d): %d/%d matched/total paths", hashCode(), id, fromNode.osm_id, toNode.osm_id, successfulPaths.size(), candidatePaths.size());
    }
}
