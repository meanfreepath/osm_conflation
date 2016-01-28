package PathFinding;

import OSM.OSMNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the possible paths between 2 waypoints (e.g. bus stops)
 * Created by nick on 1/27/16.
 */
public class WaypointPath {
    public final OSMNode fromWaypoint, toWaypoint;

    /**
     * A list of the possible paths between our two waypoints.  Because the waypoints may not be located on
     * OSM ways, there may be multiple possible starting or ending ways for us to consider when routing.
     */
    public final List<PathTree> possiblePaths;

    public WaypointPath(final OSMNode fromPoint, final OSMNode toPoint) {
        fromWaypoint = fromPoint;
        toWaypoint = toPoint;

        possiblePaths = new ArrayList<>();

        //determine the possible starting/ending ways for each stop, and create a PathTree for each one
    }
}
