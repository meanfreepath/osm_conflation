package NewPathFinding;

import Conflation.*;
import Importer.InvalidArgumentException;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import OSM.Point;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Tracks all the possible paths between the starting and ending waypoints
 * Created by nick on 10/12/16.
 */
public class PathTree {
    private final static CRC32 idGenerator = new CRC32();
    private final static Comparator<Path> pathScoreComparator = new Comparator<Path>() {
        @Override
        public int compare(final Path o1, final Path o2) {
            return o1.getTotalScore() > o2.getTotalScore() ? -1 : 1;
        }
    };

    public final static short matchStatusNone = 0, matchStatusFromStop = 1, matchStatusToStop = 2, matchStatusFromRouteLineNode = 4, getMatchStatusToRouteLineNode = 8;
    public final static short matchMaskAll = matchStatusFromStop | matchStatusToStop | matchStatusFromRouteLineNode | getMatchStatusToRouteLineNode;
    public final static int MAX_PATHS_TO_CONSIDER = 320;
    private final static short NUMBER_OF_FUTURE_SEGMENTS = 5;
    private final static long debugPathTreeId = 2996122432L;//1153577939L;
    public static boolean debugEnabled = false;

    public final long id;
    public final int pathTreeIndex;
    public final Route route;
    public final PathTree previousPathTree;
    public PathTree nextPathTree = null;
    public final StopArea originStop;
    public StopArea destinationStop = null;
    public final Point fromRouteLinePoint;
    public Point toRouteLinePoint = null;
    public short matchStatus;
    public final List<RouteLineSegment> routeLineSegments;

    public final RoutePathFinder parentPathFinder;
    public final List<Path> candidatePaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public final List<Path> successfulPaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public final List<Path> failedPaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public Path bestPath = null;

