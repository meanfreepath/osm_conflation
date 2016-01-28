package com.company;

import Conflation.Route;
import Conflation.StopArea;
import Conflation.StopWayMatch;
import OSM.OSMEntity;
import OSM.OSMNode;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 11/23/15.
 */
public class Path implements Cloneable {
    public List<PathSegment> pathSegments = new ArrayList<>(256);
    final PathTree parentPathTree;
    private static int idSequence = 0;
    public final int id;

    public int segmentCount = 0;
    public double scoreSegments = 0.0, scoreStops = 0.0, scoreAdjust = 0.0, scoreTotal = 0.0;
    public StopArea firstStopOnPath = null, lastStopOnPath = null;
    public int stopsOnPath = 0;

    public Path(final PathTree parentPathTree) {
        this.parentPathTree = parentPathTree;
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
        final Route route = parentPathTree.route;
        for(final PathSegment segment : pathSegments) {
            //track whether the stops on this path are in order (strong indicator of accuracy)
            if(segment.line.matchObject.stopMatches != null) {
                for(final StopWayMatch stopMatch : segment.line.matchObject.stopMatches) {
                    //track the first/last stops on the path
                    if(firstStopOnPath == null) {
                        firstStopOnPath = stopMatch.stopEntity;
                    }
                    lastStopOnPath = stopMatch.stopEntity;

                    //check if the current stop comes directly after the previous stop
                    final int stopIndex = route.stops.indexOf(stopMatch.stopEntity);
                    if(stopMatch.bestMatch != null) {
                        stopsOnPath++;

                        if(stopIndex != lastStopIndex + 1) {
                            stopsInOrder = false;
                        }
                    }
                    lastStopIndex = stopIndex;
                }
            }

            scoreSegments += segment.getScoreSegments();
            scoreStops += segment.getScoreStops();
            scoreAdjust += segment.getScoreAdjust();
        }

        //if the path contains the first and last stops of the route, it gets a bonus
        if(lastStopOnPath != null && route.stopIsFirst(firstStopOnPath) && route.stopIsLast(lastStopOnPath)) {
            scoreStops *= 2.0;
            //and a further bonus if it contains the stops *in order*, give it a further bonus
            if(stopsInOrder) {
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
            pathStr.append(pathSegment.line.way.getTag("name"));
            pathStr.append(":");
            pathStr.append(pathSegment.line.way.osm_id);
            pathStr.append("->");
        }
        pathStr.replace(pathStr.length() - 2, pathStr.length(), "\n");
        return pathStr.toString();
    }
    public boolean containsEntity(final long osm_id, final OSMEntity.OSMType type) {
        if(type == OSMEntity.OSMType.way) {
            for (final PathSegment pathSegment : pathSegments) {
                if (pathSegment.line.way.osm_id == osm_id) {
                    return true;
                }
            }
        } else if(type == OSMEntity.OSMType.node) {
            for(final PathSegment pathSegment : pathSegments) {
                for(final OSMNode node : pathSegment.line.way.getNodes()) {
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
            output.append(segment.line.way.getTag(OSMEntity.KEY_NAME));
            output.append(" (");
            output.append(segment.line.way.osm_id);
            output.append("): ");
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
        final Path newPath = new Path(parentPathTree);
        newPath.pathSegments.addAll(pathSegments);
        newPath.segmentCount = segmentCount;
        newPath.scoreSegments = scoreSegments;
        newPath.scoreStops = scoreStops;
        newPath.scoreAdjust = scoreAdjust;
        return newPath;
    }
}
