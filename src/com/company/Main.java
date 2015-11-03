package com.company;

import OSM.OSMNode;
import OSM.OSMWay;

public class Main {

    public static void main(String[] args) {
	    // write your code here
        double[][] mainPoints = {{34, -118}, {34.001, -118.001}, {34.002, -118.002}};
        OSMWay mainLine = OSMWay.create();
        for(double[] point : mainPoints) {
            OSMNode node = OSMNode.create(point[0], point[1]);
            mainLine.addNode(node);
        }

        LineComparison comparison = new LineComparison(mainLine);
        LineComparison.ComparisonOptions options = new LineComparison.ComparisonOptions();
        options.maxSegmentAngle = 10.0;
        options.maxSegmentDistance = 1.0;
        comparison.matchLines(options);
    }
}
