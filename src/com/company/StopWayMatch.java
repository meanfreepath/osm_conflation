package com.company;

import OSM.OSMNode;

import java.util.Comparator;

/**
 * Created by nick on 11/19/15.
 */
public class StopWayMatch {
    public enum MatchType {
        none, primaryStreet, crossStreet, proximity
    }

    public final static Comparator<StopWayMatch> comp = new Comparator<StopWayMatch>() {
        @Override
        public int compare(StopWayMatch o1, StopWayMatch o2) {
            return o1.distance < o2.distance ? -1 : 1;
        }
    };

    public final OSMNode platformNode;
    public OSMNode stopPositionNode = null;
    public final LineSegment candidateSegment;
    public final MatchType matchType;
    public final double distance;

    public StopWayMatch(OSMNode platform, LineSegment segment, double distance, MatchType matchType) {
        platformNode = platform;
        candidateSegment = segment;
        this.matchType = matchType;
        this.distance = distance;
    }
}
