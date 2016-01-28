package com.company;

import Conflation.Route;
import Conflation.StopArea;
import Conflation.WaySegments;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by nick on 11/10/15.
 */
public class PathTree {
    public Path bestPath = null;
    public final static boolean debug = true;
    private final FileWriter debugFileWriter;
    public final Route route;

    public PathTree(final Route route) {
        this.route = route;
        if(debug) {
            FileWriter writer = null;
            try {
                writer = new FileWriter("pathDebug" + route.routeRelation.osm_id);
            } catch (IOException e) {
                e.printStackTrace();
            }
            debugFileWriter = writer;
        } else {
            debugFileWriter = null;
        }
    }
    public void findPaths(final HashMap<Long, WaySegments> availableLines) {
        //first find the point on the nearest way closest to the toNode and fromNode
        final StopArea firstStop = route.stops.size() > 0 ? route.stops.get(0) : null;


        //create the initial path segment
        final PathSegment startPathSegment;
        if(firstStop != null && firstStop.wayMatches.bestMatch != null) { //if a stop match is present, use the way closest to the first stop
            startPathSegment = new PathSegment(this, null, firstStop.wayMatches.bestMatch.candidateSegmentMatch/*.matchingSegment*/.parentSegments, firstStop.getStopPosition(), 0.0, 0);
        } else { //otherwise, use the way closest to the start of the main route path line
            //startPathSegment = new PathSegment(null, firstLine, null); //the path will figure out the originating node in this case
            startPathSegment = null; //TODO
            System.out.println("ERROR: first stop for " + route.routeRelation.getTag("name") + " is not near a road or railway!");
            return;
        }

        startPathSegment.process(availableLines);

        //with all the path segments generated, connect them together into unique paths
        /*final ArrayList<Path> allPaths = new ArrayList<>(128);
        compileChildren(startPathSegment, allPaths);*/

        final Path initialPath = new Path(this);
        initialPath.addSegment(startPathSegment);
        final ArrayList<Path> allPaths = new ArrayList<>(65536);
        compileChildren(initialPath, allPaths);

        //and determine the best path out of them
        for(final Path path : allPaths) {
            if(bestPath == null || bestPath.scoreTotal < path.scoreTotal) {
                bestPath = path;
            }
        }

        if(debug) {
            /*final double scoreThreshold = 0.95 * bestPath.scoreTotal;
            final List<Path> goodPaths = new ArrayList<>(128);
            for(final Path path : allPaths) {
                if(path.scoreTotal >= scoreThreshold) {
                    //goodPaths.add(path);
                }
                //if(path.containsEntity(53071891, OSMEntity.OSMType.node)) {
                if(path.containsEntity(240874287, OSMEntity.OSMType.way)) {
                    goodPaths.add(path);
                }
            }
            goodPaths.sort(new Comparator<Path>() {
                @Override
                public int compare(Path o1, Path o2) {
                    return o1.scoreTotal > o2.scoreTotal ? 1 : -1;
                }
            });

            for(final Path path : goodPaths) {
             //   System.out.print("GOOD PATH (" + path.segmentCount + " segs): SCORE (out of " + allPaths.size() + "): #" + path.id + ", score (" + path.scoreSegments + "/" + path.scoreStops + "/" + path.scoreAdjust + ") = " + path.scoreTotal + ", first/last stop " + bestPath.lastStopOnPath.platformNode.getTag("name") + ":" + Boolean.toString(bestPath.firstStopOnPath.isFirstStop()) + "/" + Boolean.toString(bestPath.lastStopOnPath.isLastStop()) + "\nSEGMENTS: " + path.toString());
            }*/
            if(bestPath != null) {
                System.out.println("BEST PATH SCORE (out of " + allPaths.size() + "): #" + bestPath.id + ", score (" + bestPath.scoreSegments + "/" + bestPath.scoreStops + "/" + bestPath.scoreAdjust + ") = " + bestPath.scoreTotal + ", stops: " + bestPath.stopsOnPath + "/" + route.stops.size() + ", first/last stop:" + (bestPath.firstStopOnPath != null ? Boolean.toString(route.stopIsFirst(bestPath.firstStopOnPath)) : "NULL") + "/" + (bestPath.lastStopOnPath != null ? Boolean.toString(route.stopIsLast(bestPath.lastStopOnPath)) : "NULL") + "\nSEGMENTS: " + bestPath.toString());
                System.out.print(bestPath.scoreSummary());
            } else {
                System.out.println("NO BEST PATH");
            }
        }

        if(debugFileWriter != null) {
            try {
                debugFileWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    private void compileChildren(final Path path, final ArrayList<Path> allPaths) {
        final PathSegment pathSegment = path.getLastPathSegment();
        final int childCount = pathSegment.childPathSegments.size();
        if(childCount > 0) { //process the segment's children
            int idx = 0;

            //create a pre-clone if there's more than 1 child segment (we want a clone of the path *prior* to the addition of any new segments)
            final Path pathTemplate;
            if(childCount > 1) {
                pathTemplate = path.clone();
            } else {
                pathTemplate = null;
            }

            for(final PathSegment childSegment : pathSegment.childPathSegments) {
                if(idx++ == 0) { //the first segment just gets added to the existing path
                    path.addSegment(childSegment);
                    compileChildren(path, allPaths);
                } else { //any additional child segments: clone the current path and add the segment to it
                    final Path newPath = pathTemplate.clone();
                    allPaths.add(newPath);

                    newPath.addSegment(childSegment);

                    //and process the new path's children
                    compileChildren(newPath, allPaths);
                }
            }
        } else { //if the last segment has no child segments, it's done: just calculate its score
            path.calculateScore();
        }
    }
    public void writeDebug(final String logMsg) {
        try {
            debugFileWriter.write(logMsg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
