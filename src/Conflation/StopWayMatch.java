package Conflation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by nick on 11/19/15.
 */
public class StopWayMatch {
    public final static double maxDistanceFromPlatformToWay = 25.0, stopNodeTolerance = 3.0, minMatchingSegmentPathDotProduct = 0.7;
    private final static Comparator<WayMatch> comp = new Comparator<WayMatch>() {
        @Override
        public int compare(final WayMatch o1, final WayMatch o2) {
            if(o1.matchType == MatchType.primaryStreet) { //prioritize name matches
                if(o2.matchType == MatchType.primaryStreet) {
                    return o1.distance < o2.distance ? -1 : 1;
                }
                return -1;
            } else if(o2.matchType == MatchType.primaryStreet) {
                return 1;
            } else {
                return o1.distance < o2.distance ? -1 : 1;
            }
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
            return;
        }
        Collections.sort(matches, comp);
        bestMatch = matches.get(0);

        //System.out.println("Platform: " + firstMatch.platformNode.getTag("name") + ":::");
        /*for(final WayMatch otherMatch : matches) {
            if(otherMatch.matchType == StopWayMatch.MatchType.primaryStreet) { //always use the primary street (based off the name) as the best match
                bestMatch = otherMatch;
                break;
            }

            //the next-best match is the closest way
            bestMatch = otherMatch;
        }*/
        //System.out.println("Platform: " + stopEntity.platform.getTag("name") + ":: on " + bestMatch.candidateSegmentMatch.parentSegments.way.getTag("name"));
    }
}
