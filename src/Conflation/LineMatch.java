package Conflation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tracks the match status of a line with another line
 * Created by nick on 11/20/15.
 */
public class LineMatch {
    private final static Comparator<SegmentMatch> comp = new Comparator<SegmentMatch>() {
        @Override
        public int compare(SegmentMatch o1, SegmentMatch o2) {
            return o1.matchingSegment.segmentIndex < o2.matchingSegment.segmentIndex ? -1 : 1;
        }
    };
    public static boolean debug = false;

    public final WaySegments parentLine;
    public final List<SegmentMatch> matchingSegments;
    public int matchingSegmentCount = -1;

    private double avgDotProduct, avgDistance;

    public LineMatch(final WaySegments parentLine) {
        this.parentLine = parentLine;
        matchingSegments = new ArrayList<>(64);
    }
    public void addMatch(final SegmentMatch match) {
        matchingSegments.add(match);
    }

    /**
     * Consolidates all the segment matches and calculates the various totals
     */
    public void summarize(final WaySegments routeLine) {

        /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
          may match multiple segments, and there is a typically good deal of overlap).  We want only
           the unique segment matches.
         */
        for(final LineSegment segment : parentLine.segments) {
            segment.chooseBestMatch(routeLine);
        }
        matchingSegments.clear();

        //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
        avgDotProduct = avgDistance = 0.0;
        for(final LineSegment segment : parentLine.segments) {
            final SegmentMatch bestMatchForLine = segment.bestMatchForLine.get(routeLine.way.osm_id);
            if(bestMatchForLine != null) {
                matchingSegments.add(bestMatchForLine);

                avgDistance += bestMatchForLine.orthogonalDistance;
                avgDotProduct += bestMatchForLine.dotProduct;
            }
        }
        matchingSegmentCount = matchingSegments.size();
        if(matchingSegmentCount > 0) {
            avgDistance /= matchingSegmentCount;
            avgDotProduct /= matchingSegmentCount;
        }
    }
    public double getAvgDotProduct() {
        return avgDotProduct;
    }
    public double getAvgDistance() {
        return avgDistance;
    }
}
