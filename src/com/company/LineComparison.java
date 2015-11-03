package com.company;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Region;
import Overpass.ApiClient;
import Overpass.Exceptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 11/3/15.
 */
public class LineComparison {
    private OSMWay mainLine;
    private List<OSMWay> candidateLines, matchingLines;
    private ComparisonOptions options;

    private class LineSegments {
        public final OSMWay line;
        public final List<OSMWay> segments;

        public LineSegments(OSMWay line) {
            this.line = line;

            //generate a list of line segments out of this line
            segments = new ArrayList<>(line.nodes.size() - 1);
            OSMWay segmentWay = OSMWay.create();
            OSMNode lastNode = null;
            for(OSMNode node: line.nodes) {
                if(segmentWay.nodes.size() == 2) {
                    segments.add(segmentWay); //add the completed segment

                    //and create a new segment, adding the last node to it
                    segmentWay = OSMWay.create();
                    segmentWay.addNode(lastNode);
                }
                segmentWay.addNode(node);
                lastNode = node;
            }
        }
    }

    public static class ComparisonOptions {
        public double maxSegmentDistance, maxSegmentAngle;

        public ComparisonOptions() {

        }
    }

    public LineComparison(OSMWay line) {
        mainLine = line;

        //fetch the lines that intersect our line's bounding box
        candidateLines = fetchOverlappingWays(mainLine.getBoundingBox());
    }
    private List<OSMWay> fetchOverlappingWays(Region boundingBox) {
        ArrayList<OSMWay> lines = new ArrayList<>(100);

        HashMap<String, String> apiConfig = new HashMap<>(1);
        apiConfig.put("debug", "1");
        ApiClient overpassClient = new ApiClient(null, apiConfig);
        try {
            String WAY_QUERY_FORMAT = "way(%.04f,%.04f,%.04f,%.04f)";
            System.out.println(mainLine.toString());
            String query = String.format(WAY_QUERY_FORMAT, boundingBox.origin.latitude, boundingBox.origin.longitude, boundingBox.extent.latitude, boundingBox.extent.longitude);

            overpassClient.get(query, false);
        } catch (Exceptions.UnknownOverpassError unknownOverpassError) {
            unknownOverpassError.printStackTrace();
        }
        return lines;
    }

    public void matchLines(ComparisonOptions options) {
        LineSegments mainSegments = new LineSegments(mainLine);
        ArrayList<LineSegments> candidateSegments = new ArrayList<>(candidateLines.size());
        for(OSMWay candidateWay: candidateLines) {
            candidateSegments.add(new LineSegments(candidateWay));
        }

        //now loop through the segment collections, comparing them to the main line
        for(LineSegments candidate : candidateSegments) {
            for(OSMWay mainLineSegment : mainSegments.segments) {
                for(OSMWay candidateLineSegment : candidate.segments) { //TODO need to travel both ways
                    compareSegments(mainLineSegment, candidateLineSegment, options);
                }
            }
        }

    }
    private void compareSegments(final OSMWay segment1, final OSMWay segment2, final ComparisonOptions options) {
        double avgDistance = 0.0, angle = 0.0;
        //take the dot product

        //find the average distance

        if(avgDistance <= options.maxSegmentDistance && angle <= options.maxSegmentAngle) {

        }
    }
}
