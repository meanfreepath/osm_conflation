package Conflation;

import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.*;

/**
 * Class that associates stop platforms with their respective node on the appropriate way
 * Created by nick on 1/27/16.
 */
public class StopArea implements WaySegmentsObserver {
    private final static Comparator<SummarizedMatch> STOP_WAY_MATCH_COMPARATOR = new Comparator<SummarizedMatch>() {
        @Override
        public int compare(final SummarizedMatch o1, final SummarizedMatch o2) {
            return o1.totalScore < o2.totalScore ? 1 : -1;
        }
    };
    private final static long debugPlatformId = 654845766L;
    public static boolean debugEnabled = false;

    /**
     * The size of the bounding boxes (in meters) to check for existing stops, ways
     */
    public final static double duplicateStopPlatformBoundingBoxSize = 50.0, waySearchAreaBoundingBoxSize = 50.0;
    public final static double stopNodeTolerance = 3.0, maxDistanceFromPlatformToWay = waySearchAreaBoundingBoxSize / 2.0, maxDistanceBetweenDuplicateStops = duplicateStopPlatformBoundingBoxSize / 2.0;
    public final static String KEY_GTFS_STOP_ID = "gtfs:stop_id", KEY_GTFS_CONFLICT = "gtfs:conflict";
    private OSMEntity platform; //can be a node or way
    private OSMNode stopPosition = null;
    private Region nearbyWaySearchRegion, nearbyStopSearchRegion;

    public enum WayMatchType {
        none, primaryStreet, crossStreet, proximityToOSMWay
    }

    /**
     * Represents matches between an OSMWay and a RouteLine, using SegmentProximity matches to track individual segment matches
     */
    protected class StopWayMatch {
        /**
         * Represents a match between an osmLineSegment and the platform node of the parent StopArea
         */
        protected class SegmentProximityMatch {
            public final SegmentMatch segmentMatch;
            public final double distanceFromPlatform;

            protected SegmentProximityMatch(final SegmentMatch match, final double distance) {
                this.segmentMatch = match;
                this.distanceFromPlatform = distance;
            }
            @Override
            public String toString() {
                return String.format("SegmentProximityMatch for %s, OSMSeg %s, distance %.01f, rlSeg: %s", StopArea.this, segmentMatch.matchingSegment, distanceFromPlatform, segmentMatch.mainSegment);
            }
        }

        /**
         * Represents a textual name match (i.e. street name match) between the name tag of the platform node and an OSM way
         */
        protected class NameMatch {
            public final WayMatchType matchType;
            public NameMatch(final WayMatchType matchType) {
                this.matchType = matchType;
            }
        }

        protected final static double scoreFactorForPrimaryStreet = 100.0, scoreFactorForCrossStreet = 50.0, scoreFactorForProximity = 5.0;
        public final RouteLineWaySegments routeLine;
        public final OSMWaySegments osmLine;
        public OSMLineSegment closestSegmentToStop = null;
        public NameMatch nameMatch = null;
        private final Map<Long, SegmentProximityMatch> proximityMatches = new HashMap<>(8); //keyed by the OSM segment's id
        private double nameScore = 0.0, dpScore = 0.0, distanceScore = 0.0;

        public StopWayMatch(final RouteLineWaySegments routeLine, final OSMWaySegments candidateLine) {
            this.routeLine = routeLine;
            osmLine = candidateLine;
        }
        private SegmentProximityMatch addStopSegmentMatch(final SegmentMatch match) {
            SegmentProximityMatch proximityMatch = proximityMatches.get(match.matchingSegment.id);
            if(proximityMatch == null) {
                final double distance = Point.distance(match.matchingSegment.closestPointToPoint(platform.getCentroid()), platform.getCentroid());
                proximityMatch = new SegmentProximityMatch(match, distance);
                proximityMatches.put(match.matchingSegment.id, proximityMatch);
            }
            return proximityMatch;
        }
        protected void setNameMatch(final WayMatchType matchType) {
            nameMatch = new NameMatch(matchType);
        }
        protected void summarizeMatches() {
            nameScore = dpScore = distanceScore = 0.0;

            //include the name match, if any
            if(nameMatch != null) {
                nameScore = nameMatch.matchType == WayMatchType.primaryStreet ? scoreFactorForPrimaryStreet : scoreFactorForCrossStreet;
            }

            final int proximityMatchCount = proximityMatches.size();
            if(proximityMatchCount == 0) {
                return;
            }

            //and the proximity matches
            for(final SegmentProximityMatch proximityMatch : proximityMatches.values()) {
                if(platform.osm_id == debugPlatformId) {
                    System.out.format("\nBest routeLine match (%.02f distance) for %s::\n%s\n", proximityMatch.distanceFromPlatform, proximityMatch.segmentMatch.matchingSegment, proximityMatch.segmentMatch.mainSegment);
                    System.out.println();
                }

                //Only use matches that travel in a valid direction on their OSM way
                if(osmLine.oneWayDirection != WaySegments.OneWayDirection.none && (proximityMatch.segmentMatch.type & SegmentMatch.matchTypeTravelDirection) == SegmentMatch.matchTypeNone) {
                    continue;
                }

                //dpScore -= Math.log10(1.0 - Math.abs(proximityMatch.segmentMatch.dotProduct)) * scoreFactorForProximity;
                dpScore += Math.abs(proximityMatch.segmentMatch.dotProduct) * scoreFactorForProximity;
                distanceScore += scoreFactorForProximity / proximityMatch.distanceFromPlatform;
            }

            //average the proximity scores by the number of proximity (i.e. segment) matches
            dpScore /= proximityMatchCount;
            distanceScore /= proximityMatchCount;

            //determine the closest segment on the line to this stop
            closestSegmentToStop = (OSMLineSegment) osmLine.closestSegmentToPoint(platform.getCentroid(), maxDistanceFromPlatformToWay);
        }
        protected double getScore() {
            return nameScore + dpScore + distanceScore;
        }
        @Override
        public String toString() {
            return String.format("StopWayMatch for platform %s: way %s, %d proximityMatches: %.01f/%.01f/%.01f=%.01f name/dotProduct/distance/travel=total scores", StopArea.this.getPlatform(), osmLine.way, proximityMatches.size(), nameScore, dpScore, distanceScore, getScore());
        }
    }

