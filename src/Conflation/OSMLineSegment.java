package Conflation;

import OSM.OSMNode;
import OSM.Point;

/**
 * Created by nick on 9/30/16.
 */
public class OSMLineSegment extends LineSegment {
    public WaySegments parentSegments = null;

    public OSMLineSegment(WaySegments parentSegments, Point origin, Point destination, OSMNode originNode, OSMNode destinationNode, int segmentIndex, int nodeIndex) {
        super(parentSegments.way.osm_id, origin, destination, originNode, destinationNode, segmentIndex, nodeIndex);
        this.parentSegments = parentSegments;
    }

    @Override
    public WaySegments getParent() {
        return parentSegments;
    }
    @Override
    public void setParent(WaySegments newParent) {
        parentSegments = newParent;
    }

    public OSMLineSegment(LineSegment segmentToCopy, Point destination, OSMNode destinationNode) {
        super(segmentToCopy, destination, destinationNode);
        this.parentSegments = segmentToCopy.getParent();
    }
    public String toString() {
        return String.format("OSMSeg %d #%d/%d [%.01f, %.01f], nd[%d/%d]", parentSegments.way.osm_id, nodeIndex, segmentIndex, midPoint.y, midPoint.x, originNode != null ? originNode.osm_id : 0, destinationNode != null ? destinationNode.osm_id : 0);
    }
}
