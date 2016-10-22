package Conflation;

import OSM.*;
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

    /**
     * the distance (in meters) to check for existing stops
     */
    public final static double maxConflictSearchDistance = 25.0;
    public final static double maxDistanceFromPlatformToWay = 25.0, stopNodeTolerance = 3.0;
    public static boolean debugEnabled = false;
    private OSMEntity platform; //can be a node or way
    private OSMNode stopPosition = null;
    private Region nearbyWaySearchRegion, nearbyStopSearchRegion;

    public enum SegmentMatchType {
        none, primaryStreet, crossStreet, proximityToOSMWay
    }

    public class StopWayMatch {
        public class SegmentProximityMatch {
            public final LineSegment candidateSegment;
            public final RouteLineWaySegments routeLine;
            public final SegmentMatchType matchType;
            public final double distance;

            public SegmentProximityMatch(final RouteLineWaySegments toRouteLine, final LineSegment segment, final double distance, final SegmentMatchType matchType) {
                this.candidateSegment = segment;
                this.distance = distance;
                this.matchType = matchType;
                this.routeLine = toRouteLine;
            }
        }
        public class NameMatch {
            public final WaySegments candidateLine;
            public final SegmentMatchType matchType;
            public NameMatch(final WaySegments candidateLine, final SegmentMatchType matchType) {
                this.candidateLine = candidateLine;
                this.matchType = matchType;
            }
        }

        public final WaySegments line;
        public LineSegment closestSegmentToStop = null;
        public final List<NameMatch> nameMatches = new ArrayList<>(8);
        public final List<SegmentProximityMatch> proximityMatches = new ArrayList<>(8);
        private double nameScore = 0.0, dpScore = 0.0, distanceScore = 0.0;

        public StopWayMatch(final WaySegments candidateLine) {
            line = candidateLine;
        }
        public void addStopSegmentMatch(final RouteLineWaySegments toRouteLine, final LineSegment segment, final double distance, final SegmentMatchType matchType) {
            proximityMatches.add(new SegmentProximityMatch(toRouteLine, segment, distance, matchType));
        }
        public void addNameMatch(final WaySegments toOSMLine, final SegmentMatchType matchType) {
            nameMatches.add(new NameMatch(toOSMLine, matchType));
        }
        public void summarizeMatches() {
            nameScore = dpScore = distanceScore = 0.0;
            if(proximityMatches.size() == 0) {
                return;
            }

            //sum up the name matches
            for(final NameMatch nameMatch : nameMatches) {
                nameScore += scoreFactorForMatchType(nameMatch.matchType);
            }

            //check the oneway direction of the line, and whether it goes with or against
            final boolean wayIsOneWay = line.oneWayDirection != WaySegments.OneWayDirection.none;
            final boolean debugIds = line.way.osm_id == 158265862L || line.way.osm_id == 158265857L;

            //and the proximity matches
            double dpFactor, odFactor, oneWayFactor;
            for(final SegmentProximityMatch segmentMatch : proximityMatches) {
                final SegmentMatch bestMatchForRouteLine = null;//TODO 222 segmentMatch.candidateSegment.bestMatchForLine.get(segmentMatch.routeLine.way.osm_id);

                if(bestMatchForRouteLine != null) {
                    //adjust score for matches that go with direction of oneway travel, for better matching on dual carriageways
                    if(line.oneWayDirection == WaySegments.OneWayDirection.forward) {
                        oneWayFactor = bestMatchForRouteLine.dotProduct > 0.0 ? 2.0 : -2.0;
                    } else if(line.oneWayDirection == WaySegments.OneWayDirection.backward) {
                        oneWayFactor = bestMatchForRouteLine.dotProduct < 0.0 ? 2.0 : -2.0;
                    } else {
                        oneWayFactor = 1.0;
                    }
                    if(debugIds) {
                        System.out.println("ONEWAY " + line.way.osm_id + ": " + bestMatchForRouteLine.dotProduct + " factor " + oneWayFactor);
                    }
                    dpFactor = oneWayFactor * Math.abs(bestMatchForRouteLine.dotProduct);
                    odFactor = bestMatchForRouteLine.orthogonalDistance;
                } else {
                    dpFactor = 0.01;
                    odFactor = maxDistanceFromPlatformToWay;
                }

                dpScore += dpFactor * scoreFactorForMatchType(segmentMatch.matchType);
                distanceScore += 1.0 / (odFactor * segmentMatch.distance) * scoreFactorForMatchType(segmentMatch.matchType);
            }

            //determine the closest segment on the line to this stop
            closestSegmentToStop = line.closestSegmentToPoint(platform.getCentroid(), StopArea.maxDistanceFromPlatformToWay);
        }
        public double getScore() {
            return nameScore + dpScore + distanceScore;
        }
    }
    public StopWayMatch bestWayMatch = null;
    public final Map<Long, StopWayMatch> wayMatches = new HashMap<>(16);

    public StopArea(final OSMEntity platform, final OSMNode stopPosition) {
        this.setPlatform(platform);
        this.stopPosition = stopPosition;
    }
    public OSMNode getStopPosition() {
        return stopPosition;
    }
    public void setStopPosition(final OSMNode stopNode) {
        stopPosition = stopNode;
    }
    public void addNameMatch(final WaySegments line, final SegmentMatchType matchType) {
        StopWayMatch wayMatch = wayMatches.get(line.way.osm_id);
        if(wayMatch == null) { //init a match object for the line, if not yet present
            wayMatch = new StopWayMatch(line);
            wayMatches.put(line.way.osm_id, wayMatch);
        }
        wayMatch.addNameMatch(line, matchType);

        line.addObserver(this);
    }
    public void addProximityMatch(final RouteLineWaySegments toRouteLine, final LineSegment segment, final double distance, final SegmentMatchType matchType) {
        StopWayMatch wayMatch = wayMatches.get(segment.getParent().way.osm_id);
        if(wayMatch == null) { //init a match object for the line, if not yet present
            wayMatch = new StopWayMatch(segment.getParent());
            wayMatches.put(segment.getParent().way.osm_id, wayMatch);
        }
        wayMatch.addStopSegmentMatch(toRouteLine, segment, distance, matchType);

        segment.getParent().addObserver(this);
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
    public OSMEntity getPlatform() {
        return platform;
    }

    public void setPlatform(OSMEntity platform) {
        this.platform = platform;
        final double waySearchBuffer = -SphericalMercator.metersToCoordDelta(maxDistanceFromPlatformToWay, platform.getCentroid().y), stopSearchBuffer = -SphericalMercator.metersToCoordDelta(maxConflictSearchDistance, platform.getCentroid().y);
        nearbyWaySearchRegion = platform.getBoundingBox().regionInset(waySearchBuffer, waySearchBuffer);
        nearbyStopSearchRegion = platform.getBoundingBox().regionInset(stopSearchBuffer, stopSearchBuffer);
    }
    public Region getNearbyWaySearchRegion() {
        return nearbyWaySearchRegion;
    }

    public Region getNearbyStopSearchRegion() {
        return nearbyStopSearchRegion;
    }
    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        //first find the match to the original way
        final StopWayMatch originalWayMatch = wayMatches.get(originalWaySegments.way.osm_id);
        if(originalWayMatch == null) { //shouldn't happen
            String[] errMsg = {"Received observation message for untracked line #" + originalWaySegments.way.osm_id};
            throw new InvalidArgumentException(errMsg);
        }

        //System.out.println("Stop " + platform.getTag("name") + " observed split: " + originalWaySegments.way.getTag("name"));

        //copy the name matches to the new lines, if present.  No need to check for proximity here
        StopWayMatch.NameMatch existingNameMatch = null;
        for(final StopWayMatch.NameMatch nameMatch : originalWayMatch.nameMatches) {
            if(nameMatch.candidateLine == originalWaySegments) {
                existingNameMatch = nameMatch;
                break;
            }
        }
        if(existingNameMatch != null) {
            for (final WaySegments ws : splitWaySegments) {
                if (ws == originalWaySegments) {
                    continue;
                }
                addNameMatch(ws, existingNameMatch.matchType);
            }
        }

        //remove the proximity matches from segments that are no longer on the original line
        final List<StopWayMatch.SegmentProximityMatch> oldProximityMatches = new ArrayList<>(originalWayMatch.proximityMatches.size());
        for(final StopWayMatch.SegmentProximityMatch segmentMatch : originalWayMatch.proximityMatches) {
            if(!originalWayMatch.line.segments.contains(segmentMatch.candidateSegment)) {
                oldProximityMatches.add(segmentMatch);
            }
        }
        originalWayMatch.proximityMatches.removeAll(oldProximityMatches);

        //update the proximity matches to refer to the correct line
        //System.out.println("removed " + oldProximityMatches.size() + " stale proximity matches");
        for(final WaySegments ws : splitWaySegments) {
            for(final StopWayMatch.SegmentProximityMatch segmentMatch : oldProximityMatches) {
                if(ws.segments.contains(segmentMatch.candidateSegment)) {
                    addProximityMatch(segmentMatch.routeLine, segmentMatch.candidateSegment, segmentMatch.distance, segmentMatch.matchType);
                    //System.out.println("Moved proximity for segment from " + originalWayMatch.line.way.osm_id + " to line " + segmentMatch.candidateSegment.parentSegments.way.osm_id + "/" + ws.way.osm_id);
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
    @Override
    public void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment newSegment) {
        //update the way matches to include the new segment
        for(final StopWayMatch wayMatch : wayMatches.values()) {
            for(final StopWayMatch.SegmentProximityMatch proximityMatch : wayMatch.proximityMatches) {
                if(proximityMatch.candidateSegment.getParent() == waySegments) {
                    addProximityMatch(proximityMatch.routeLine, newSegment, Point.distance(newSegment.midPoint, platform.getCentroid()), SegmentMatchType.proximityToOSMWay);
                    break;
                }
            }
        }
        chooseBestWayMatch();
    }
    @Override
    public String toString() {
        return String.format("StopArea P(#%d: “%s”[ref:%s]), *S(#%s: %s[ref:%s])", platform.osm_id, platform.getTag(OSMEntity.KEY_NAME), platform.getTag(OSMEntity.KEY_REF), stopPosition != null ? stopPosition.osm_id : "N/A", stopPosition != null ? stopPosition.getTag(OSMEntity.KEY_NAME) : "", stopPosition != null ? stopPosition.getTag(OSMEntity.KEY_REF) : "");
    }
}
