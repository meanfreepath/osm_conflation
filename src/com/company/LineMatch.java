package com.company;

import java.util.*;

/**
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
    public final List<StopWayMatch> stopMatches = new ArrayList<>(4);
    private double avgDotProduct, avgDistance, score = 0.0;

    public LineMatch(final WaySegments parentLine) {
        this.parentLine = parentLine;
        matchingSegments = new ArrayList<>(64);
    }
    public void addMatch(SegmentMatch match) {
        matchingSegments.add(match);
    }

    /**
     * Consolidates all the segment matches and calculates the various totals
     */
    public void summarize() {

        /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
          may match multiple segments, and there is a typically good deal of overlap).  We want only
           the unique segment matches.
         */
        for(final LineSegment segment : parentLine.segments) {
            segment.chooseBestMatch();
        }
        matchingSegments.clear();

        //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
        avgDotProduct = avgDistance = 0.0;
        for(final LineSegment segment : parentLine.segments) {
            if(segment.bestMatch != null) {
                matchingSegments.add(segment.bestMatch);

                avgDistance += segment.bestMatch.orthogonalDistance;
                avgDotProduct += segment.bestMatch.dotProduct;
            }
        }
        matchingSegmentCount = matchingSegments.size();
        if(matchingSegmentCount > 0) {
            avgDistance /= matchingSegmentCount;
            avgDotProduct /= matchingSegmentCount;
        }

        //calculate a match score for this line
        score = 100 * stopMatches.size() + matchingSegmentCount * 10 + Math.abs(avgDotProduct);





/*
        HashMap<Integer, SegmentMatch> matchMap = new HashMap<>(matchingSegments.size());
        SegmentMatch curMatch;
        for(final SegmentMatch match : matchingSegments) {
            curMatch = matchMap.get(match.matchingSegment.segmentIndex);

            //use the new match if it's qualitatively better than the current best match
            if(curMatch == null || Math.abs(match.dotProduct) >= Math.abs(curMatch.dotProduct) && match.orthogonalDistance < curMatch.orthogonalDistance) {
                matchMap.put(match.matchingSegment.segmentIndex, match);
                curMatch = match;
                curMatch.consolidated = true;
            }
            curMatch.consolidatedMatches++;
        }
        matchingSegments.clear();

        //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
        avgDotProduct = avgDistance = 0.0;
        for(final SegmentMatch match : matchMap.values()) {
            matchingSegments.add(match);

            avgDistance += match.orthogonalDistance;
            avgDotProduct += Math.abs(match.dotProduct);
        }
        matchingSegmentCount = matchingSegments.size();
        if(matchingSegmentCount > 0) {
            avgDistance /= matchingSegmentCount;
            avgDotProduct /= matchingSegmentCount;
        }

        //calculate a match score for this line
        score = 100 * stopMatches.size() + matchingSegmentCount * 10 + avgDotProduct;

        //sort in debug mode, to ensure a nice output for segments
        if(debug) {
            Collections.sort(matchingSegments, comp);
        }*/
    }
    public double getAvgDotProduct() {
        return avgDotProduct;
    }
    public double getAvgDistance() {
        return avgDistance;
    }
    public double getScore(){
        return score;
    }
}
