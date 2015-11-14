package com.company;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for an OSM way and its calculated line segments
 * Created by nick on 11/9/15.
 */
public class WaySegments {
    public final OSMWay line;
    public final List<LineSegment> segments;
    private final boolean debug;

    public WaySegments(final OSMWay line, final double maxSegmentLength, boolean debug) {
        this.line = line;
        this.debug = debug;

        //generate a list of line segments out of this line
        segments = new ArrayList<>(line.getNodes().size() - 1); //TODO base on total line length, to handle the newly
        OSMNode originNode = line.getNodes().get(0);
        int nodeIndex = 0, segmentIndex = 0;
        for(final OSMNode destinationNode: line.getNodes()) {
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

                    final LineSegment segment = new LineSegment(this, miniOrigin, miniDestination, miniOriginNode, null, segmentIndex++, nodeIndex);
                    segments.add(segment);

                    miniOrigin = miniDestination;
                    miniOriginNode = null;
                }

                //add the last segment, with its last node as the original destination node
                miniDestination = destinationNode.getCentroid();
                final LineSegment segment = new LineSegment(this, miniOrigin, miniDestination, miniOriginNode, destinationNode, segmentIndex++, nodeIndex);
                segments.add(segment);
            }
            //System.out.println("END MAINSEGMENT #" + mainSegmentIndex + ": " + originNode.osm_id + "-" + destinationNode.osm_id);
            originNode = destinationNode;
            nodeIndex++;
        }
    }

    /**
     * Inserts a node on the given segment, splitting it into two segments
     * NOTE: does not check if node lies on onSegment!
     * @param node
     * @param onSegment
     */
    public void insertNode(final OSMNode node, final LineSegment onSegment) {
        //create a new segment starting from the node, and ending at onSegment's destination Point
        final LineSegment insertedSegment = new LineSegment(this, node.getCentroid(), onSegment.destinationPoint, node, onSegment.destinationNode, onSegment.segmentIndex + 1, onSegment.nodeIndex + 1);

        //and truncate onSegment to the new node's position
        onSegment.destinationPoint = node.getCentroid();
        onSegment.destinationNode = node;

        //increment the node index of all following segments
        for(final LineSegment segment : segments) {
            if(segment.segmentIndex > onSegment.segmentIndex) {
                segment.segmentIndex++;
                segment.nodeIndex++;
            }
        }

        segments.add(insertedSegment.segmentIndex, insertedSegment);
        line.insertNode(node, insertedSegment.nodeIndex);
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
        line.appendNode(node);
    }
}
