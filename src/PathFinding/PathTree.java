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
    public final static int MAX_PATHS_TO_CONSIDER = 32;
    private final static Comparator<Path> pathScoreComparator = new Comparator<Path>() {
        @Override
        public int compare(final Path o1, final Path o2) {
            return o1.getTotalScore() > o2.getTotalScore() ? -1 : 1;
        }
    };
    private final static double scoreThresholdToProcessPathSegment = 3.0;

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
    private void iteratePath(final Junction startingJunction, final WaySegments routeLine, final RoutePath parentPath, final Path curPath) throws PathIterationException {
        //bail if the junction indicates there's no need to continue processing
        if(!processJunction(startingJunction, routeLine, parentPath, curPath)) {
            return;
        }

        System.out.println("FROM " + (startingJunction.originatingPathSegment != null ? startingJunction.originatingPathSegment.line.way.getTag("name") + " (" + startingJunction.originatingPathSegment.line.way.osm_id + "): " : "BEGIN: ") + startingJunction.junctionPathSegments.size() + " TO PROCESS: ordered list:");
        for(final Junction.JunctionSegmentStatus segmentStatus : startingJunction.junctionPathSegments) {
            if(segmentStatus.segment.isLineMatchedWithRoutePath()) {
                System.out.println("\t" + segmentStatus.segment.line.way.getTag("name") + "(" + segmentStatus.segment.originJunction.junctionNode.osm_id + "/" + segmentStatus.segment.getEndJunction().junctionNode.osm_id + "): " + segmentStatus.segment.getScore() + ":" + segmentStatus.processStatus.name());
            } else {
                System.out.println("\t" + segmentStatus.segment.line.way.getTag("name") + "(" + segmentStatus.segment.originJunction.junctionNode.osm_id + "/NoMatch): " + segmentStatus.segment.getScore() + ":" + segmentStatus.processStatus.name());
            }
        }

        int processedSegments = 0;
        for(final Junction.JunctionSegmentStatus segmentStatus : startingJunction.junctionPathSegments) {
            parentPath.logEvent(RoutePath.RouteLogType.info, "Junction " + startingJunction.junctionNode.osm_id + ": " + segmentStatus.segment + ": " +  Math.round(100.0 * segmentStatus.segment.pathScore)/100.0 + "/" + Math.round(100.0 * segmentStatus.segment.lengthFactor)/100.0 + "%/" + Math.round(100.0 * segmentStatus.segment.waypointScore)/100.0 + "=" + Math.round(segmentStatus.segment.getScore()) + ", STATUS " + segmentStatus.processStatus.name(), this);
            if(segmentStatus.processStatus != Junction.ProcessStatus.yes) {
                continue;
            }

            //bail if we've iterated too many paths - unlikely to find anything if we've reached this stage
            if(candidatePaths.size() > MAX_PATHS_TO_CONSIDER) {
                parentPath.logEvent(RoutePath.RouteLogType.warning, "Reached maximum paths to consider - unable to find a suitable path", this);
                throw new PathIterationException("Reached maximum paths");
            }

            //process the ending junction of the current segment, but only if its line is properly matched with the route path
            if(segmentStatus.segment.isLineMatchedWithRoutePath()) {
                final Path newPath = createPath(curPath, segmentStatus.segment);
                iteratePath(segmentStatus.segment.getEndJunction(), routeLine, parentPath, newPath);
            } else {
                parentPath.logEvent(RoutePath.RouteLogType.warning, "PathSegment for " + segmentStatus.segment.line.way.getTag(OSMEntity.KEY_NAME) + "(" + segmentStatus.segment.line.way.osm_id + ") doesn't match route line - skipping", this);
            }
            processedSegments++;
        }

        if(processedSegments == 0) { //dead end path
            parentPath.logEvent(RoutePath.RouteLogType.notice, "Dead end at node #" + startingJunction.junctionNode.osm_id, this);
            curPath.outcome = Path.PathOutcome.deadEnded;
        }
    }
    private Path createPath(final Path parent, final PathSegment segmentToAdd) {
        final Path newPath = new Path(parent, segmentToAdd);
        candidatePaths.add(newPath);
        return newPath;
    }
    private boolean processJunction(final Junction junction, final WaySegments routeLine, final RoutePath parentPath, final Path currentPath) {
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
                if(currentPath != null) {
                    parentPath.logEvent(RoutePath.RouteLogType.info, "FOUND destination on " + wayPathSegment.line.way.getTag(OSMEntity.KEY_NAME)  + "(" + wayPathSegment.line.way.osm_id + "), node " + toNode.getTag(OSMEntity.KEY_NAME), this);
                    currentPath.markAsSuccessful(wayPathSegment);
                } else { //if currentPath isn't present, it's because we've found a stop on the first segment.  Create the path and bail
                    final Path newPath = createPath(null, null);
                    newPath.markAsSuccessful(wayPathSegment);
                    parentPath.logEvent(RoutePath.RouteLogType.info, "FOUND destination on first segment: " + wayPathSegment.line.way.getTag(OSMEntity.KEY_NAME) + "(" + wayPathSegment.line.way.osm_id + "), node " + toNode.getTag(OSMEntity.KEY_NAME), this);
                }
                return false;
            }

            final Junction.JunctionSegmentStatus segmentStatus = new Junction.JunctionSegmentStatus(wayPathSegment);
            if(wayPathSegment.isLineMatchedWithRoutePath()) {
                //if(wayPathSegment.)
                if (wayPathSegment.getScore() >= scoreThresholdToProcessPathSegment) {
                    segmentStatus.processStatus = Junction.ProcessStatus.yes;
                } else {
                    segmentStatus.processStatus = Junction.ProcessStatus.belowScoreThreshold;
                }
            } else {
                segmentStatus.processStatus = Junction.ProcessStatus.nonMatchingLine;
            }
            junction.junctionPathSegments.add(segmentStatus);
        }
        junction.sortPathSegmentsByScore();

        return true; //indicate further processing is necessary
    }
    public void findPaths(final RoutePath parentPath, final WaySegments routeLine) {
        //starting with the first line, determine the initial direction of travel
        final Junction startingJunction = new Junction(fromNode, null);
        try {
            iteratePath(startingJunction, routeLine, parentPath, null);
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
        fromLine.getMatchForLine(routeLine).getAvgDotProduct();


        //we then determine the best path based on score (TODO maybe try shortcuts etc here)
        //bestPath =
    }
}