    /**
     * Represents a summary of all matching ways with the platform node, with scoring sums etc
     */
    public class SummarizedMatch {
        public final OSMWaySegments osmLine;
        public final StopWayMatch.NameMatch nameMatch;
        public final double nameScore, dpScore, distanceScore, totalScore;

        protected SummarizedMatch(final Collection<StopWayMatch> matches) {
            StopWayMatch lastMatch = null;

            //total up the distance and DP scores for the matches
            double dpScore = 0.0, distanceScore = 0.0, travelDirectionScore = 0.0;
            for(final StopWayMatch stopWayMatch : matches) {
                stopWayMatch.summarizeMatches();
                dpScore += stopWayMatch.dpScore;
                distanceScore += stopWayMatch.distanceScore;
                lastMatch = stopWayMatch;
            }
            assert lastMatch != null;

            nameMatch = lastMatch.nameMatch;
            osmLine = lastMatch.osmLine;
            this.nameScore = lastMatch.nameScore;
            this.dpScore = dpScore;
            this.distanceScore = distanceScore;
            this.totalScore = nameScore + dpScore + distanceScore + travelDirectionScore;
        }
        public OSMLineSegment getClosestSegmentToStopPlatform() {
            return (OSMLineSegment) osmLine.closestSegmentToPoint(StopArea.this.platform.getCentroid(), maxDistanceFromPlatformToWay);
        }
        @Override
        public String toString() {
            return String.format("SummarizedStopMatch: way #%d (%s): %.01f/%.01f/%.01f=%.01f name/DP/distance=total scores", osmLine.way.osm_id, osmLine.way.getTag(OSMEntity.KEY_NAME), nameScore, dpScore, distanceScore, totalScore);
        }
    }

    /**
     * Contains a list of the matches to nearby ways, keyed by the routeLine's id, then the OSM ways' ids
     * Basically more matches for an OSM way == good
     * NOTE: There can be up to N matches per way (where N is the number of subroutes processed)
     */
    protected final Map<Long, Map<Long, StopWayMatch>> wayMatches = new HashMap<>(8);
    /**
     * A list of the summarized best way matches for this stop
     */
    public final List<SummarizedMatch> bestWayMatches = new ArrayList<>(8);
    public SummarizedMatch bestWayMatch = null;

    public StopArea(final OSMEntity platform, final OSMNode stopPosition) {
        setPlatform(platform);
        setStopPosition(stopPosition);
    }
    protected StopWayMatch.SegmentProximityMatch addProximityMatch(final SegmentMatch match) {
        //first get a handle on the OSM way matches for the given routeLine
        final RouteLineWaySegments routeLine = (RouteLineWaySegments) match.mainSegment.getParent();
        Map<Long, StopWayMatch> wayMatchesForRouteLine = wayMatches.get(routeLine.way.osm_id);
        if(wayMatchesForRouteLine == null) {
            wayMatchesForRouteLine = new HashMap<>(8);
            wayMatches.put(routeLine.way.osm_id, wayMatchesForRouteLine);

            //watch for changes on the routeLine, so we can update the indexes as needed
            routeLine.addObserver(this);
        }

        //and the routeLine's entry for the OSM line that owns osmLineSegment
        final OSMWaySegments osmLine = (OSMWaySegments) match.matchingSegment.getParent();
        StopWayMatch wayMatch = wayMatchesForRouteLine.get(osmLine.way.osm_id);
        if(wayMatch == null) { //init a match object for the line, if not yet present
            wayMatch = new StopWayMatch(routeLine, osmLine);
            wayMatchesForRouteLine.put(osmLine.way.osm_id, wayMatch);

            //watch for changes on the RouteLine and any matching OSMLineSegments
            osmLine.addObserver(this);
        }
        if(platform.osm_id == debugPlatformId) {
            System.out.println("Added way match for " + wayMatch);
        }
        return wayMatch.addStopSegmentMatch(match);
    }

