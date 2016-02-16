package PathFinding;

import OSM.OSMNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a junction where multiple OSM ways meet at a node
 * Created by nick on 1/27/16.
 */
public class Junction {
    public enum PathSegmentProcessStatus {
        none, yes, belowScoreThreshold, isOriginatingWay, nonMatchingLine, containsDestination
    }
    public enum JunctionProcessStatus {
        continuePath, deadEnd
    }
    public static class PathSegmentStatus {
        public final PathSegment segment;
        public final PathSegmentProcessStatus processStatus;
        public PathSegmentStatus(final PathSegment segment, final PathSegmentProcessStatus processStatus) {
            this.segment = segment;
            this.processStatus = processStatus != null ? processStatus : PathSegmentProcessStatus.none;
        }
    }

    public final OSMNode junctionNode;
    public final List<PathSegmentStatus> junctionPathSegments;
    protected PathSegmentStatus originatingPathSegment;
    public final JunctionProcessStatus processStatus;
    private final static Comparator<PathSegmentStatus> pathSegmentComparator = new Comparator<PathSegmentStatus>() {
        @Override
        public int compare(final PathSegmentStatus o1, final PathSegmentStatus o2) {
            return o1.segment.getScore() > o2.segment.getScore() ? -1 : 1;
        }
    };

    public Junction(final OSMNode node, final PathSegment originatingPathSegment, final JunctionProcessStatus processStatus) {
        junctionNode = node;
        this.processStatus = processStatus;
        junctionPathSegments = new ArrayList<>(node.containingWayCount);
        if (originatingPathSegment != null) {
            this.originatingPathSegment = addPathSegment(originatingPathSegment, PathSegmentProcessStatus.isOriginatingWay);
        } else {
            this.originatingPathSegment = null;
        }
    }
    public void sortPathSegmentsByScore() {
        junctionPathSegments.sort(pathSegmentComparator);
    }
    public PathSegmentStatus addPathSegment(final PathSegment pathSegment, final Junction.PathSegmentProcessStatus processStatus) {
        final PathSegmentStatus segmentStatus = new PathSegmentStatus(pathSegment, processStatus);
        junctionPathSegments.add(segmentStatus);
        return segmentStatus;
    }
    public PathSegmentStatus replacePathSegment(final PathSegment originalPathSegment, final PathSegment newPathSegment) {
        PathSegmentStatus originalSegmentStatus = null;
        for(final PathSegmentStatus segmentStatus : junctionPathSegments) {
            if(segmentStatus.segment == originalPathSegment) {
                originalSegmentStatus = segmentStatus;
                break;
            }
        }

        assert originalSegmentStatus != null;
        final PathSegmentStatus newSegmentStatus = new PathSegmentStatus(newPathSegment, originalSegmentStatus.processStatus);
        junctionPathSegments.set(junctionPathSegments.indexOf(originalSegmentStatus), newSegmentStatus);

        //update the originating segment too, if the originalPathSegment was the originating segment
        if(originalSegmentStatus == originatingPathSegment) {
            originatingPathSegment = newSegmentStatus;
        }
        return newSegmentStatus;
    }
    public String toString() {
        return String.format("Junction@%d: node %d (%d): status %s", hashCode(), junctionNode.hashCode(), junctionNode.osm_id, processStatus.toString());
    }
}
