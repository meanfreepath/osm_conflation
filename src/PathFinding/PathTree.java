package PathFinding;

import OSM.OSMNode;
import Conflation.WaySegments;

/**
 * Represents the possible paths between 2 nodes
 * Created by nick on 1/27/16.
 */
public class PathTree {
    public final OSMNode fromNode, toNode;
    public final WaySegments fromLine, toLine;

    public PathTree(final OSMNode fromNode, final OSMNode toNode, final WaySegments fromLine, final WaySegments toLine) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.fromLine = fromLine;
        this.toLine = toLine;
    }
}
