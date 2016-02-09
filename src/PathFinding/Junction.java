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
        none, yes, belowScoreThreshold, isOriginatingWay, nonMatchingLine
    }
    public enum JunctionProcessStatus {
        continuePath, deadEnd
    }
    public static class JunctionSegmentStatus {
        public final PathSegment segment;
        public PathSegmentProcessStatus processStatus = PathSegmentProcessStatus.none;
        public JunctionSegmentStatus(final PathSegment segment) {
            this.segment = segment;
        }
    }

    public final OSMNode junctionNode;
    public final List<JunctionSegmentStatus> junctionPathSegments;
    public final PathSegment originatingPathSegment;
    public final JunctionProcessStatus processStatus;
    private final static Comparator<JunctionSegmentStatus> pathSegmentComparator = new Comparator<JunctionSegmentStatus>() {
        @Override
        public int compare(final JunctionSegmentStatus o1, final JunctionSegmentStatus o2) {
            return o1.segment.getScore() > o2.segment.getScore() ? -1 : 1;
        }
    };

    public Junction(final OSMNode node, final PathSegment originatingPathSegment, final JunctionProcessStatus processStatus) {
        junctionNode = node;
        this.originatingPathSegment = originatingPathSegment;
        this.processStatus = processStatus;
        junctionPathSegments = new ArrayList<>(node.containingWayCount);
        if (this.originatingPathSegment != null) {
            final JunctionSegmentStatus status = new JunctionSegmentStatus(this.originatingPathSegment);
            status.processStatus = PathSegmentProcessStatus.isOriginatingWay;
            junctionPathSegments.add(status);
        }
    }
    public void sortPathSegmentsByScore() {
        junctionPathSegments.sort(pathSegmentComparator);
    }
}
