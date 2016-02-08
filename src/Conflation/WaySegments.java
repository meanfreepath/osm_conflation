package Conflation;

import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.*;

/**
 * Container for an OSM way and its calculated line segments
 * Created by nick on 11/9/15.
 */
public class WaySegments {
    public enum OneWayDirection {
        none, forward, backward
    }
    public enum LineType {
        routeLine, osmLine
    }
    private final static Comparator<SegmentMatch> SEGMENT_MATCH_COMPARATOR = new Comparator<SegmentMatch>() {
        @Override
        public int compare(SegmentMatch o1, SegmentMatch o2) {
            return o1.matchingSegment.segmentIndex < o2.matchingSegment.segmentIndex ? -1 : 1;
        }
    };

    /**
     * Class for tracking the match status between two WaySegmentsObjects
     */
    public class LineMatch {
        public final List<SegmentMatch> matchingSegments;
        public int matchingSegmentCount = -1;
        public final WaySegments routeLine;
        private boolean summarized = false;

        private double avgDotProduct, avgDistance;

        public LineMatch(final WaySegments routeLine) {
            this.routeLine = routeLine;
            matchingSegments = new ArrayList<>(segments.size());
        }

        public void addMatch(final SegmentMatch match) {
            matchingSegments.add(match);
        }
        private void resyncMatchesForSegments(final List<LineSegment> segments) {
            final List<SegmentMatch> matchesToRemove = new ArrayList<>(matchingSegments.size());
            for(final SegmentMatch match : matchingSegments) {
                if(!segments.contains(match.matchingSegment)) {
                    matchesToRemove.add(match);
                }
            }
            //System.out.println("Removed " + matchesToRemove.size() + " segment matches: " + matchingSegments.size() + " left");
            matchingSegments.removeAll(matchesToRemove);
        }

        /**
         * Consolidates all the segment matches and calculates the various totals
         */
        public void summarize() {

        /*first, collapse the segment matchers down by their index (each segment in mainWaySegments
          may match multiple segments, and there is a typically good deal of overlap).  We want only
           the unique segment matches.
         */
            for (final LineSegment segment : segments) {
                segment.chooseBestMatch(routeLine);
            }
            matchingSegments.clear();

            //now re-add the consolidated segments, calculating the average dot product and distance for each matching segment
            avgDotProduct = avgDistance = 0.0;
            for (final LineSegment segment : segments) {
                final SegmentMatch bestMatchForLine = segment.bestMatchForLine.get(routeLine.way.osm_id);
                if (bestMatchForLine != null) {
                    matchingSegments.add(bestMatchForLine);

                    avgDistance += bestMatchForLine.orthogonalDistance;
                    avgDotProduct += bestMatchForLine.dotProduct;
                }
            }
            matchingSegmentCount = matchingSegments.size();
            if (matchingSegmentCount > 0) {
                avgDistance /= matchingSegmentCount;
                avgDotProduct /= matchingSegmentCount;
            }
            summarized = true;
        }
        public boolean isSummarized() {
            return summarized;
        }
        public double getAvgDotProduct() {
            return avgDotProduct;
        }

        public double getAvgDistance() {
            return avgDistance;
        }
    }

    public final OSMWay way;
    public final LineType lineType;
    public final List<LineSegment> segments;
    public final HashMap<Long,LineMatch> lineMatches = new HashMap<>(8);
    public final OneWayDirection oneWayDirection;
    public final double maxSegmentLength;
    private List<WaySegmentsObserver> observers = null;

    public boolean wasSplit = false;

