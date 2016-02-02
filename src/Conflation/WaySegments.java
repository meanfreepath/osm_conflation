package Conflation;

import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Container for an OSM way and its calculated line segments
 * Created by nick on 11/9/15.
 */
public class WaySegments {
    public enum OneWayDirection {
        none, forward, backward
    }

    public final OSMWay way;
    public final List<LineSegment> segments;
    public final HashMap<Long,LineMatch> lineMatches = new HashMap<>(16);
    public List<StopWayMatch> stopMatches = null;
    public final OneWayDirection oneWayDirection;

    public WaySegments(final OSMWay way, final double maxSegmentLength) {
        this.way = way;
        oneWayDirection = determineOneWayDirection(way);

        //generate a list of line segments out of this line
        segments = new ArrayList<>(way.getNodes().size() - 1); //TODO base on total line length, to handle the newly-created segments
        OSMNode originNode = way.getNodes().get(0);
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
    public void initMatchForLine(final WaySegments otherLine) {
        if(!lineMatches.containsKey(otherLine.way.osm_id)) {
            lineMatches.put(otherLine.way.osm_id, new LineMatch(this));
        }
    }
    public void addMatchForLine(final WaySegments otherLine, final SegmentMatch match) {
        final LineMatch curMatch = lineMatches.get(otherLine.way.osm_id);
        curMatch.addMatch(match);
    }
    public LineMatch getMatchForLine(final WaySegments otherLine) {
        return lineMatches.get(otherLine.way.osm_id);
    }
    public void summarizeMatchesForLine(final WaySegments otherLine) {
        final LineMatch curMatch = lineMatches.get(otherLine.way.osm_id);
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
    /**
     * Adds the given StopWayMatch to this object
     * @param stopMatch
     */
    public void addStopMatch(final StopWayMatch stopMatch) {
        if(stopMatches == null) {
            stopMatches = new ArrayList<>(4);
        }
        stopMatches.add(stopMatch);
    }
}