    public static long idForParameters(final int index, final StopArea fromStop, final StopArea toStop) {
        idGenerator.reset();
        byte[] idBytes = String.format("PT:%d:%d:%d", index, fromStop != null ? fromStop.getPlatform().osm_id : 0, toStop != null ? toStop.getPlatform().osm_id : 0).getBytes(StandardCharsets.US_ASCII);
        idGenerator.update(idBytes, 0, idBytes.length);
        return idGenerator.getValue();
    }
    public PathTree(final Route route, final StopArea originStop, final Point originRouteLinePoint, final PathTree previousPath, final int pathTreeIndex, final RoutePathFinder parentPathFinder) {
        this.route = route;
        this.originStop = originStop;
        fromRouteLinePoint = originRouteLinePoint;
        this.previousPathTree = previousPath;
        matchStatus = matchStatusFromStop;
        if(fromRouteLinePoint != null) {
            matchStatus |= matchStatusFromRouteLineNode;
        }
        this.pathTreeIndex = pathTreeIndex;
        this.parentPathFinder = parentPathFinder;

        id = idForParameters(this.pathTreeIndex, this.originStop, this.destinationStop);

        //observe any splits on the RouteLine (i.e. when generating other PathTrees)
        //this.routeLine.addObserver(this); //NOTE: not needed, since routeLines are only touched by their owning Route object

        //the maximum path length is a multiple of the as-the-crow-flies distance between the from/to nodes, to cut down on huge path detours
        //maxPathLength = Point.distance(fromNode.getCentroid(), toNode.getCentroid()) * MAX_PATH_LENGTH_FACTOR;
        routeLineSegments = new ArrayList<>(128);
    }
    public void findPaths(final RouteConflator routeConflator) {
        boolean debug = id == debugPathTreeId;

        if(debug) {
            System.out.print("\n\nCheck find path for " + id + "(" + pathTreeIndex + ") " + originStop.getPlatform().getTag(OSMEntity.KEY_NAME) + " -> " + destinationStop.getPlatform().getTag(OSMEntity.KEY_NAME) + ": ");
        }
        //make sure we have enough of the required information to match this path
        if(matchStatus != matchMaskAll) {
            System.out.println("FAILED: insufficient match (mask is " + matchStatus + ")");
            return;
        }
        if(debug) {
            System.out.println("running now…");
        }

        //Create a new Path object for every way that originates from the stop position
        final List<PathSegment> initialPathSegments = Path.determineOutgoingPathSegments(routeConflator, originStop.getStopPosition(routeConflator.routeType), null);
        for(final PathSegment initialPathSegment : initialPathSegments) {
            final Path initialPath = new Path(this, initialPathSegment);
            candidatePaths.add(initialPath);
        }

        //prepopulate the future segments to use in the futureVector calculation
        final ArrayList<RouteLineSegment> routeLineSegmentsToConsider = new ArrayList<>(NUMBER_OF_FUTURE_SEGMENTS);
        short curIdx = 0;
        for(final RouteLineSegment futureSegment : routeLineSegments) {
            routeLineSegmentsToConsider.add(futureSegment);
            if(++curIdx >= NUMBER_OF_FUTURE_SEGMENTS) {
                break;
            }
        }

        final long[] debugRlSegIdsRaw = {2183985599L, 1707782496L, 1936162033L, 1917356142L, 133202163L, 1161896790L, 1789482644L, 1336691900L, 2162494158L};
        final List<Long> debugRlSegIds = new ArrayList<>(debugRlSegIdsRaw.length);
        for(final long rlId : debugRlSegIdsRaw) {
            debugRlSegIds.add(rlId);
        }

        //now iterate the RouteLine's segments, advancing candidatePaths toward the current segment
        int futureSegmentIndex = NUMBER_OF_FUTURE_SEGMENTS;
        final int segmentCount = routeLineSegments.size();
        final Iterator<RouteLineSegment> rlIterator = routeLineSegments.listIterator();
        RouteLineSegment curRouteLineSegment;
        while (rlIterator.hasNext()){
            curRouteLineSegment = rlIterator.next();

            //now advance the active paths
            debug = id == debugPathTreeId;// || debugRlSegIds.contains(curRouteLineSegment.id);
            if(debug) {
                System.out.format("\n\n*******PROCESS RL SEGMENT[%.01f,%.01f] %s\n", curRouteLineSegment.vectorX, curRouteLineSegment.vectorY, curRouteLineSegment);
            }

            final ListIterator<Path> pathListIterator = candidatePaths.listIterator();
            final List<OSMNode> iterationProcessedNodes = new ArrayList<>(8); //tracks nodes processed for each RouteLineSegment, to prevent infinite path loops
            while (pathListIterator.hasNext()) {
                final Path candidatePath = pathListIterator.next();
                if(candidatePath.outcome != Path.PathOutcome.unknown) {
                    pathListIterator.remove();
                    continue;
                }
                iterationProcessedNodes.clear();

                if(debug) {
                    System.out.println("\tCheck path ending with: " + candidatePath.lastPathSegment);
                }

                //advance the Path (which may also create new Path forks) in the direction of the RouteLineSegment's position
                boolean didAdvance = candidatePath.advance(routeLineSegmentsToConsider, pathListIterator, this, routeConflator, iterationProcessedNodes, debug);
                if(debug) {
                    System.out.format("\t%s: outcome is %s, %d nodes processed, last PathSeg is %s\n", didAdvance ? "ADVANCED " : "NOADVANCE", candidatePath.outcome, iterationProcessedNodes.size(), candidatePath.lastPathSegment.getProcessingStatus());
                }

                //compile a list of the paths that successfully reached their destination
                if(didAdvance) {
                    if(candidatePath.outcome == Path.PathOutcome.waypointReached) {
                        successfulPaths.add(candidatePath);
                    } else if(candidatePath.outcome != Path.PathOutcome.unknown) {
                        failedPaths.add(candidatePath);
                    }
                }
            }

            //update the routeLineSegmentsToConsider, removing the last segment and adding the next
            routeLineSegmentsToConsider.remove(0);
            if(futureSegmentIndex < segmentCount) {
                routeLineSegmentsToConsider.add(routeLineSegments.get(futureSegmentIndex++));
            }
        }

        System.out.format("INFO: %s: %d possible paths found, (%d successful, %d failed, %d skipped)\n", this, candidatePaths.size() + successfulPaths.size() + failedPaths.size(), successfulPaths.size(), failedPaths.size(), candidatePaths.size());

        //now determine the best path, based on its score
        successfulPaths.sort(pathScoreComparator);
        bestPath = successfulPaths.size() > 0 ? successfulPaths.get(0) : null;

        if(bestPath != null) {
            if(id == debugPathTreeId) {
                System.out.println("\tSUCCESS: ");
                for (final Path path : successfulPaths) {
                    System.out.format("\t\tscore %.01f: %s\n", path.getTotalScore(), path);
                }
            }

            //clear out all the other paths and release them
            candidatePaths.clear();
            successfulPaths.clear();
            failedPaths.clear();
        } else { //debug output
            System.out.println("\tFAILED: ");
            int longestPathSize = 0, pathSize, maxPathDiff = 3;
            for(final Path path : failedPaths) {
                pathSize = path.getPathSegments().size();
                if(pathSize > longestPathSize) {
                    longestPathSize = pathSize;
                }
            }
            final List<Path> outputPaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
            for(final Path path : failedPaths) {
                pathSize = path.getPathSegments().size();
                if(pathSize >= longestPathSize - maxPathDiff) {
                    outputPaths.add(path);
                }
            }
            for(final Path path : candidatePaths) {
                pathSize = path.getPathSegments().size();
                if(pathSize >= longestPathSize - maxPathDiff) {
                    outputPaths.add(path);
                }
            }
            for (final Path path : outputPaths) {
                System.out.println("\t\t" + path);
            }
        }

    }
    public void compileRouteLineSegments() {
        if(matchStatus != matchMaskAll) {
            return;
        }
        boolean inLeg = false;
        for(final LineSegment segment : route.routeLine.segments) {
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
    public void setRouteLineEnd(final Point end) {
        if((matchStatus & getMatchStatusToRouteLineNode) != 0) {
            throw new IllegalStateException("From node is already set");
        }
        matchStatus |= getMatchStatusToRouteLineNode;
        this.toRouteLinePoint = end;
    }
    public void splitWaysAtIntersections(final OSMEntitySpace entitySpace, final RoutePathFinder parentPathFinder) throws InvalidArgumentException {
        if(bestPath == null || bestPath.outcome != Path.PathOutcome.waypointReached) {
            return;
        }

        final List<OSMNode> newSplitNodes = new ArrayList<>(2);

        //check if the best path needs any intermediate splits
        bestPath.splitWaysAtIntersections(entitySpace, newSplitNodes);
    }
    @Override
    public String toString() {
        final OSMNode originStopPosition = originStop.getStopPosition(route.routeType), destinationStopPosition = destinationStop.getStopPosition(route.routeType);
        return String.format("PathTree #%d (idx %d) from %s/%s (%s:%s) to %s/%s (%s:%s): status %d, %s segments", id, pathTreeIndex, originStop.getPlatform().osm_id, originStopPosition != null ? Long.toString(originStopPosition.osm_id) : "N/A", originStop.getPlatform().getTag(OSMEntity.KEY_REF), originStop.getPlatform().getTag(OSMEntity.KEY_NAME), destinationStop != null ? Long.toString(destinationStop.getPlatform().osm_id) : "N/A", destinationStop != null && destinationStopPosition != null ? Long.toString(destinationStopPosition.osm_id) : "N/A", destinationStop != null ? destinationStop.getPlatform().getTag(OSMEntity.KEY_REF) : "N/A", destinationStop != null ? destinationStop.getPlatform().getTag(OSMEntity.KEY_NAME) : "N/A", matchStatus, routeLineSegments != null ? Integer.toString(routeLineSegments.size()) : "N/A");
    }
}
