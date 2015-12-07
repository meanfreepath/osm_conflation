package com.company;

import OSM.OSMEntity;
import OSM.OSMNode;

import javax.swing.text.NumberFormatter;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Created by nick on 11/23/15.
 */
public class Path implements Cloneable {
    public List<PathSegment> pathSegments = new ArrayList<>(256);
    private static int idSequence = 0;
    public final int id;

    public int segmentCount = 0;
    public double scoreSegments = 0.0, scoreStops = 0.0, scoreAdjust = 0.0, scoreTotal = 0.0;
    public StopWayMatch firstStopOnPath = null, lastStopOnPath = null;

    public Path() {
        id = ++idSequence;
    }

    public PathSegment getLastPathSegment() {
        if(segmentCount > 0) {
            return pathSegments.get(segmentCount - 1);
        } else {
            return null;
        }
    }
    public void addSegment(final PathSegment segment) {
        //the current segment should descend from the last segment on this path
        final PathSegment lastSegment = getLastPathSegment();
        if(lastSegment != null && segment.parentPathSegment != lastSegment) {
            System.out.println("INCORRECT CHILD!");
        }
        pathSegments.add(segment);
        segmentCount++;
    }

    /**
     * Calculate the score of this path, based on its adherence to the route's shapeline,
     * with heavy emphasis on whether it contains the route's stops
     */
    public void calculateScore() {
        scoreSegments = scoreStops = scoreAdjust = 0.0;

        boolean stopsInOrder = true;
        int lastStopIndex = -1;
        firstStopOnPath = null;
        lastStopOnPath = null;
        for(final PathSegment segment : pathSegments) {
            //track whether the stops on this path are in order (strong indicator of accuracy)
            if(segment.line.matchObject.stopMatches != null) {
                for(final StopWayMatch stopMatch : segment.line.matchObject.stopMatches) {
                    //track the first/last stops on the path
                    if(firstStopOnPath == null) {
                        firstStopOnPath = stopMatch;
                    }
                    lastStopOnPath = stopMatch;

                    //check if the current stop comes directly after the previous stop
                    if(stopMatch.bestMatch != null && stopMatch.stopIndex != lastStopIndex + 1) {
                        stopsInOrder = false;
                    }
                    lastStopIndex = stopMatch.stopIndex;
                }
            }

            scoreSegments += segment.getScoreSegments();
            scoreStops += segment.getScoreStops();
            scoreAdjust += segment.getScoreAdjust();
        }

        //if the path contains the stops *in order*, give it a big bonus
        if(lastStopOnPath != null && stopsInOrder) {
            scoreStops *= 10.0;

            //and a further bonus if it contains the first and last stops
            if(firstStopOnPath.isFirstStop() && lastStopOnPath.isLastStop()) {
                scoreStops *= 2.0;
            }
        }

        if(segmentCount > 0) {
            scoreSegments /= segmentCount;
        }
        scoreTotal = scoreSegments + scoreStops + scoreAdjust;
    }
    /*public void validate() {
        PathSegment lastSegment = null;
        for(final PathSegment curSegment : pathSegments) {
            if(lastSegment != null && curSegment.parentPathSegment != lastSegment) {
               System.out.println("WRONG PATHSEG!");
            } else {
                System.out.println("**OK PATHSEG!");
            }
            lastSegment = curSegment;
        }
    }*/
    public String toString() {
        final StringBuilder pathStr = new StringBuilder(pathSegments.size() * 32);
        for(final PathSegment pathSegment : pathSegments) {
            pathStr.append(pathSegment.line.line.getTag("name"));
            pathStr.append(":");
            pathStr.append(pathSegment.line.line.osm_id);
            pathStr.append("->");
        }
        pathStr.replace(pathStr.length() - 2, pathStr.length(), "\n");
        return pathStr.toString();
    }
    public boolean containsEntity(final long osm_id, final OSMEntity.OSMType type) {
        if(type == OSMEntity.OSMType.way) {
            for (final PathSegment pathSegment : pathSegments) {
                if (pathSegment.line.line.osm_id == osm_id) {
                    return true;
                }
            }
        } else if(type == OSMEntity.OSMType.node) {
            for(final PathSegment pathSegment : pathSegments) {
                for(final OSMNode node : pathSegment.line.line.getNodes()) {
                    if(node.osm_id == osm_id) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    public String scoreSummary() {
        final DecimalFormat format = new DecimalFormat("0.00");
        final StringBuilder output = new StringBuilder(pathSegments.size() * 128);
        for(final PathSegment segment : pathSegments) {
            output.append(segment.line.line.getTag(OSMEntity.KEY_NAME));
            output.append(": ");
            output.append(format.format(segment.getScoreSegments()));
            output.append("/");
            output.append(format.format(segment.getScoreStops()));
            output.append("/");
            output.append(format.format(segment.getScoreAdjust()));
            output.append("\n");
        }
        output.append("Total score: ");
        output.append(format.format(scoreSegments));
        output.append("/");
        output.append(format.format(scoreStops));
        output.append("/");
        output.append(format.format(scoreAdjust));
        output.append("=");
        output.append(format.format(scoreTotal));
        output.append("\n");

        return output.toString();
    }
    @Override
    public Path clone() {
        final Path newPath = new Path();
        newPath.pathSegments.addAll(pathSegments);
        newPath.segmentCount = segmentCount;
        newPath.scoreSegments = scoreSegments;
        newPath.scoreStops = scoreStops;
        newPath.scoreAdjust = scoreAdjust;
        return newPath;
    }
}
