package PathFinding;

import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a junction where multiple OSM ways meet at a node
 * Created by nick on 1/27/16.
 */
public class Junction {
    public final OSMNode junctionNode;
    public final List<PathSegment> junctionPathSegments;

    public Junction(final OSMNode node) {
        junctionNode = node;
        junctionPathSegments = new ArrayList<>(node.containingWayCount);

        //create PathSegments for each intersecting way
        for(final OSMWay junctionWay : node.containingWays.values()) {
            //junctionPathSegments.add(new PathSegment())
        }
    }
}
