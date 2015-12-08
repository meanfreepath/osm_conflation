package com.company;

import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

/**
 * Represents a possible segment on a route Path.
 * Created by nick on 11/10/15.
 */
public class PathSegment {
    private final static int INITIAL_CHILD_CAPACITY = 8;
    private final static double SCORE_FOR_DETOUR = -40.0, SCORE_FOR_STOP_ON_WAY = 100.0, SCORE_FOR_ALIGNMENT = 1.0;
    private enum OneWayDirection {
        none, forward, backward
    }

    public final static long[] debugFilterIds = {};

    public static boolean checkDetours = true;
    private static int idSequence = 0;

    private final PathTree parentPathTree;
    public final int id, debugDepth;
    public final WaySegments line;
    public final PathSegment parentPathSegment;
    public final List<PathSegment> childPathSegments = new ArrayList<>(INITIAL_CHILD_CAPACITY);
    public final OneWayDirection oneWayDirection;
    public OSMNode originatingNode; //the node this path segment "begins" from

    private double scoreSegments, scoreStops, scoreAdjust;

    private class LineIntersection {
        public final WaySegments intersectingLine;
        public final OSMNode intersectingNode;
        public LineIntersection(final WaySegments intersectingLine, final OSMNode intersectingNode) {
            this.intersectingLine = intersectingLine;
            this.intersectingNode = intersectingNode;
        }
    }

    public PathSegment(final PathTree parentPathTree, final PathSegment parent, final WaySegments startingWay, final OSMNode originatingNode, final double scoreAdjust, final int debugDepth) {
        id = ++idSequence;
        this.parentPathTree = parentPathTree;
        parentPathSegment = parent;
        line = startingWay;
        this.originatingNode = originatingNode;
        oneWayDirection = determineOneWayDirection(line.line);
        scoreSegments = scoreStops = 0.0;
        this.scoreAdjust = scoreAdjust;
        this.debugDepth = debugDepth;
    }

