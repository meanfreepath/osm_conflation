package Conflation;

import java.util.*;

/**
 * Class for tracking the match status between OSMWays and RouteLineSegments, including
 * match scores for the specific LineSegments.  Only one LineMatch should exist per OSM way
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
    protected void addMatch(final SegmentMatch match) {
        if(match.matchingSegment.getParent() != osmLine) {
            throw new RuntimeException("Tried to add match for different line: " + this.toString());
        }

        if(matchingSegments.contains(match)) { //shouldn't happen
            System.out.println("ALREADY CONTAINS " + match);
            return;
        }

        matchingSegments.add(match);
        //System.out.println("ADD SM " + match);

        //update the index keyed by the mainSegment's Id property
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
    protected void removeMatch(final SegmentMatch oldMatch) {
        if(!matchingSegments.contains(oldMatch)) {
            System.out.println("MATCH DOESN'T EXIST " + oldMatch);
            return;
        }
        matchingSegments.remove(oldMatch);
        bestMatchingSegments.remove(oldMatch);

        //remove from the RouteLineSegment index
        final List<SegmentMatch> routeLineMatches = matchedSegmentsByRouteLineSegmentId.get(oldMatch.mainSegment.id);
        if(routeLineMatches != null) {
            routeLineMatches.remove(oldMatch);
            if(routeLineMatches.size() == 0) {
                matchedSegmentsByRouteLineSegmentId.remove(oldMatch.mainSegment.id);
            }
        }

        //and from the OSMLineSegmentIndex
        final List<SegmentMatch> osmLineMatches = matchedSegmentsByOSMLineSegmentId.get(oldMatch.matchingSegment.id);
        if(osmLineMatches != null) {
            osmLineMatches.remove(oldMatch);
            if(osmLineMatches.size() == 0) {
                matchedSegmentsByOSMLineSegmentId.remove(oldMatch.matchingSegment.id);
            }
        }
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

    /**
     * Consolidates all the segment matches and calculates the various totals
     */
    protected void summarize() {
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
        return String.format("LineMatch %s:%s(%d matching, %.02f Avg DP, %.02f Avg Dist, %ssummarized)", routeLine, osmLine, matchingSegments.size(), avgDotProduct, avgDistance, summarized ? "" : "NOT ");
    }
}
