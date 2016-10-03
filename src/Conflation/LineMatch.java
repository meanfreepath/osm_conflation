package Conflation;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for tracking the match status between two WaySegmentsObjects, including
 * match scores for the specific LineSegments
 */
public class LineMatch {
    public final List<SegmentMatch> matchingSegments;
    public final RouteLineWaySegments routeLine;
    public final OSMWaySegments osmLine;
    private boolean summarized = false;

    private double avgDotProduct, avgDistance;

    public LineMatch(final RouteLineWaySegments routeLine, final OSMWaySegments osmLine) {
        this.routeLine = routeLine;
        this.osmLine = osmLine;
        matchingSegments = new ArrayList<>(routeLine.segments.size());
    }

    /**
     * Add a match to the
     * @param match
     */
    public void addMatch(final SegmentMatch match) {
        if(match.matchingSegment.getParent() != osmLine) {
            throw new RuntimeException("Tried to add match for different line: " + this.toString());
        }
        matchingSegments.add(match);
    }
    private void resyncMatchesForSegments(final List<LineSegment> segments) {
        final List<SegmentMatch> matchesToRemove = new ArrayList<>(matchingSegments.size());
        for(final SegmentMatch match : matchingSegments) {
            if(!segments.contains(match.matchingSegment)) {
                matchesToRemove.add(match);
            }
        }
        //System.out.println("Removed " + matchesToRemove.size() + " segment matches: " + matchingSegments.size() + " left");
        matchingSegments.removeAll(matchesToRemove);
    }

    /**
     * Consolidates all the segment matches and calculates the various totals
     */
    public void summarize() {
        final List<SegmentMatch> oldMatchingSegments = new ArrayList<>(matchingSegments);
        matchingSegments.clear();

        //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
        avgDotProduct = avgDistance = 0.0;
        for (final SegmentMatch match : oldMatchingSegments) {
            final SegmentMatch bestMatchForLine = match.mainSegment.bestMatchForLine.get(osmLine.way.osm_id);
            if (bestMatchForLine != null) {
                matchingSegments.add(bestMatchForLine);

                avgDistance += bestMatchForLine.orthogonalDistance;
                avgDotProduct += bestMatchForLine.dotProduct;
            }
        }
        final int matchingSegmentCount = matchingSegments.size();
        if (matchingSegmentCount > 0) {
            avgDistance /= matchingSegmentCount;
            avgDotProduct /= matchingSegmentCount;
        }
        summarized = true;
    }
    public boolean isSummarized() {
        return summarized;
    }
    public double getAvgDotProduct() {
        return avgDotProduct;
    }
    public double getAvgDistance() {
        return avgDistance;
    }
    @Override
    public String toString() {
        return "LineMatch: " + routeLine + ":" + osmLine + " (" + matchingSegments.size() + " matching segments)";
    }
}
