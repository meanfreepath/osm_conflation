package Conflation;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;

import java.util.*;

/**
 * Created by nick on 9/30/16.
 */
public class RouteLineSegment extends LineSegment {
    private final static Comparator<SegmentMatch> matchComparatorDistance = new Comparator<SegmentMatch>() {
        @Override
        public int compare(SegmentMatch o1, SegmentMatch o2) {
            return o1.midPointDistance > o2.midPointDistance ? 1 : -1;
        }
    };
    private final static Comparator<SegmentMatch> matchComparatorDotProduct = new Comparator<SegmentMatch>() {
        @Override
        public int compare(SegmentMatch o1, SegmentMatch o2) {
            return Math.abs(o1.dotProduct) < Math.abs(o2.dotProduct) ? 1 : -1;
        }
    };

    /**
     * The parent line of this segment
     */
    public final RouteLineWaySegments parentSegments;

    /**
     * The OSMLineSegment matches, keyed by their way's OSM id
     */
    private final Map<Long, List<SegmentMatch>> matchingSegments;

    /**
     * The OSMLineSegment matches, keyed by the match's id
     */
    private final Map<Long, SegmentMatch> matchingSegmentsById;

    /**
     * A list of the best matches, keyed by their way's OSM id
     */
    public final Map<Long, SegmentMatch> bestMatchForLine;

    /**
     * The best overall match for this segment, based on distance and dot product
     */
    public SegmentMatch bestMatchOverall = null;

    public boolean summarized = false;

    /**
     * Default constructor
     * @param parentSegments the segmented way that this segment belongs to
     * @param origin the origin coordinate of this segment
     * @param destination the destination coordinate of this segment
     * @param originNode the node at the origin coordinate, if any
     * @param destinationNode the node at the destination coordinate, if any
     * @param segmentIndex the index of this segment within its parent
     * @param nodeIndex the index of this segment's originNode within its parent's way (will be same as previous segment if no origin node present)
     */
    protected RouteLineSegment(RouteLineWaySegments parentSegments, Point origin, Point destination, OSMNode originNode, OSMNode destinationNode, int segmentIndex, int nodeIndex) {
        super(parentSegments.wayMatchingOptions, origin, destination, originNode, destinationNode, segmentIndex, nodeIndex);
        this.parentSegments = parentSegments;

        matchingSegments = new HashMap<>(8);
        matchingSegmentsById = new HashMap<>(8);
        bestMatchForLine = new HashMap<>(8);
    }

    /**
     * Copy constructor used when splitting segments
     * @param segmentToCopy the original segment
     * @param destination the new destination coordinate to use
     * @param destinationNode the new destination node to use, if any
     */
    protected RouteLineSegment(final RouteLineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        super(segmentToCopy.parentSegments.wayMatchingOptions, segmentToCopy, destination, destinationNode);
        this.parentSegments = segmentToCopy.parentSegments;

        //NOTE: these matches are re-run in post-split observer functions in RouteLineWaySegments
        matchingSegments = new HashMap<>(segmentToCopy.matchingSegments.size());
        matchingSegmentsById = new HashMap<>(segmentToCopy.matchingSegmentsById.size());
        bestMatchForLine = new HashMap<>(segmentToCopy.bestMatchForLine.size());
    }
    /**
     * Add the given SegmentMatch to this object's match indexes.
     * @param match
     * @return true if added, false if a duplicate or other error
     */
    public boolean addMatch(final SegmentMatch match) {
        //prevent duplicate matches from being added (happens on RouteLineSegments located near Cell boundaries)
        if(matchingSegmentsById.containsKey(match.id)) {
           // System.out.println("DUPLICATE MATCH FOR " + match.mainSegment + "::" + SphericalMercator.mercatorToLatLon(match.mainSegment.midPoint));
            return false;
        }
        matchingSegmentsById.put(match.id, match);

        //add to the index keyed by the OSM ways' ids
        updateMatchesByLine(match);
        return true;
    }
    private void updateMatchesByLine(final SegmentMatch match) {
        final long osmWayId = match.matchingSegment.getParent().way.osm_id;
        List<SegmentMatch> matchesForLine = matchingSegments.get(osmWayId);
        if(matchesForLine == null) {
            matchesForLine = new ArrayList<>(8);
            matchingSegments.put(osmWayId, matchesForLine);
        }
        matchesForLine.add(match);
    }

