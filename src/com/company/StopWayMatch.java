package com.company;

import OSM.OSMNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by nick on 11/19/15.
 */
public class StopWayMatch {
    public final static double maxDistanceFromPlatformToWay = 25.0, stopNodeTolerance = 3.0, minMatchingSegmentPathDotProduct = 0.9, maxMatchingSegmentPathDistance = 10.0;
    private final static Comparator<WayMatch> comp = new Comparator<WayMatch>() {
        @Override
        public int compare(WayMatch o1, WayMatch o2) {
            return o1.distance < o2.distance ? -1 : 1;
        }
    };
    public enum MatchType {
        none, primaryStreet, crossStreet, proximity
    }

    public class WayMatch {
        public final SegmentMatch candidateSegmentMatch;
        public final MatchType matchType;
        public final double distance;

        public WayMatch(final SegmentMatch segmentMatch, final double distance, final MatchType matchType) {
            this.candidateSegmentMatch = segmentMatch;
            this.distance = distance;
            this.matchType = matchType;
        }
    }

    /**
     * The index of this stop on the route
     */
    public final int stopIndex;

    /**
     * The total number of stops on the route
     */
    public final int stopCount;

    /**
     * Pointer to the actual OSM node of the stop's platform
     */
    public final OSMNode platformNode;
    public final List<WayMatch> matches = new ArrayList<>(16);

    public OSMNode stopPositionNode = null;
    public WayMatch bestMatch = null;

    public StopWayMatch(final OSMNode platform, int index, int count) {
        platformNode = platform;
        stopIndex = index;
        stopCount = count;
    }
    public void addWayMatch(final SegmentMatch segmentMatch, final double distance, final MatchType matchType) {
        matches.add(new WayMatch(segmentMatch, distance, matchType));
    }
    public void chooseBestMatch() {
        Collections.sort(matches, comp);

        //System.out.println("Platform: " + firstMatch.platformNode.getTag("name") + ":::");
        for(final WayMatch otherMatch : matches) {
            if(otherMatch.matchType == StopWayMatch.MatchType.primaryStreet) { //always use the primary street (based off the name) as the best match
                bestMatch = otherMatch;
                break;
            }

            //the next-best match is the closest way
            bestMatch = otherMatch;
        }
    }
    public boolean isFirstStop() {
        return stopIndex == 0;
    }
    public boolean isLastStop() {
        return stopIndex == stopCount - 1;
    }
}
