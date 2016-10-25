package Conflation;

import OSM.OSMNode;
import OSM.Point;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by nick on 9/30/16.
 */
public class RouteLineSegment extends LineSegment {
    public final RouteLineWaySegments parentSegments;
    private final List<OSMWaySegments> candidateWaySegments; //TODO: may not be needed

    /**
     * The OSMLineSegment matches, keyed by their way's OSM id
     */
    private final Map<Long, List<SegmentMatch>> matchingSegments;
    public final Map<Long, SegmentMatch> bestMatchForLine;
    public RouteLineSegment(RouteLineWaySegments parentSegments, Point origin, Point destination, OSMNode originNode, OSMNode destinationNode, int segmentIndex, int nodeIndex) {
        super(parentSegments.way.osm_id, origin, destination, originNode, destinationNode, segmentIndex, nodeIndex);
        this.parentSegments = parentSegments;

        candidateWaySegments = new ArrayList<>(16);
        matchingSegments = new HashMap<>(8);
        bestMatchForLine = new HashMap<>(8);
    }

    /**
     * Copy constructor used when splitting segments
     * @param segmentToCopy
     * @param destination
     * @param destinationNode
     */
    protected RouteLineSegment(final RouteLineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        super(segmentToCopy, destination, destinationNode);
        this.parentSegments = segmentToCopy.parentSegments;

        //also copy the matches
        candidateWaySegments = new ArrayList<>(segmentToCopy.candidateWaySegments);
        matchingSegments = new HashMap<>(segmentToCopy.matchingSegments.size());
        bestMatchForLine = new HashMap<>(segmentToCopy.bestMatchForLine);
    }
    public void addCandidateLine(final OSMWaySegments candidate) {
        candidateWaySegments.add(candidate);
    }
    public List<OSMWaySegments> getCandidateLines() {
        return candidateWaySegments;
    }
    public void addMatch(final SegmentMatch match) {
        final long osmWayId = match.matchingSegment.getParent().way.osm_id;
        List<SegmentMatch> matchesForLine = matchingSegments.get(osmWayId);
        if(matchesForLine == null) {
            matchesForLine = new ArrayList<>(8);
            matchingSegments.put(osmWayId, matchesForLine);
        }
        matchesForLine.add(match);
    }
    public void summarize() {
        //look up the matching segments we have for the given OSM way
        bestMatchForLine.clear();
        for(final Map.Entry<Long, List<SegmentMatch>> matchesForLine : matchingSegments.entrySet() ) {
            //and loop through them, picking the one with the best score as the best match
            SegmentMatch bestMatch = chooseBestMatchForMatchType(matchesForLine.getValue(), SegmentMatch.matchMaskAll);
            if (bestMatch == null) {
                bestMatch = chooseBestMatchForMatchType(matchesForLine.getValue(), SegmentMatch.matchTypeBoundingBox);
            }
            bestMatchForLine.put(matchesForLine.getKey(), bestMatch);
        }

        //also clean up any unneeded memory
        candidateWaySegments.clear();
        //matchingSegments.clear(); uncomment once not needed
    }
    private SegmentMatch chooseBestMatchForMatchType(final List<SegmentMatch> matches, final short matchMask) {
        double minScore = Double.MAX_VALUE, matchScore, nextBestMinScore = Double.MAX_VALUE, nextBestMatchScore;
        SegmentMatch bestMatch = null;
        for(final SegmentMatch match : matches) {
            //choose the best match based on the product of their dot product and distance score
            if((match.type & matchMask) == matchMask) {
                matchScore = (match.orthogonalDistance * match.orthogonalDistance + match.midPointDistance * match.midPointDistance) / Math.max(0.000001, Math.abs(match.dotProduct));
                if (bestMatch == null || matchScore < minScore) {
                    bestMatch = match;
                    minScore = matchScore;
                }
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
    public Map<Long, SegmentMatch> getBestMatchingSegments() {
        return bestMatchForLine;
    }
    @Override
    public String toString() {
        return String.format("RLSeg #%d [%d/%d] [%.01f, %.01f], nd[%d/%d]", id, segmentIndex, nodeIndex, midPoint.x, midPoint.y, originNode != null ? originNode.osm_id : 0, destinationNode != null ? destinationNode.osm_id : 0);
    }
    /*@Override
    public void finalize() throws Throwable {
        System.out.println("DELETE " + this);
        super.finalize();
    }*/
}
