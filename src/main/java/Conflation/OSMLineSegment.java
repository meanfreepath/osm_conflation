package Conflation;

import OSM.OSMNode;
import OSM.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Created by nick on 9/30/16.
 */
public class OSMLineSegment extends LineSegment {
    @NotNull
    private WaySegments parentSegments;

    public OSMLineSegment(@NotNull WaySegments parentSegments, @NotNull Point origin, @NotNull Point destination, @Nullable OSMNode originNode, @Nullable OSMNode destinationNode, int segmentIndex, int nodeIndex) {
        super(origin, destination, originNode, destinationNode, segmentIndex, nodeIndex);
        this.parentSegments = parentSegments;
    }
    protected OSMLineSegment(OSMLineSegment segmentToCopy, Point destination, OSMNode destinationNode) {
        super(segmentToCopy, destination, destinationNode);
        this.parentSegments = segmentToCopy.getParent();
    }

    @Override
    @NotNull
    public WaySegments getParent() {
        return parentSegments;
    }
    @Override
    public void setParent(@NotNull WaySegments newParent) {
        parentSegments = newParent;
    }

    @Override
    public String toString() {
        return String.format("OSMSeg #%d (way %d) #%d/%d [%.01f, %.01f], nd[%d/%d]",  id, parentSegments.way.osm_id, segmentIndex, nodeIndex, midPoint.x, midPoint.y, originNode != null ? originNode.osm_id : 0, destinationNode != null ? destinationNode.osm_id : 0);
    }
}
