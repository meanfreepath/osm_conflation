package PathFinding;

import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a possible (non-branched) path between two junctions
 * Created by nick on 1/27/16.
 */
public class Path {
    private final static int INITIAL_PATH_SEGMENT_CAPACITY = 64;
    public enum PathOutcome {
        waypointReached, deadEnded, unknown
    }

    private final List<PathSegment> pathSegments;
    public final PathSegment firstPathSegment;
    public PathSegment lastPathSegment;
    public PathOutcome outcome = PathOutcome.unknown;

    public Path(final PathSegment startingSegment) {
        pathSegments = new ArrayList<>(INITIAL_PATH_SEGMENT_CAPACITY);
        pathSegments.add(startingSegment);
        firstPathSegment = lastPathSegment = startingSegment;
    }
    public Path(final Path pathToClone, final PathSegment segmentToAdd) {
        if(pathToClone != null) {
            pathSegments = new ArrayList<>(pathToClone.pathSegments.size() + INITIAL_PATH_SEGMENT_CAPACITY);
            pathSegments.addAll(pathToClone.pathSegments);
            firstPathSegment = pathToClone.firstPathSegment;
        } else {
            pathSegments = new ArrayList<>(INITIAL_PATH_SEGMENT_CAPACITY);
            firstPathSegment = segmentToAdd;
        }
        pathSegments.add(segmentToAdd);
        lastPathSegment = segmentToAdd;
    }
    public void markAsSuccessful(final PathSegment lastPathSegment) {
        pathSegments.add(lastPathSegment);
        this.lastPathSegment = lastPathSegment;
        outcome = PathOutcome.waypointReached;
    }
    public double getTotalScore() {
        double totalScore = 0.0;
        for(final PathSegment segment : pathSegments) {
            totalScore += segment.getScore();
        }
        return totalScore;
    }
    public List<OSMWay> getPathWays() {
        List<OSMWay> ways = new ArrayList<>(pathSegments.size());
        PathSegment lastPathSegment = null;
        for(final PathSegment pathSegment : pathSegments) {
            if(lastPathSegment != null) {
                if(pathSegment.line.way != lastPathSegment.line.way) {
                    ways.add(pathSegment.line.way);
                }
            } else {
                ways.add(pathSegment.line.way);
            }
            lastPathSegment = pathSegment;
        }
        return ways;
    }
    public String toString() {
        final List<String> streets = new ArrayList<>(pathSegments.size());
        for(final PathSegment segment : pathSegments) {
            streets.add(segment.line.way.getTag(OSMEntity.KEY_NAME) + "(" + segment.originJunction.junctionNode.osm_id + " to " + segment.getEndJunction().junctionNode.osm_id + ")");
        }
        return String.join("->", streets);
    }
}
