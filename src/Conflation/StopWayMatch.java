package Conflation;

import OSM.OSMEntity;

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
        public int compare(final WayMatch o1, final WayMatch o2) {
            return o1.getScore() < o2.getScore() ? 1 : -1;
        }
    };
    public enum MatchType {
        none, primaryStreet, crossStreet, proximity
    }

    public class WayMatch {
        public final LineSegment candidateSegmentMatch;
        public final MatchType matchType;
        public final double distance;

        public WayMatch(final LineSegment segmentMatch, final double distance, final MatchType matchType) {
            this.candidateSegmentMatch = segmentMatch;
            this.distance = distance;
            this.matchType = matchType;
        }
        public double getScore() {
            final double dpFactor, odFactor;
            if(candidateSegmentMatch.bestMatch != null) {
                dpFactor = candidateSegmentMatch.bestMatch.dotProduct;
                odFactor = candidateSegmentMatch.bestMatch.orthogonalDistance;
            } else {
                dpFactor = 0.01;
                odFactor = maxDistanceFromPlatformToWay;
            }
            return scoreFactorForMatchType(matchType) + dpFactor * 5.0 +  1.0 / (odFactor * distance);
        }
        public double scoreFactorForMatchType(MatchType type) {
            switch (type) {
                case primaryStreet: //prioritize name matches
                    return 100.0;
                case crossStreet:
                    return 50.0;
                case proximity:
                    return 25.0;
                default:
                    return 0.0;
            }
        }
    }

    /**
     * Pointer to the actual OSM node of the stop's platform
     */
    public final StopArea stopEntity;
    public final List<WayMatch> matches = new ArrayList<>(16);

    public WayMatch bestMatch = null;

    public StopWayMatch(final StopArea stop) {
        stopEntity = stop;
    }
    public void addWayMatch(final LineSegment segmentMatch, final double distance, final MatchType matchType) {
        matches.add(new WayMatch(segmentMatch, distance, matchType));
    }
    public void chooseBestMatch() {
        if(matches.size() == 0) {
            //System.out.println("NOMATCH: " + stopEntity.platform.getTag("name"));
            return;
        }
        Collections.sort(matches, comp);
        bestMatch = matches.get(0);

        //System.out.println("Platform: " + firstMatch.platformNode.getTag("name") + ":::");
        System.out.println("Platform: " + stopEntity.platform.getTag("name") + ":: on " + bestMatch.candidateSegmentMatch.parentSegments.way.getTag(OSMEntity.KEY_NAME) + "(" + bestMatch.candidateSegmentMatch.parentSegments.way.osm_id + ") with score " + bestMatch.getScore());
        if(bestMatch.getScore() < 100.0) {
            for (final WayMatch otherMatch : matches) {
                System.out.println("\tALTMATCHES: " + stopEntity.platform.getTag("name") + ":: on " + otherMatch.candidateSegmentMatch.parentSegments.way.getTag(OSMEntity.KEY_NAME) + "(" + otherMatch.candidateSegmentMatch.parentSegments.way.osm_id + ") with score " + otherMatch.getScore());
            }
        }
    }
}
