package com.company;

import OSM.OSMNode;

import java.util.*;

/**
 * Created by nick on 11/10/15.
 */
public class PathTree {
    public Path bestPath = null;

    public void findPaths(List<StopWayMatch> stops, HashMap<Long, WaySegments> availableLines) {
        //first find the point on the nearest way closest to the toNode and fromNode
        final OSMNode firstStop = stops.get(0).stopPositionNode;
        final WaySegments firstLine = stops.get(0).candidateSegment.parentSegments;

        //create the initial path segment from the way closest to the first stop
        final PathSegment startPathSegment;
        if(firstStop != null) {
            startPathSegment = new PathSegment(null, firstLine, firstStop);
        } else {
            startPathSegment = new PathSegment(null, firstLine, null); //the path will figure out the originating node in this case
        }
        startPathSegment.process(availableLines, 0);

        //with all the path segments generated, connect them together into unique paths
        final ArrayList<Path> allPaths = new ArrayList<>(128);
        compileChildren(startPathSegment, allPaths);

        //and determine the best path out of them
        for(final Path path : allPaths) {
            if(bestPath == null || bestPath.score < path.score) {
                bestPath = path;
            }
        }
        System.out.println("BEST PATH SCORE: " + bestPath.score + ", PATH: " + bestPath.toString());
    }

    private void compileChildren(final PathSegment pathSegment, ArrayList<Path> allPaths) {
        if(pathSegment.childPathSegments.size() == 0) { //i.e. pathSegment terminates here
            final Path curPath = new Path();
            //walk this node's parent paths, creating a new pathSegment and returning
            PathSegment curPathSegment = pathSegment;
            while(curPathSegment != null) {
                curPath.prependSegment(curPathSegment);
                curPathSegment = curPathSegment.parentPathSegment;
            }
            allPaths.add(curPath);
        } else {
            for(final PathSegment childPathSegment : pathSegment.childPathSegments) {
                compileChildren(childPathSegment, allPaths);
            }
        }
    }
}