    /**
     * Maps the "oneway" tag of the way to the OneWayDirection enum
     * @param way
     * @return
     */
    private static OneWayDirection determineOneWayDirection(final OSMWay way) {
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
     * Checks the validity of this segment for addition to a path of travel.
     * @param linesInRouteBoundingBox: the ways that intersect this route's bounding box
     * @return TRUE if this segment is valid, FALSE if not
     */
    public boolean process(final HashMap<Long, WaySegments> linesInRouteBoundingBox) {

        //determine the direction of this line relative to the direction of route travel
        final List<OSMNode> segmentNodes = new ArrayList<>(line.line.getNodes().size());
        boolean ourDirectionForward = line.matchObject.getAvgDotProduct() >= 0.0;

        //boost the score if there's are stops on the current line segment
        StopWayMatch lastStopOnSegment = null;
        if(line.matchObject.stopMatches != null) {
            for(final StopWayMatch stopMatch : line.matchObject.stopMatches) {
                if (stopMatch.bestMatch != null) {
                    scoreStops += SCORE_FOR_STOP_ON_WAY;
                    lastStopOnSegment = stopMatch;
                    debugLog("HAS MATCHING TRANSIT STOP (id " + stopMatch.stopPositionNode.osm_id + ")\n");
                }
            }
        }

        final OSMNode firstNode, lastNode;
        if(ourDirectionForward) { //i.e. we're traveling along the natural direction of this way
            if(oneWayDirection == OneWayDirection.backward) { //oneway runs counter our direction of travel
                debugLog("PATH BLOCKS ENTRY TO " + line.line.getTag("name") + " (" + line.line.osm_id + ")\n");
                return false;
            }

            firstNode = line.segments.get(0).originNode;
            lastNode = line.segments.get(line.segments.size() - 1).destinationNode;

            //if entering from the originating node would cause us to go counter to the oneway, also bail
            if(originatingNode == lastNode) {
                debugLog("REVPATH BLOCKS ENTRY TO " + line.line.getTag("name") + " (" + line.line.osm_id + ")\n");
                return false;
            }

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            segmentNodes.add(firstNode); //add the first node
            final ListIterator<LineSegment> iterator = line.segments.listIterator();
            while (iterator.hasNext()) {
                final LineSegment segment = iterator.next();
                if(checkDetours || segment.matchingSegments.size() > 0) {
                    if(segment.destinationNode != null) {
                        segmentNodes.add(segment.destinationNode);
                    }
                    if(segment.bestMatch != null) {
                        scoreSegments += SCORE_FOR_ALIGNMENT * Math.abs(segment.bestMatch.dotProduct) / segment.bestMatch.midPointDistance;
                    }
                }
            }
        } else { //i.e. we're traveling against the natural direction of this way
            if(oneWayDirection == OneWayDirection.forward) { //oneway runs counter our direction of travel
                debugLog("PATH BLOCKS ENTRY TO " + line.line.getTag("name") + " (" + line.line.osm_id + ")\n");
                return false;
            }

            firstNode = line.segments.get(line.segments.size() - 1).destinationNode;
            lastNode = line.segments.get(0).originNode;

            //if entering from the originating node would cause us to go counter to the oneway, also bail
            if(originatingNode == lastNode) {
                debugLog("REVPATH BLOCKS ENTRY TO " + line.line.getTag("name") + " (" + line.line.osm_id + ")\n");
                return false;
            }

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            segmentNodes.add(firstNode); //add the first node
            final ListIterator<LineSegment> iterator = line.segments.listIterator(line.segments.size());
            while (iterator.hasPrevious()) {
                final LineSegment segment = iterator.previous();
                if(checkDetours || segment.matchingSegments.size() > 0) {
                    if(segment.originNode != null) {
                        segmentNodes.add(segment.originNode);
                        if(segment.bestMatch != null) {
                            scoreSegments += SCORE_FOR_ALIGNMENT * Math.abs(segment.bestMatch.dotProduct) / segment.bestMatch.midPointDistance;
                        }
                    }
                }
            }
        }

        //if this segment is the root segment of a Path, default its originating node to the "first" (based on our direction of travel) node on the path
        if(parentPathSegment == null && originatingNode == null) {
            originatingNode = firstNode;
        }

        //if there's a stop on this path segment, and it's the last stop in the route, we're done: no need to check further connecting ways
        if(lastStopOnSegment != null && lastStopOnSegment.isLastStop()) {
            return true;
        }

        //and generate the list of lines that intersect this line, to check for path continuation
        debugLog("CHECK INTERSECTION for " + line.line.getTag("name") + " (" + line.line.osm_id + "):\n");
        final List<LineIntersection> intersectingLines = new ArrayList<>(INITIAL_CHILD_CAPACITY);
        for(final OSMNode containedNode : segmentNodes) {
            //don't allow paths back through the originating node of this path (i.e. no U-turns)
            if(containedNode == originatingNode) {
                debugLog("Skipping originating node " + containedNode.osm_id + "\n");
                continue;
            }

            //if the node is contained within 2+ ways, it's an intersection node
            if(containedNode.containingWayCount > 1) {
                for(final OSMWay containingWay : containedNode.containingWays.values()) {
                    //skip this line (will be part of every node's containingWays array)
                    if(containingWay.osm_id == line.line.osm_id) {
                        //debugLog("SKIPPED " + containingWay.getTag("name") + " (" + containingWay.osm_id + ") at node " + containedNode.osm_id + "\n");
                        continue;
                    }

                    //check if the given way is part of the linesInRouteBoundingBox array - may not be if it's too far from the route's path (which is OK)
                    final WaySegments matchedLine = linesInRouteBoundingBox.get(containingWay.osm_id);
                    if(matchedLine != null) {
                        intersectingLines.add(new LineIntersection(matchedLine, containedNode));
                        debugLog("ADDED " + containingWay.getTag("name") + " (" + containingWay.osm_id + ") at node " + containedNode.osm_id + "\n");
                    } else {
                       debugLog("NO Waysegments FOR  " + containingWay.getTag("name") + " (" + containingWay.osm_id + ") at node " + containedNode.osm_id + "\n");
                    }
                }
            }
        }

        //if no intersecting ways, this is a dead end
        final int intersectingLineCount = intersectingLines.size();
        if(intersectingLineCount == 0) {
            debugLog("DEAD END at " + line.line.getTag("name") + " (" + line.line.osm_id + ")\n");
            return false;
        }

        //now check the intersecting lines to see whether they're good segments to follow
        PathSegment curPathSegment;
        debugLog("NOW CHECK " + line.line.getTag("name") + " (" + line.line.osm_id + "): " + intersectingLines.size() + " intersecting\n");
        for(final LineIntersection intersection : intersectingLines) {
            debugLog("WITH " + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")");

            double childScoreAdjust = 0.0;
            //if the current way doesn't contain any matching segments, skip it
            if(intersection.intersectingLine.matchObject.matchingSegmentCount == 0) {
                if(!checkDetours || intersectingLineCount > 1) {
                    debugLog(":::NO MATCH for " + line.line.getTag("name") + "(" + line.line.osm_id + ")->" + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")\n");
                    continue;
                } else {
                    childScoreAdjust = SCORE_FOR_DETOUR;
                    debugLog(":::SPUR MATCH for " + line.line.getTag("name") + "(" + line.line.osm_id + ")->" + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")\n");
                }
            }

            //check the current way isn't already on the path (i.e. looping back on itself)
            boolean onPath = false;
            curPathSegment = this;
            while (curPathSegment.parentPathSegment != null) {
                if (curPathSegment.line.line.osm_id == intersection.intersectingLine.line.osm_id) {
                    onPath = true;
                    break;
                }
                curPathSegment = curPathSegment.parentPathSegment;
            }
            if (onPath) {
                debugLog(":::PREVADD " + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")");
                continue;
            }

            //TODO check if a turn restriction prevents turning at this junction

            //if there's a path from this way to the intersecting way, add a path
            debugLog(":::add path " + line.line.getTag("name") + "(" + line.line.osm_id + ")->" + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")\n");
            final PathSegment childPathSegment = new PathSegment(parentPathTree, this, intersection.intersectingLine, intersection.intersectingNode, childScoreAdjust, debugDepth + 1);
            if(childPathSegment.process(linesInRouteBoundingBox)) {
                childPathSegments.add(childPathSegment);
            }
        }

        return true;
    }
    public double getScoreSegments() {
        return scoreSegments;
    }

    public double getScoreStops() {
        return scoreStops;
    }
    public double getScoreAdjust() {
        return scoreAdjust;
    }
    private void debugLog(final String message) {
        if(PathTree.debug) {
            boolean output = false;
            if(debugFilterIds.length > 0) {
                for(short i=0;i<debugFilterIds.length;i++) {
                    if(line.line.osm_id == debugFilterIds[i]) {
                        output = true;
                        break;
                    }
                }
            } else {
                output = true;
            }

            if(output) {
                String debugPadding = "";
                if(!message.startsWith(":")) {
                    for (int d = 0; d < debugDepth; d++) {
                        debugPadding += " ";
                    }
                    debugPadding += debugDepth + ": ";
                }
                //System.out.print(debugPadding + message);
                parentPathTree.writeDebug(debugPadding + message);
            }
        }
    }
}
