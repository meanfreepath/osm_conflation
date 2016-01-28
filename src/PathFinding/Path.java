package PathFinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a possible (non-branched) path between two junctions
 * Created by nick on 1/27/16.
 */
public class Path {
    public final List<PathSegment> pathSegments;

    public Path() {
        pathSegments = new ArrayList<>(64);
    }
}