    public WaySegments(final OSMWay way, final LineType type, final double maxSegmentLength) {
        this.way = way;
        this.lineType = type;
        this.maxSegmentLength = maxSegmentLength;
        oneWayDirection = determineOneWayDirection(way);

        //generate a list of line segments out of this line
        segments = new ArrayList<>(way.getNodes().size() - 1); //TODO base on total line length, to handle the newly-created segments
        OSMNode originNode = way.getFirstNode();
        int nodeIndex = 0, segmentIndex = 0;
        for(final OSMNode destinationNode: way.getNodes()) {
            if (destinationNode == originNode) { //skip the first iteration
                continue;
            }

            //first get the distance between the 2 nodes
            final double vectorX = destinationNode.getLon() - originNode.getLon(), vectorY = destinationNode.getLat() - originNode.getLat();
            final double segmentLength = Point.distance(originNode.getCentroid(),destinationNode.getCentroid());

            //if less than the length threshold, add as a segment
            if (segmentLength < maxSegmentLength) {
                final LineSegment segment = new LineSegment(this, originNode.getCentroid(), destinationNode.getCentroid(), originNode, destinationNode, segmentIndex++, nodeIndex);
                segments.add(segment);
            } else { //otherwise, split into a number of segments, each equal to or shorter than the maximum segment length
                final int segmentsToAdd = (int) Math.ceil(segmentLength / maxSegmentLength);
                OSMNode miniOriginNode = originNode;
                Point miniOrigin = originNode.getCentroid(), miniDestination;
                double destinationLat, destinationLon;

                //add the first segment (with the origin node) and subsequent segments (which have no existing nodes)
                for (int seg = 0; seg < segmentsToAdd - 1; seg++) {
                    destinationLon = miniOrigin.longitude + vectorX / segmentsToAdd;
                    destinationLat = miniOrigin.latitude + vectorY / segmentsToAdd;
                    miniDestination = new Point(destinationLat, destinationLon);

                    segments.add(new LineSegment(this, miniOrigin, miniDestination, miniOriginNode, null, segmentIndex++, nodeIndex));

                    miniOrigin = miniDestination;
                    miniOriginNode = null;
                }

                //add the last segment, with its last node as the original destination node
                miniDestination = destinationNode.getCentroid();
                segments.add(new LineSegment(this, miniOrigin, miniDestination, miniOriginNode, destinationNode, segmentIndex++, nodeIndex));
            }
            //System.out.println("END MAINSEGMENT #" + mainSegmentIndex + ": " + originNode.osm_id + "-" + destinationNode.osm_id);
            originNode = destinationNode;
            nodeIndex++;
        }
    }
    private WaySegments(final WaySegments originalSegments, final OSMWay splitWay) {
        this.way = splitWay;
        this.lineType = originalSegments.lineType;
        this.maxSegmentLength = originalSegments.maxSegmentLength;
        this.oneWayDirection = originalSegments.oneWayDirection;
        this.segments = new ArrayList<>(splitWay.getNodes().size() - 1); //TODO base on total line length, to handle the newly-created segments
        wasSplit = true;

        //copy the appropriate segments from the originalSegments
        boolean inSplitLineNodes;
        final OSMNode splitWayFirstNode = splitWay.getFirstNode(), splitWayLastNode = splitWay.getLastNode();
        int originalSegmentIndex = 0, originalNodeIndex = 0, newSegmentIndex = 0, newNodeIndex = 0;
        if(originalSegments.way.getFirstNode() == originalSegments.segments.get(0).originNode) {
            inSplitLineNodes = false;
            for(final LineSegment segment : originalSegments.segments) {
                if(inSplitLineNodes) { //copy the segment over if we're in the new line's node list
                    segments.add(segment);
                    segment.parentSegments = this;
                    segment.segmentIndex = newSegmentIndex++;
                    segment.nodeIndex = newNodeIndex;
                    if(segment.originNode != null) {
                        ++newNodeIndex;
                    }
                } else {
                    if(segment.destinationNode == splitWayFirstNode) {
                        inSplitLineNodes = true;
                    }
                }
            }
        } else {
            inSplitLineNodes = true;
            for (final LineSegment segment : originalSegments.segments) {
                if (inSplitLineNodes) { //copy the segment over if we're in the new line's node list
                    segments.add(segment);
                    segment.parentSegments = this;
                    segment.segmentIndex = newSegmentIndex++;
                    segment.nodeIndex = newNodeIndex;
                    if (segment.originNode != null) {
                        ++newNodeIndex;
                    }

                    if (segment.destinationNode == splitWayLastNode) {
                        inSplitLineNodes = false;
                    }
                } else {
                    segment.segmentIndex = originalSegmentIndex++;
                    segment.nodeIndex = originalNodeIndex;
                    if(segment.originNode != null) {
                        ++originalNodeIndex;
                    }
                }
            }
        }

        //and remove this line's segments from the original
        int origSegs = originalSegments.segments.size();
        originalSegments.segments.removeAll(segments);

        //System.out.println("Finished segment split: was " + origSegs + ", now " + originalSegments.segments.size() + "/" + segments.size() + "(" + originalSegments.way.osm_id + "/" + way.osm_id + ")");
        if(originalSegments.segments.size() == 0 || segments.size() == 0) {
            System.out.println("WARNING: 0 segments!");
            for(final LineSegment segment : originalSegments.segments) {
                System.out.println("\t" + segment);
            }
        }

        //compile the matches for the segments
        for(final LineSegment segment : segments) {
            for(final List<SegmentMatch> matchesForLine : segment.matchingSegments.values()) {
                for(final SegmentMatch match : matchesForLine) {
                    if (!lineMatches.containsKey(match.mainSegment.parentSegments.way.osm_id)) {
                        initMatchForLine(match.mainSegment.parentSegments);
                    }
                    addMatchForLine(match.mainSegment.parentSegments, match);
                }
            }
        }
        for(final LineMatch lineMatch : lineMatches.values()) {
            lineMatch.summarize();
        }

        //System.out.println("HAS " + lineMatches.size() + " line matches");
        originalSegments.resyncLineMatches();

        //also copy the observers
        if(originalSegments.observers != null) {
            observers = new ArrayList<>(originalSegments.observers);
        }
    }
    private void resyncLineMatches() {
        final List<Long> lineMatchesToRemove = new ArrayList<>(lineMatches.size());
        for(final LineMatch lineMatch : lineMatches.values()) {
            lineMatch.resyncMatchesForSegments(segments);

            //resummarize the match
            if(lineMatch.isSummarized()) {
                lineMatch.summarize();
                if(lineMatch.matchingSegments.size() == 0) { //and remove if no segment matches present
                    lineMatchesToRemove.add(lineMatch.routeLine.way.osm_id);
                }
            }
        }
        for(final Long id : lineMatchesToRemove) {
            lineMatches.remove(id);
        }
    }
    public void initMatchForLine(final WaySegments otherLine) {
        if(!lineMatches.containsKey(otherLine.way.osm_id)) {
            lineMatches.put(otherLine.way.osm_id, new LineMatch(otherLine));
        }
    }
    public void addMatchForLine(final WaySegments otherLine, final SegmentMatch match) {
        final LineMatch curMatch = lineMatches.get(otherLine.way.osm_id);
        curMatch.addMatch(match);
    }
    public LineMatch getMatchForLine(final WaySegments otherLine) {
        return lineMatches.get(otherLine.way.osm_id);
    }
    public void summarizeMatchesForLine(final WaySegments routeLine) {
        final LineMatch curMatch = lineMatches.get(routeLine.way.osm_id);
        curMatch.summarize();
    }
    /**
     * Inserts a node on the given segment, splitting it into two segments
     * NOTE: does not check if node lies on onSegment!
     * @param node The node to add
     * @param onSegment The segment to add the node to
     * @return If an existing node is within the tolerance distance, that node, otherwise the input node
     */
    public OSMNode insertNode(final OSMNode node, final LineSegment onSegment) {
        final Point nodePoint = node.getCentroid();

        //create a new segment starting from the node, and ending at onSegment's destination Point
        final LineSegment insertedSegment = new LineSegment(this, nodePoint, onSegment.destinationPoint, node, onSegment.destinationNode, onSegment.segmentIndex + 1, onSegment.nodeIndex + 1);
        insertedSegment.copyMatches(onSegment);

        //and truncate onSegment to the new node's position
        onSegment.destinationPoint = nodePoint;
        onSegment.destinationNode = node;

        //increment the node index of all following segments
        for(final LineSegment segment : segments) {
            if(segment.segmentIndex > onSegment.segmentIndex) {
                segment.segmentIndex++;
                segment.nodeIndex++;
            }
        }

        segments.add(insertedSegment.segmentIndex, insertedSegment);
        way.insertNode(node, insertedSegment.nodeIndex);

        return node;
    }
    /**
     * Maps the "oneway" tag of the way to the OneWayDirection enum
     * @param way
     * @return
     */
    public static OneWayDirection determineOneWayDirection(final OSMWay way) {
        final String oneWayTag = way.getTag("oneway");

        //check the oneway status of the way
        if(oneWayTag == null) {
            return OneWayDirection.none;
        } else if(oneWayTag.equals(OSMEntity.TAG_YES)) {
            return OneWayDirection.forward;
        } else if(oneWayTag.equals("-1")) {
            return OneWayDirection.backward;
        } else {
            return OneWayDirection.none;
        }
    }
    /**
     * Appends a node to the current segments list
     * TODO: split long segments into smaller ones
     * @param node
     * @throws InvalidArgumentException
     */
    public void appendNode(final OSMNode node) throws InvalidArgumentException {
        if(segments.size() == 0) {
            String[] errMsg = {"No existing line segments!"};
            throw new InvalidArgumentException(errMsg);
        }
        final LineSegment lastSegment = segments.get(segments.size() - 1);

        //the new segment starts from the last segment's destinationPoint
        final LineSegment newSegment = new LineSegment(this, lastSegment.destinationPoint, node.getCentroid(), lastSegment.destinationNode, node, lastSegment.segmentIndex + 1, lastSegment.nodeIndex + 1);
        segments.add(newSegment);
        way.appendNode(node);
    }
    public WaySegments[] split(final OSMNode[] splitNodes, final OSMEntitySpace entitySpace) throws InvalidArgumentException {
        final List<OSMNode> actualSplitNodes = new ArrayList<>(splitNodes.length);
        for(final OSMNode node : splitNodes) {
            if(node == way.getFirstNode() || node == way.getLastNode()) {
                continue;
            }
            actualSplitNodes.add(node);
        }

        if(actualSplitNodes.size() == 0) {
            System.out.println("NO SPLITTER!");
            return new WaySegments[]{this};
        }

        final List<Point> preSplitPoints = new ArrayList<>(segments.size() + 1);
        if(segments.size() > 0) {
            preSplitPoints.add(segments.get(0).originPoint);
            for (final LineSegment segment : segments) {
                preSplitPoints.add(segment.destinationPoint);
            }
        }

        //run the split on the underlying way
        final OSMWay[] splitWays = entitySpace.splitWay(way, actualSplitNodes.toArray(new OSMNode[actualSplitNodes.size()]));

        //create a WaySegments object for each way
        System.out.println("Line " + way.getTag("name") + "(" + way.osm_id + ") split: ");
        final WaySegments[] splitWaySegments = new WaySegments[splitWays.length];
        int idx = 0;
        for(final OSMWay splitWay : splitWays) {
            final WaySegments ws;
            if(splitWay != way) {
                ws = new WaySegments(this, splitWay);
            } else {
                ws = this;
            }
            splitWaySegments[idx++] = ws;

            System.out.println("\t" + ws.way.osm_id + ": from " + ws.way.getFirstNode().osm_id + " to " + ws.way.getLastNode().osm_id + ": " + ws.segments.size() + " segments, " + ws.way.getNodes().size() + " nodes");
        }


        //notify the observers of the split
        if(observers != null) {
            for (final WaySegmentsObserver observer : observers) {
                observer.waySegmentsWasSplit(this, splitWaySegments);
            }
        }

        return splitWaySegments;
    }
    public void addObserver(final WaySegmentsObserver observer) {
        if(observers == null) {
            observers = new ArrayList<>(64);
        }
        if(!observers.contains(observer)) {
            observers.add(observer);
        }
    }
}
