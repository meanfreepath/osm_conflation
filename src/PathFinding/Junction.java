package PathFinding;

import Conflation.WaySegments;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a junction where multiple OSM ways meet at a node
 * Created by nick on 1/27/16.
 */
public class Junction {
    public enum ProcessStatus {
        none, yes, belowScoreThreshold, isOriginatingWay, nonMatchingLine
    }
    public static class JunctionSegmentStatus {
        public final PathSegment segment;
        public ProcessStatus processStatus = ProcessStatus.none;
        public JunctionSegmentStatus(final PathSegment segment) {
            this.segment = segment;
        }
    }

    public final OSMNode junctionNode;
    public final List<JunctionSegmentStatus> junctionPathSegments;
    public final PathSegment originatingPathSegment;
    private final static Comparator<JunctionSegmentStatus> pathSegmentComparator = new Comparator<JunctionSegmentStatus>() {
        @Override
        public int compare(final JunctionSegmentStatus o1, final JunctionSegmentStatus o2) {
            return o1.segment.getScore() > o2.segment.getScore() ? -1 : 1;
        }
    };

    public Junction(final OSMNode node, final PathSegment originatingPathSegment) {
        junctionNode = node;
        this.originatingPathSegment = originatingPathSegment;
        junctionPathSegments = new ArrayList<>(node.containingWayCount);
        if (this.originatingPathSegment != null) {
            final JunctionSegmentStatus status = new JunctionSegmentStatus(this.originatingPathSegment);
            status.processStatus = ProcessStatus.isOriginatingWay;
            junctionPathSegments.add(status);
        }
    }
    public void sortPathSegmentsByScore() {
        junctionPathSegments.sort(pathSegmentComparator);
    }
}
