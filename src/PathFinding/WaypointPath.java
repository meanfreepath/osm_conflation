package PathFinding;

import Conflation.StopArea;
import Conflation.WaySegments;
import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.Point;
import OSM.Region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Represents the possible paths between 2 waypoints (e.g. bus stops)
 * Created by nick on 1/27/16.
 */
public class WaypointPath {
    public final StopArea fromStop, toStop;

    /**
     * A list of the possible paths between our two waypoints.  Because the waypoints may not be located on
     * OSM ways, there may be multiple possible starting or ending ways for us to consider when routing.
     */
    public final List<PathTree> possiblePathTrees;

    public PathTree bestPathTree = null;

    public WaypointPath(final StopArea fromStop, final Conflation.StopArea toStop) {
        this.fromStop = fromStop;
        this.toStop = toStop;

        //filter all the candidateSegments down to only the ones whose bounding boxes intersect the two stops'
        /*final double expansionSize = 50.0;
        final double latitudeDelta = -expansionSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * (fromStop.platform.getCentroid().latitude + toStop.platform.getCentroid().latitude) / 360.0);
        Point[] includedPoints = {this.fromStop.platform.getCentroid(), this.toStop.platform.getCentroid()};
        final Region stopBoundingBox = new Region(includedPoints);
        stopBoundingBox.regionInset(latitudeDelta, longitudeDelta);
        final List<WaySegments>
        for(final WaySegments : candidateSegments) {

        }*/

        //determine the possible starting/ending ways for each stop, and create a PathTree for each one (TODO consider all stop matches)
        possiblePathTrees = new ArrayList<>(8);
        final PathTree tempPath = new PathTree(fromStop.getStopPosition(), toStop.getStopPosition(), fromStop.wayMatches.bestMatch.candidateSegmentMatch.parentSegments, toStop.wayMatches.bestMatch.candidateSegmentMatch.parentSegments);
        possiblePathTrees.add(tempPath);
        bestPathTree = tempPath;
    }
    public void findPaths(final RoutePath parentPath, final WaySegments routeLine) {
        //Start finding paths on each path tree (TODO may also be able to multithread?)
        for(final PathTree pathTree : possiblePathTrees) {
            pathTree.findPaths(parentPath, routeLine);
        }

        //and choose the best PathTree out of them
        //TODO
    }
    public String toString() {
        return getClass().getName() + ": " + fromStop.platform.osm_id + "/" + fromStop.platform.getTag(OSMEntity.KEY_NAME) + " to " + toStop.platform.osm_id + "/" + toStop.platform.getTag(OSMEntity.KEY_NAME);
    }
}
