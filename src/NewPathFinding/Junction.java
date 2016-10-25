package NewPathFinding;

import OSM.OSMNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 10/20/16.
 */
public class Junction {
    public final OSMNode junctionNode;
    public final List<PathSegment> junctionPathSegments;

    public Junction(final OSMNode node) {
        junctionNode = node;
        junctionPathSegments = new ArrayList<>(node.containingWayCount);
    }
    public void addPathSegment(final PathSegment pathSegment) {
        junctionPathSegments.add(pathSegment);
    }
}