    /**
     * Remove the given match from this object's indexes
     * @param match the match to remove
     * @return true if removed, false if not
     */
    public boolean removeMatch(final SegmentMatch match) {
        if(!matchingSegmentsById.containsKey(match.id)) {
            return false;
        }
        matchingSegmentsById.remove(match.id);

        final OSMWaySegments matchingLine = (OSMWaySegments) match.matchingSegment.getParent();
        List<SegmentMatch> matchesForLine = matchingSegments.get(matchingLine.way.osm_id);
        matchesForLine.remove(match);
        return true;
    }
    protected void resyncMatchesForWay(final OSMWay oldWay) {
        final List<SegmentMatch> oldMatchesForLine = matchingSegments.get(oldWay.osm_id);
        if(oldMatchesForLine != null) {
            final ArrayList<SegmentMatch> oldMatchesForLineCopy = new ArrayList<>(oldMatchesForLine);
            oldMatchesForLine.clear();
            for (final SegmentMatch oldWayMatch : oldMatchesForLineCopy) {
                updateMatchesByLine(oldWayMatch);
            }
        }
    }
    protected void flushMatches() {
        matchingSegments.clear();
        matchingSegmentsById.clear();
        bestMatchForLine.clear();
        bestMatchOverall = null;
    }
    public void summarize() {
        //look up the matching segments we have for the given OSM way
        bestMatchForLine.clear();
        final List<SegmentMatch> filteredMatches = new ArrayList<>(matchingSegments.size());
        for(final Map.Entry<Long, List<SegmentMatch>> matchesForLine : matchingSegments.entrySet() ) {
            //and loop through them, picking the one with the best score as the best match
            SegmentMatch bestMatch = chooseBestMatchForMatchType(matchesForLine.getValue(), SegmentMatch.matchMaskAll);
            if (bestMatch == null) {
                bestMatch = chooseBestMatchForMatchType(matchesForLine.getValue(), SegmentMatch.matchTypeBoundingBox);
            }

            if(bestMatch != null) {
                bestMatchForLine.put(matchesForLine.getKey(), bestMatch);
                filteredMatches.add(bestMatch);
            }
        }

        //and using the filtered match list choose a best overall match
        bestMatchOverall = chooseBestMatchForMatchType(filteredMatches, SegmentMatch.matchMaskAll);

        summarized = true;
    }
    private SegmentMatch chooseBestMatchForMatchType(final List<SegmentMatch> matches, final short matchMask) {
        double minScore = Double.MAX_VALUE, matchScore, absDotProduct;
        SegmentMatch bestMatch = null;
        final List<SegmentMatch> matchList = new ArrayList<>(matches.size());
        for(final SegmentMatch match : matches) {
            //choose the best match based on the product of their dot product and distance score
            if((match.type & matchMask) == matchMask) {
                matchList.add(match);
              /*  absDotProduct = Math.abs(match.dotProduct);
                matchScore = (match.orthogonalDistance * match.orthogonalDistance + match.midPointDistance * match.midPointDistance) * (absDotProduct > Double.MIN_VALUE ?  Math.abs(Math.log10(absDotProduct)) : 1.0);
                if (bestMatch == null || matchScore < minScore) {
                    bestMatch = match;
                    minScore = matchScore;
                }*/
            }
        }
        matchList.sort(matchComparatorDistance);
        final List<SegmentMatch> distanceCandidates = new ArrayList<>(matchList.size());
        for (double maxDistance = parentSegments.wayMatchingOptions.maxSegmentLength * 0.25;maxDistance<=parentSegments.wayMatchingOptions.maxSegmentMidPointDistance; maxDistance*=2.0) {
            distanceCandidates.clear();
            for (final SegmentMatch match : matchList) {
                if (match.midPointDistance < maxDistance) {
                    distanceCandidates.add(match);
                }
            }

            if(distanceCandidates.size() > 0) {
                distanceCandidates.sort(matchComparatorDotProduct);
                return distanceCandidates.get(0);
            }
        }
        return bestMatch;
    }

    @Override
    public WaySegments getParent() {
        return parentSegments;
    }
    @Override
    public void setParent(WaySegments newParent) {
        throw new RuntimeException("Can’t set parent for RouteLineSegment");
    }
    public Map<Long, List<SegmentMatch>> getMatchingSegments(final short matchMask) {
        if (matchMask == SegmentMatch.matchTypeNone) {
            return matchingSegments;
        }
        final Map<Long, List<SegmentMatch>> filteredMatchingSegments = new HashMap<>(matchingSegments.size());
        for(final Map.Entry<Long, List<SegmentMatch>> matchesForLine : matchingSegments.entrySet()) {
            final List<SegmentMatch> filteredMatchingSegmentsForLine = new ArrayList<>(matchesForLine.getValue().size());
            for(final SegmentMatch match : matchesForLine.getValue()) {
                if((match.type & matchMask) == matchMask) {
                    filteredMatchingSegmentsForLine.add(match);
                }
            }
            filteredMatchingSegments.put(matchesForLine.getKey(), filteredMatchingSegmentsForLine);
        }
        return filteredMatchingSegments;
    }
    public List<SegmentMatch> getMatchingSegmentsForLine(final long wayOsmId, final short matchMask) {
        final List<SegmentMatch> matchingSegmentsForLine = matchingSegments.get(wayOsmId);
        if(matchingSegmentsForLine != null) {
            if(matchMask == SegmentMatch.matchTypeNone) {
                return matchingSegmentsForLine;
            } else {
                final List<SegmentMatch> filteredMatchingSegments = new ArrayList<>(matchingSegmentsForLine.size());
                for(final SegmentMatch match: matchingSegmentsForLine) {
                    if((match.type & matchMask) == matchMask) {
                        filteredMatchingSegments.add(match);
                    }
                }
                return filteredMatchingSegments;
            }
        } else {
            return null;
        }
    }
    @Override
    public String toString() {
        return String.format("RLSeg #%d [%d/%d] [%.01f, %.01f], nd[%d/%d]", id, segmentIndex, nodeIndex, midPoint.x, midPoint.y, originNode != null ? originNode.osm_id : 0, destinationNode != null ? destinationNode.osm_id : 0);
    }
    /*@Override
    public void finalize() throws Throwable {
        System.out.println("RLSEGDELETE " + this);
        super.finalize();
    }*/
}
