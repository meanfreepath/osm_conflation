package Conflation;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;

import java.util.List;

/**
 * Created by nick on 9/30/16.
 */
public class OSMWaySegments extends WaySegments {

    public OSMWaySegments(OSMWay way, double maxSegmentLength) {
        super(way, maxSegmentLength);
    }
    protected OSMWaySegments(final OSMWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);
    }
    @Override
    protected LineSegment createLineSegment(final Point miniOrigin, final Point miniDestination, final OSMNode miniOriginNode, final OSMNode miniDestinationNode, int segmentIndex, int nodeIndex) {
        return new OSMLineSegment(this, miniOrigin, miniDestination, miniOriginNode, miniDestinationNode, segmentIndex, nodeIndex);
    }
    @Override
    protected LineSegment copyLineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        return new OSMLineSegment(segmentToCopy, destination, destinationNode);
    }
}