    /**
     * Resets the entire way match index for this stop, and stops observing any related lines.  May be safely called after stop position is set
     */
    protected void clearAllWayMatches() {
        for(final Map<Long, StopWayMatch> routeWayMatches: wayMatches.values()) {
            for(final StopWayMatch stopWayMatch : routeWayMatches.values()) {
                stopWayMatch.routeLine.removeObserver(this);
                stopWayMatch.osmLine.removeObserver(this);
            }
        }
        wayMatches.clear();
        bestWayMatches.clear();
        //bestWayMatch = null; //TODO remove later
    }

    protected void chooseBestWayMatch() {
        if(wayMatches.size() == 0) {
            System.out.println("WARNING: no nearby ways found for " + this);
            return;
        }

        //summarize the matches and pick the best one for each route
        bestWayMatches.clear(); //clear in case previously called
        final Map<Long, List<StopWayMatch>> stopMatchesByOSMWayId = new HashMap<>(8);
        for(final Map<Long, StopWayMatch> routeWayMatches : wayMatches.values()) {
            for(final Map.Entry<Long,StopWayMatch> stopWayMatchEntry : routeWayMatches.entrySet()) {
                List<StopWayMatch> osmMatches = stopMatchesByOSMWayId.get(stopWayMatchEntry.getKey());
                if(osmMatches == null) {
                    osmMatches = new ArrayList<>(8);
                    stopMatchesByOSMWayId.put(stopWayMatchEntry.getKey(), osmMatches);
                }
                osmMatches.add(stopWayMatchEntry.getValue());
            }
        }

        //summarize the matches, sort them by score and pick the best one - this is the way the stop position should be placed on
        for(final List<StopWayMatch> osmMatches : stopMatchesByOSMWayId.values()) {
            bestWayMatches.add(new SummarizedMatch(osmMatches));
        }
        Collections.sort(bestWayMatches, STOP_WAY_MATCH_COMPARATOR);
        bestWayMatch = bestWayMatches.get(0);
    }
    public OSMEntity getPlatform() {
        return platform;
    }
    protected void setPlatform(OSMEntity platform) {
        this.platform = platform;
        final double waySearchBuffer = -SphericalMercator.metersToCoordDelta(waySearchAreaBoundingBoxSize, platform.getCentroid().y), stopSearchBuffer = -SphericalMercator.metersToCoordDelta(duplicateStopPlatformBoundingBoxSize, platform.getCentroid().y);
        nearbyWaySearchRegion = platform.getBoundingBox().regionInset(waySearchBuffer, waySearchBuffer);
        nearbyStopSearchRegion = platform.getBoundingBox().regionInset(stopSearchBuffer, stopSearchBuffer);
    }
    public OSMNode getStopPosition() {
        return stopPosition;
    }
    protected void setStopPosition(final OSMNode stopNode) {
        stopPosition = stopNode;

        //set the tags of the node to ensure they match the platform
        if(stopPosition != null) {
            OSMPresetFactory.makeStopPosition(stopNode);
            if (platform.hasTag(OSMEntity.KEY_NAME)) {
                stopNode.setTag(OSMEntity.KEY_NAME, platform.getTag(OSMEntity.KEY_NAME));
            }
            if (platform.hasTag(OSMEntity.KEY_REF)) {
                stopNode.setTag(OSMEntity.KEY_REF, platform.getTag(OSMEntity.KEY_REF));
            }
            if (platform.hasTag(KEY_GTFS_STOP_ID)) {
                stopNode.setTag(KEY_GTFS_STOP_ID, platform.getTag(KEY_GTFS_STOP_ID));
            }
        }
    }
    protected Region getNearbyWaySearchRegion() {
        return nearbyWaySearchRegion;
    }
    protected Region getNearbyStopSearchRegion() {
        return nearbyStopSearchRegion;
    }

    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        if(originalWaySegments instanceof OSMWaySegments) {
            //TODO need to implement
            /*//first find the match to the original way
            final StopWayMatch originalWayMatch = wayMatches.get(originalWaySegments.way.osm_id);
            if (originalWayMatch == null) { //shouldn't happen
                String[] errMsg = {"Received observation message for untracked line #" + originalWaySegments.way.osm_id};
                throw new InvalidArgumentException(errMsg);
            }

            //delete the old match reference (will be regenerated below)
            wayMatches.remove(originalWaySegments.way.osm_id);

            //System.out.println("Stop " + platform.getTag("name") + " observed split: " + originalWaySegments.way.getTag("name"));

            //copy the name match to the new lines, if present.  No need to check for proximity here
            if (originalWayMatch.nameMatch != null) {
                for (final WaySegments ws : splitWaySegments) {
                    if (ws == originalWaySegments) {
                        continue;
                    }
                    addNameMatch((OSMWaySegments) ws, originalWayMatch.nameMatch.matchType);
                }
            }

            //create the proximity matches for the newly-split ways
            for (final WaySegments ws : splitWaySegments) {
                for (final StopWayMatch.SegmentProximityMatch segmentMatch : originalWayMatch.proximityMatches.values()) {
                    if (ws.segments.contains(segmentMatch.candidateSegment)) {
                        StopWayMatch.SegmentProximityMatch proximityMatch = addProximityMatch(segmentMatch.candidateSegment, segmentMatch.distance, segmentMatch.matchType);
                        // if(segmentMatch.)
                        //System.out.println("Moved proximity for segment from " + originalWayMatch.line.way.osm_id + " to line " + segmentMatch.candidateSegment.parentSegments.way.osm_id + "/" + ws.way.osm_id);
                    }
                }
            }

            //and re-summarize the matches
            for (final StopWayMatch wayMatch : wayMatches.values()) {
                wayMatch.nameScore = originalWayMatch.nameScore; //also copy name score
                wayMatch.summarizeMatches();
            }

            //and re-choose the best match
            chooseBestWayMatch();*/
        }
    }

    @Override
    public void waySegmentsWasDeleted(WaySegments waySegments) {

    }
    @Override
    public void waySegmentsAddedSegment(final WaySegments originalWaySegments, final LineSegment oldSegment, final LineSegment[] newSegments) {
        if(originalWaySegments instanceof RouteLineWaySegments) { //i.e. when inserting a stop position on the RouteLine
            //Update the closest RouteLineSegment property on all proximity matches, to ensure it's not referring to oldSegment
            final Map<Long, StopWayMatch> routeWayMatches = wayMatches.get(originalWaySegments.way.osm_id);
            for(final StopWayMatch wayMatch : routeWayMatches.values()) {
                for(final StopWayMatch.SegmentProximityMatch proximityMatch : wayMatch.proximityMatches.values()) {
                    if(proximityMatch.segmentMatch.mainSegment == oldSegment) {
                        wayMatch.proximityMatches.remove(proximityMatch.segmentMatch.matchingSegment.id);
                    }
                }
            }

            //and match the new lineSegments with the stop platform
            //NOTE: this assumes the matches are summarized, i.e. this StopArea is later in the observer chain than the RouteLine
            RouteLineSegment routeLineSegment;
            for(final LineSegment newSegment : newSegments) {
                routeLineSegment = (RouteLineSegment) newSegment;
                if(routeLineSegment.bestMatchOverall != null && Region.intersects(routeLineSegment.boundingBox, getNearbyWaySearchRegion())) {
                    addProximityMatch(routeLineSegment.bestMatchOverall);
                }
            }

        } else if(originalWaySegments instanceof OSMWaySegments) { //i.e. when a way associated with this stop is updated
            //update the way matches to include the new segment
            for (final Map<Long, StopWayMatch> routeWayMatches : wayMatches.values()) {
                final StopWayMatch wayMatch = routeWayMatches.get(originalWaySegments.way.osm_id);
                final StopWayMatch.SegmentProximityMatch originalProximityMatch = wayMatch.proximityMatches.get(oldSegment.id);

                //now update the proximity match list, removing the old segment, and adding the (now updated) best match to the list
                if (originalProximityMatch != null) {
                    wayMatch.proximityMatches.remove(oldSegment.id);
                    if(originalProximityMatch.segmentMatch.mainSegment.bestMatchOverall != null) {
                        addProximityMatch(originalProximityMatch.segmentMatch.mainSegment.bestMatchOverall);
                    }
                }
            }
            chooseBestWayMatch(); //also updates the references in the SummarizedMatch objects
        }
    }
    @Override
    public String toString() {
        return String.format("StopArea @%d P(#%d: “%s”[ref:%s]), *S(#%s: %s[ref:%s])", hashCode(), platform.osm_id, platform.getTag(OSMEntity.KEY_NAME), platform.getTag(OSMEntity.KEY_REF), stopPosition != null ? stopPosition.osm_id : "N/A", stopPosition != null ? stopPosition.getTag(OSMEntity.KEY_NAME) : "", stopPosition != null ? stopPosition.getTag(OSMEntity.KEY_REF) : "");
    }
}
