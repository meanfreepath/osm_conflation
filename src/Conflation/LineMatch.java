package Conflation;

import java.util.*;

/**
 * Class for tracking the match status between two WaySegmentsObjects, including
 * match scores for the specific LineSegments
 */
public class LineMatch {
    public final List<SegmentMatch> matchingSegments, bestMatchingSegments;
    /**
     * The matches, keyed by the matchingSegment's id
     */
    protected final Map<Long, List<SegmentMatch>> matchedSegmentsByRouteLineSegmentId, matchedSegmentsByOSMLineSegmentId;
    public final RouteLineWaySegments routeLine;
    public final OSMWaySegments osmLine;
    private boolean summarized = false;

    private double avgDotProduct, avgDistance;

    public LineMatch(final RouteLineWaySegments routeLine, final OSMWaySegments osmLine) {
        this.routeLine = routeLine;
        this.osmLine = osmLine;
        matchingSegments = new ArrayList<>(routeLine.segments.size());
        bestMatchingSegments = new ArrayList<>(routeLine.segments.size());
        matchedSegmentsByRouteLineSegmentId = new HashMap<>(32);
        matchedSegmentsByOSMLineSegmentId = new HashMap<>(32);
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

        //also group the segments by the matchingSegment's Id property
        List<SegmentMatch> routeLineBySegment = matchedSegmentsByRouteLineSegmentId.get(match.mainSegment.id);
        if(routeLineBySegment == null) {
            routeLineBySegment = new ArrayList<>(16);
            matchedSegmentsByRouteLineSegmentId.put(match.mainSegment.id, routeLineBySegment);
        }
        routeLineBySegment.add(match);

        //also group the segments by the matchingSegment's Id property
        List<SegmentMatch> osmLineBySegment = matchedSegmentsByOSMLineSegmentId.get(match.matchingSegment.id);
        if(osmLineBySegment == null) {
            osmLineBySegment = new ArrayList<>(16);
            matchedSegmentsByOSMLineSegmentId.put(match.matchingSegment.id, osmLineBySegment);
        }
        osmLineBySegment.add(match);
    }

    private static List<SegmentMatch> applyMask(final List<SegmentMatch> segmentMatches, final short matchMask) {
        if(matchMask == SegmentMatch.matchTypeNone) {
            return segmentMatches;
        } else {
            final List<SegmentMatch> byType = new ArrayList<>(segmentMatches.size());
            for(final SegmentMatch match : segmentMatches) {
                if((match.type & matchMask) == matchMask) {
                    byType.add(match);
                }
            }
            return byType;
        }
    }
    /**
     * Returns the SegmentMatches for the RouteLineSegments that matched with this line and given segment id
     * @param osmSegment
     * @param matchMask
     * @return
     */
    public List<SegmentMatch> getRouteLineMatchesForSegment(final OSMLineSegment osmSegment, final short matchMask) {
        final List<SegmentMatch> bySegment = matchedSegmentsByOSMLineSegmentId.get(osmSegment.id);
        if(bySegment == null) {
            return new ArrayList<>();
        }
        return applyMask(bySegment, matchMask);
    }
    /**
     * Returns the SegmentMatches for the OSMLineSegments that matched with this line and given segment id
     * @param routeLineSegment
     * @param matchMask
     * @return
     */
    public List<SegmentMatch> getOSMLineMatchesForSegment(final RouteLineSegment routeLineSegment, final short matchMask) {
        final List<SegmentMatch> bySegment = matchedSegmentsByRouteLineSegmentId.get(routeLineSegment.id);
        if(bySegment == null) {
            return new ArrayList<>();
        }
        return applyMask(bySegment, matchMask);
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
        bestMatchingSegments.clear();

        //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
        avgDotProduct = avgDistance = 0.0;
        for (final SegmentMatch match : matchingSegments) {
            final SegmentMatch bestMatchForLine = match.mainSegment.bestMatchForLine.get(osmLine.way.osm_id);
            if (bestMatchForLine != null) {
                bestMatchingSegments.add(bestMatchForLine);

                avgDistance += bestMatchForLine.orthogonalDistance;
                avgDotProduct += bestMatchForLine.dotProduct;
            }
        }
        final int matchingSegmentCount = bestMatchingSegments.size();
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
