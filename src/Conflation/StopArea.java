package Conflation;

import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.Point;
import OSM.Region;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.*;

/**
 * Class that associates stop platforms with their respective node on the appropriate way
 * Created by nick on 1/27/16.
 */
public class StopArea implements WaySegmentsObserver {
    private final static Comparator<StopWayMatch> STOP_WAY_MATCH_COMPARATOR = new Comparator<StopWayMatch>() {
        @Override
        public int compare(final StopWayMatch o1, final StopWayMatch o2) {
            return o1.getScore() < o2.getScore() ? 1 : -1;
        }
    };

    public final static double maxDistanceFromPlatformToWay = 25.0, stopNodeTolerance = 3.0;
    public final static boolean debugEnabled = false;
    public OSMEntity platform; //can be a node or way
    private OSMNode stopPosition = null;
    public final Region nearbyWaySearchRegion;

    public enum SegmentMatchType {
        none, primaryStreet, crossStreet, proximityToOSMWay
    }

    public class StopWayMatch {
        public class StopSegmentMatch {
            public final LineSegment candidateSegment;
            public final WaySegments routeLine;
            public final SegmentMatchType matchType;
            public final double distance;

            public StopSegmentMatch(final WaySegments toRouteLine, final LineSegment segment, final double distance, final SegmentMatchType matchType) {
                this.candidateSegment = segment;
                this.distance = distance;
                this.matchType = matchType;
                this.routeLine = toRouteLine;
            }
        }

        public final WaySegments line;
        public LineSegment closestSegmentToStop = null;
        public final List<StopSegmentMatch> proximityMatches = new ArrayList<>(8);
        private double nameScore = 0.0, dpScore = 0.0, distanceScore = 0.0;

        public StopWayMatch(final WaySegments candidateLine) {
            line = candidateLine;
        }
        public void addStopSegmentMatch(final WaySegments toRouteLine, final LineSegment segment, final double distance, final SegmentMatchType matchType) {
            proximityMatches.add(new StopSegmentMatch(toRouteLine, segment, distance, matchType));
        }
        public void summarizeMatches() {
            dpScore = distanceScore = 0.0;
            if(proximityMatches.size() == 0) {
                return;
            }

            for(final StopSegmentMatch segmentMatch : proximityMatches) {
                final double dpFactor, odFactor;
                final SegmentMatch bestMatchForRouteLine = segmentMatch.candidateSegment.bestMatchForLine.get(segmentMatch.routeLine.way.osm_id);
                if(bestMatchForRouteLine != null) {
                    dpFactor = Math.abs(bestMatchForRouteLine.dotProduct);
                    odFactor = bestMatchForRouteLine.orthogonalDistance;
                } else {
                    dpFactor = 0.01;
                    odFactor = maxDistanceFromPlatformToWay;
                }

                dpScore += dpFactor * scoreFactorForMatchType(segmentMatch.matchType);
                distanceScore += 1.0 / (odFactor * segmentMatch.distance) * scoreFactorForMatchType(segmentMatch.matchType);
            }

            //determine the closest segment on the line to this stop
            LineSegment nearestSegment = null;
            double minDistance = StopArea.maxDistanceFromPlatformToWay;
            Point closestPoint;
            for(final LineSegment segment : line.segments) {
                closestPoint = segment.closestPointToPoint(platform.getCentroid());
                final double segmentDistance = Point.distance(closestPoint, platform.getCentroid());
                if(segmentDistance < minDistance) {
                    nearestSegment = segment;
                    minDistance = segmentDistance;
                }
            }
            closestSegmentToStop = nearestSegment;
        }
        public double getScore() {
            return nameScore + dpScore + distanceScore;
        }
    }
    public StopWayMatch bestWayMatch = null;
    public final Map<Long, StopWayMatch> wayMatches = new HashMap<>(16);

