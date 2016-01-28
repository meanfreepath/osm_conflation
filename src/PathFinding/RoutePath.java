package PathFinding;

import OSM.OSMRelation;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains all the stop-to-stop paths for a route
 * Created by nick on 1/27/16.
 */
public class RoutePath {
    public final OSMRelation routeRelation;
    public final List<WaypointPath> allPaths;

    public RoutePath(final OSMRelation route) {
        routeRelation = route;
        allPaths = new ArrayList<>(route.getMembers("").size() + 1);

        //get the stop matches from the route, creating a new
    }
}
