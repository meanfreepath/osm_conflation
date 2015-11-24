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
    public final static double maxDistanceFromPlatformToWay = 25.0, stopNodeTolerance = 3.0;
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
        public final LineSegment candidateSegment;
        public final MatchType matchType;
        public final double distance;

        public WayMatch(final LineSegment segment, final double distance, final MatchType matchType) {
            this.candidateSegment = segment;
            this.distance = distance;
            this.matchType = matchType;
        }
    }

    public final OSMNode platformNode;
    public final List<WayMatch> matches = new ArrayList<>(16);

    public OSMNode stopPositionNode = null;
    public WayMatch bestMatch = null;

    public StopWayMatch(final OSMNode platform) {
        platformNode = platform;
    }
    public void addWayMatch(final LineSegment segment, final double distance, final MatchType matchType) {
        matches.add(new WayMatch(segment, distance, matchType));
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
}