    public StopArea(final OSMEntity platform, final OSMNode stopPosition) {
        this.platform = platform;
        this.stopPosition = stopPosition;

        final double latitudeDelta = -maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * platform.getCentroid().latitude / 180.0);
        nearbyWaySearchRegion = platform.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
    }
    public OSMNode getStopPosition() {
        return stopPosition;
    }
    public void setStopPosition(final OSMNode stopNode) {
        stopPosition = stopNode;
    }
    public void addNameMatch(final WaySegments line, final SegmentMatchType matchType) {
        StopWayMatch wayMatch = wayMatches.get(line.way.osm_id);
        if(wayMatch == null) {
            wayMatch = new StopWayMatch(line);
            wayMatches.put(line.way.osm_id, wayMatch);
        }
        wayMatch.nameScore += scoreFactorForMatchType(matchType);

        line.addObserver(this);
    }
    public void addProximityMatch(final WaySegments toRouteLine, final LineSegment segment, final double distance, final SegmentMatchType matchType) {
        StopWayMatch wayMatch = wayMatches.get(segment.parentSegments.way.osm_id);
        if(wayMatch == null) {
            wayMatch = new StopWayMatch(segment.parentSegments);
            wayMatches.put(segment.parentSegments.way.osm_id, wayMatch);
        }
        wayMatch.addStopSegmentMatch(toRouteLine, segment, distance, matchType);

        segment.parentSegments.addObserver(this);
    }
    public void chooseBestWayMatch() {
        if(wayMatches.size() == 0) {
            return;
        }
        for(final StopWayMatch wayMatch : wayMatches.values()) {
            wayMatch.summarizeMatches();
        }

        final List<StopWayMatch> matchList = new ArrayList<>(wayMatches.values());
        Collections.sort(matchList, STOP_WAY_MATCH_COMPARATOR);
        bestWayMatch = matchList.get(0);

        if(debugEnabled) {
            System.out.println("BEST PLATFORM MATCH: " + platform.getTag("name") + "(" + platform.getTag("ref") + "):: on " + bestWayMatch.line.way.getTag(OSMEntity.KEY_NAME) + "(" + bestWayMatch.line.way.osm_id + ") with score " + bestWayMatch.nameScore + "/" + bestWayMatch.dpScore + "/" + bestWayMatch.distanceScore + ": " + (bestWayMatch.nameScore + bestWayMatch.dpScore + bestWayMatch.distanceScore));
            if ((bestWayMatch.nameScore + bestWayMatch.dpScore + bestWayMatch.distanceScore) < 1000.0) {
                for (final StopWayMatch otherMatch : matchList) {
                    if (otherMatch == bestWayMatch) {
                        continue;
                    }
                    System.out.println("\tALTMATCHES: " + platform.getTag("name") + ":: on " + otherMatch.line.way.getTag(OSMEntity.KEY_NAME) + "(" + otherMatch.line.way.osm_id + ") with score " + otherMatch.nameScore + "/" + otherMatch.dpScore + "/" + otherMatch.distanceScore + ": " + (otherMatch.nameScore + otherMatch.dpScore + otherMatch.distanceScore));
                }
            }
        }
    }
    private static double scoreFactorForMatchType(final SegmentMatchType type) {
        switch (type) {
            case primaryStreet: //prioritize name matches
                return 100.0;
            case crossStreet:
                return 50.0;
            case proximityToOSMWay:
                return 5.0;
            default:
                return 0.0;
        }
    }
    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        //first find the match to the original way
        final StopWayMatch originalWayMatch = wayMatches.get(originalWaySegments.way.osm_id);
        if(originalWayMatch == null) { //shouldn't happen
            String[] errMsg = {"Received observation message for untracked line!"};
            throw new InvalidArgumentException(errMsg);
        }

        System.out.println("Stop " + platform.getTag("name") + " observed split: " + originalWaySegments.way.getTag("name"));

        //remove the proximity matches from segments that are no longer on the original line
        final List<StopWayMatch.StopSegmentMatch> oldProximityMatches = new ArrayList<>(originalWayMatch.proximityMatches.size());
        for(final StopWayMatch.StopSegmentMatch segmentMatch : originalWayMatch.proximityMatches) {
            if(!originalWayMatch.line.segments.contains(segmentMatch.candidateSegment)) {
                oldProximityMatches.add(segmentMatch);
            }
        }
        originalWayMatch.proximityMatches.removeAll(oldProximityMatches);

        //update the proximity matches to refer to the correct line
        System.out.println("removed " + oldProximityMatches.size() + " stale proximity matches");
        for(final WaySegments ws : splitWaySegments) {
            for(final StopWayMatch.StopSegmentMatch segmentMatch : oldProximityMatches) {
                if(ws.segments.contains(segmentMatch.candidateSegment)) {
                    addProximityMatch(segmentMatch.routeLine, segmentMatch.candidateSegment, segmentMatch.distance, segmentMatch.matchType);
                    System.out.println("Moved proximity for segment from " + originalWayMatch.line.way.osm_id + " to line " + segmentMatch.candidateSegment.parentSegments.way.osm_id + "/" + ws.way.osm_id);
                }
            }
        }

        //and re-summarize the matches
        for(final StopWayMatch wayMatch : wayMatches.values()) {
            wayMatch.nameScore = originalWayMatch.nameScore; //also copy name score
            wayMatch.summarizeMatches();
        }

        //and re-choose the best match
        chooseBestWayMatch();
    }

    @Override
    public void waySegmentsWasDeleted(WaySegments waySegments) {

    }
}
