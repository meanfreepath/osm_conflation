package com.company;

import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for an OSM way and its calculated line segments
 * Created by nick on 11/9/15.
 */
public class WaySegments {
    public final OSMWay line;
    public final List<LineSegment> segments;

    public WaySegments(final OSMWay line, boolean debug) {
        this.line = line;

        //generate a list of line segments out of this line
        segments = new ArrayList<>(line.getNodes().size() - 1); //TODO base on total line length, to handle the newly
        OSMNode originNode = line.getNodes().get(0);
        int mainSegmentIndex = 0, segmentIndex = 0;
        for(final OSMNode destinationNode: line.getNodes()) {
            if (destinationNode == originNode) { //skip the first iteration
                continue;
            }

            //first get the distance between the 2 nodes
            final double vectorX = destinationNode.getLon() - originNode.getLon(), vectorY = destinationNode.getLat() - originNode.getLat();
            final double latitudeFactor = Math.cos(Math.PI * originNode.getLat() / 180.0);
            final double segmentLength = Math.sqrt(vectorX * vectorX * latitudeFactor * latitudeFactor + vectorY * vectorY) * LineSegment.DEGREE_DISTANCE_AT_EQUATOR;

            //if less than the length threshold, add as a segment
            if (segmentLength < LineSegment.MAX_SEGMENT_LENGTH) {
                final OSMWay segmentWay = OSMWay.create();
                segmentWay.addNode(originNode);
                segmentWay.addNode(destinationNode);
                segments.add(new LineSegment(this, segmentWay));
                if (debug) {
                    segmentWay.setTag("name", "Segment #" + mainSegmentIndex + "/" + segmentIndex);
                }
                segmentIndex++;
            } else { //otherwise, split into a number of segments, each equal to or shorter than the maximum segment length
                final int segmentsToAdd = (int) Math.ceil(segmentLength / LineSegment.MAX_SEGMENT_LENGTH);
                OSMNode miniOriginNode = originNode, miniDestinationNode;
                double destinationLat, destinationLon;
                for (int seg = 0; seg < segmentsToAdd; seg++) {
                    if (seg < segmentsToAdd - 1) {
                        destinationLon = miniOriginNode.getLon() + vectorX / segmentsToAdd;
                        destinationLat = miniOriginNode.getLat() + vectorY / segmentsToAdd;
                        miniDestinationNode = OSMNode.create(destinationLat, destinationLon);
                    } else { //the last subsegment's destination node is the original destination node
                        miniDestinationNode = destinationNode;
                    }

                    final OSMWay miniSegmentWay = OSMWay.create();
                    miniSegmentWay.addNode(miniOriginNode);
                    miniSegmentWay.addNode(miniDestinationNode);
                    segments.add(new LineSegment(this, miniSegmentWay));
                    if (debug) {
                        miniSegmentWay.setTag("name", "Segment #" + mainSegmentIndex + "/" + segmentIndex);
                    }

                    miniOriginNode = miniDestinationNode;
                    segmentIndex++;
                }
            }
            //System.out.println("END MAINSEGMENT #" + mainSegmentIndex + ": " + originNode.osm_id + "-" + destinationNode.osm_id);
            originNode = destinationNode;
            mainSegmentIndex++;
        }
    }
}
