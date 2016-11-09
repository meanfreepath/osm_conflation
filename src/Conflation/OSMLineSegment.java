package Conflation;

import OSM.OSMNode;
import OSM.Point;

/**
 * Created by nick on 9/30/16.
 */
public class OSMLineSegment extends LineSegment {
    public WaySegments parentSegments = null;

    public OSMLineSegment(WaySegments parentSegments, RouteConflator routeConflator, Point origin, Point destination, OSMNode originNode, OSMNode destinationNode, int segmentIndex, int nodeIndex) {
        super(routeConflator, origin, destination, originNode, destinationNode, segmentIndex, nodeIndex);
        this.parentSegments = parentSegments;
    }
    protected OSMLineSegment(OSMLineSegment segmentToCopy, RouteConflator routeConflator, Point destination, OSMNode destinationNode) {
        super(routeConflator, segmentToCopy, destination, destinationNode);
        this.parentSegments = segmentToCopy.getParent();
    }

    @Override
    public WaySegments getParent() {
        return parentSegments;
    }
    @Override
    public void setParent(WaySegments newParent) {
        parentSegments = newParent;
    }

    @Override
    public String toString() {
        return String.format("OSMSeg #%d (way %d) #%d/%d [%.01f, %.01f], nd[%d/%d]",  id, parentSegments.way.osm_id, segmentIndex, nodeIndex, midPoint.x, midPoint.y, originNode != null ? originNode.osm_id : 0, destinationNode != null ? destinationNode.osm_id : 0);
    }
    /*@Override
    public void finalize() throws Throwable {
        System.out.println("OSMSEGDELETE " + this);
        super.finalize();
    }*/
}
