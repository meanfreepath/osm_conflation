package com.company;

import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import OSM.OSMWay;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 11/23/15.
 */
public class Path implements Cloneable {
    public List<PathSegment> pathSegments = new ArrayList<>(256);
    public final OSMEntitySpace entitySpace;
    private static int idSequence = 0;
    public final int id;

    public int segmentCount = 0;
    public double scoreSegments = 0.0, scoreStops = 0.0, scoreAdjust = 0.0, scoreTotal = 0.0;
    public StopWayMatch firstStopOnPath = null, lastStopOnPath = null;
    public int stopsOnPath = 0;

    public Path(final OSMEntitySpace space) {
        entitySpace = space;
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
        if(lastSegment != null) {
            if(segment.parentPathSegment != lastSegment) {
                System.out.println("INCORRECT CHILD!");
            }
            lastSegment.endingNode = segment.originatingNode;
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
                    if(stopMatch.bestMatch != null) {
                        stopsOnPath++;
                        if(stopMatch.stopIndex != lastStopIndex + 1) {
                            stopsInOrder = false;
                        }
                    }
                    lastStopIndex = stopMatch.stopIndex;
                }
            }

            scoreSegments += segment.getScoreSegments();
            scoreStops += segment.getScoreStops();
            scoreAdjust += segment.getScoreAdjust();
        }

        //if the path contains the first and last stops of the route, it gets a bonus
        if(lastStopOnPath != null && firstStopOnPath.isFirstStop() && lastStopOnPath.isLastStop()) {
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

    /**
     * Checks the PathSegments' originating/ending nodes and splits any ways that extend past them
     * Only needs to be called on the "best" path
     */
    public void splitWaysAtIntersections() {
        for(final PathSegment segment : pathSegments) {
            segment.usedWay = segment.line.way;
            final List<OSMNode> splitNodes = new ArrayList<>(2);

            //check if we should split at the origin node
            if(segment.originatingNode != segment.line.way.getFirstNode() && segment.originatingNode != segment.line.way.getLastNode()) {
                if(PathTree.debug) {
                    segment.originatingNode.setTag("split", "begin");
                }
                splitNodes.add(segment.originatingNode);
            }

            //and at the ending node.  NB: the very last segment on the path will not have an ending node
            if(segment.endingNode != null && segment.endingNode != segment.line.way.getFirstNode() && segment.endingNode != segment.line.way.getLastNode()) {
                if(PathTree.debug) {
                    segment.endingNode.setTag("split", "end");
                }
                splitNodes.add(segment.endingNode);
            }

            if(splitNodes.size() == 0) {
                //System.out.println("DON'T SPLIT " + segment.line.way.getTag("name") + " (" + segment.line.way.osm_id + ")");
                continue;
            }

            //and run the split
            //System.out.println("SPLIT " + segment.line.way.getTag("name") + " (" + segment.line.way.osm_id + ")" + " with " + splitNodes.size() + " nodes");
            try {
                final OSMWay splitWays[] = entitySpace.splitWay(segment.line.way, splitNodes.toArray(new OSMNode[splitNodes.size()]));

                //determine which of the newly-split ways to use for the PathSegment's usedWay property
                for(final OSMWay splitWay : splitWays) {
                    //System.out.println("Check split for " + splitWay.osm_id + " - " + (splitWay.getFirstNode() != null ? splitWay.getFirstNode().osm_id : "NULL") + ":" + (splitWay.getLastNode() != null ? splitWay.getLastNode().osm_id : "NULL") + " vs " + segment.originatingNode.osm_id + ":" + (segment.endingNode != null ? segment.endingNode.osm_id : "NULL"));
                    if(splitWay.getFirstNode() == segment.originatingNode && splitWay.getLastNode() == segment.endingNode || splitWay.getLastNode() == segment.originatingNode && splitWay.getFirstNode() == segment.endingNode) {
                        //System.out.println("Way Split: using way " + splitWay.osm_id);
                        segment.usedWay = splitWay;
                    }
                }
            } catch (InvalidArgumentException e) {
                e.printStackTrace();
            }
        }
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
        final Path newPath = new Path(entitySpace);
        newPath.pathSegments.addAll(pathSegments);
        newPath.segmentCount = segmentCount;
        newPath.scoreSegments = scoreSegments;
        newPath.scoreStops = scoreStops;
        newPath.scoreAdjust = scoreAdjust;
        return newPath;
    }
}
