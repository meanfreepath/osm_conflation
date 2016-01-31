package PathFinding;

import Conflation.LineSegment;
import Conflation.StopArea;
import OSM.OSMEntity;
import OSM.OSMNode;
import Conflation.WaySegments;
import OSM.OSMWay;

import java.util.*;

/**
 * Represents the possible paths between 2 nodes
 * Created by nick on 1/27/16.
 */
public class PathTree {
    public final static int MAX_PATHS_TO_CONSIDER = 320;
    private final static Comparator<Path> pathScoreComparator = new Comparator<Path>() {
        @Override
        public int compare(final Path o1, final Path o2) {
            return o1.getTotalScore() > o2.getTotalScore() ? -1 : 1;
        }
    };
    private final static double scoreThresholdToProcessPathSegment = 0.1;

    public final OSMNode fromNode, toNode;
    public final WaySegments fromLine, toLine;

    public final List<Path> candidatePaths = new ArrayList<>(MAX_PATHS_TO_CONSIDER);
    public Path bestPath = null;

    private class PathIterationException extends Exception {
        public PathIterationException(final String message) {
            super(message);
        }
    }

    public PathTree(final OSMNode fromNode, final OSMNode toNode, final WaySegments fromLine, final WaySegments toLine) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.fromLine = fromLine;
        this.toLine = toLine;
    }
    private void iteratePath(final Junction startingJunction, final RoutePath parentPath, final Path curPath) throws PathIterationException {
        //bail if the junction indicates there's no need to continue processing
        if(!processJunction(startingJunction, parentPath, curPath)) {
            return;
        }

        System.out.println(startingJunction.junctionPathSegments.size() + " TO PROCESS: ordered list:");
        for(final Junction.JunctionSegmentStatus segmentStatus : startingJunction.junctionPathSegments) {
            System.out.println("\t" + segmentStatus.segment.line.way.getTag("name") + "(" + segmentStatus.segment.originJunction.junctionNode.osm_id + "/" + segmentStatus.segment.getEndJunction().junctionNode.osm_id + "): " + segmentStatus.segment.getScore() + ":" + segmentStatus.processStatus.name());
        }

        int processedSegments = 0;
        for(final Junction.JunctionSegmentStatus segmentStatus : startingJunction.junctionPathSegments) {
            parentPath.logEvent(RoutePath.RouteLogType.info, "Junction " + startingJunction.junctionNode.osm_id + ": score for PathSegment " + segmentStatus.segment.line.way.getTag(OSMEntity.KEY_NAME) + "(" + segmentStatus.segment.line.way.osm_id + "): " + segmentStatus.segment.getScore() + ", STATUS " + segmentStatus.processStatus.name(), this);
            if(segmentStatus.processStatus != Junction.ProcessStatus.yes) {
                continue;
            }

            //bail if we've iterated too many paths - unlikely to find anything if we've reached this stage
            if(candidatePaths.size() > MAX_PATHS_TO_CONSIDER) {
                parentPath.logEvent(RoutePath.RouteLogType.warning, "Reached maximum paths to consider - unable to find a suitable path", this);
                throw new PathIterationException("Reached maximum paths");
            }

            final Path newPath = new Path(curPath, segmentStatus.segment);
            candidatePaths.add(newPath);
            iteratePath(segmentStatus.segment.getEndJunction(), parentPath, newPath);
            processedSegments++;
        }

        if(processedSegments == 0) { //dead end path
            parentPath.logEvent(RoutePath.RouteLogType.notice, "Dead end at node #" + startingJunction.junctionNode.osm_id, this);
            curPath.outcome = Path.PathOutcome.deadEnded;
        }
    }
    private boolean processJunction(final Junction junction, final RoutePath parentPath, final Path currentPath) {
        for(final OSMWay junctionWay : junction.junctionNode.containingWays.values()) {
            //look up the respective WaySegments object for the way
            final WaySegments curLine = parentPath.candidateLines.get(junctionWay.osm_id);
            if(curLine == null) {
                parentPath.logEvent(RoutePath.RouteLogType.error, "Unable to find way #" + junctionWay.osm_id + " in candidate Lines", this);
                continue;
            }

            //create a PathSegment for it
            final PathSegment wayPathSegment = new PathSegment(curLine, junction);

            //don't double-back on the PathSegment we came from
            if(junction.originatingPathSegment == wayPathSegment) {
                continue;
            }
            wayPathSegment.determineScore(parentPath, fromNode, toNode);

            //if wayPathSegment contains toNode, we've found a successful path.  Mark it and bail
            if(wayPathSegment.containsPathDestinationNode()) {
                currentPath.markAsSuccessful(wayPathSegment);
                parentPath.logEvent(RoutePath.RouteLogType.info, "FOUND destination " + toNode.getTag(OSMEntity.KEY_NAME), this);
                return false;
            }

            final Junction.JunctionSegmentStatus segmentStatus = new Junction.JunctionSegmentStatus(wayPathSegment);
            if(wayPathSegment.getScore() >= scoreThresholdToProcessPathSegment) {
                segmentStatus.processStatus = Junction.ProcessStatus.yes;
            } else {
                segmentStatus.processStatus = Junction.ProcessStatus.belowScoreThreshold;
            }
            junction.junctionPathSegments.add(segmentStatus);
        }
        junction.sortPathSegmentsByScore();

        return true; //indicate further processing is necessary
    }
    public void findPaths(final RoutePath parentPath) {
        //starting with the first line, determine the initial direction of travel
        final Junction startingJunction = new Junction(fromNode, null);
        try {
            iteratePath(startingJunction, parentPath, null);
        } catch (PathIterationException ignored) {}

        //compile a list of the paths that successfully reached their destination
        final List<Path> successfulPaths = new ArrayList<>(candidatePaths.size());
        for(final Path path : candidatePaths) {
            if(path.outcome == Path.PathOutcome.waypointReached) {
                successfulPaths.add(path);
            }
        }

        successfulPaths.sort(pathScoreComparator);
        bestPath = successfulPaths.get(0);

        System.out.println(successfulPaths.size() + " successful paths: ");
        for(final Path path :successfulPaths) {
            System.out.println(path + ": " + path.getTotalScore());
        }


        //get the closest segment on the routeLine to fromNode
        fromLine.matchObject.getAvgDotProduct();

        //iterate over the line's nodes in the direction of travel

        //if a junction node is found, create PathSegment objects for each way, and evaluate them

        //choose the best option and continue down that path

        //if the best option dead ends, back out to the last junction with an untraveled match and repeat again


        //pathfinding ends when we reach toNode



        //we then determine the best path based on score (TODO maybe try shortcuts etc here)
        //bestPath =
    }
}
