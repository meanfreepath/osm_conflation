package PathFinding;

import com.company.WaySegments;

/**
 * Represents the portion of a way that is traversed by a route's path
 * Created by nick on 1/27/16.
 */
public class PathSegment {
    public final Junction originJunction, endJunction;
    public final WaySegments line;
    public double pathScore, waypointScore, directionScore;

    public PathSegment(final WaySegments line, final Junction fromJunction, final Junction toJunction) {
        this.line = line;
        originJunction = fromJunction;
        endJunction = toJunction;
    }
}
