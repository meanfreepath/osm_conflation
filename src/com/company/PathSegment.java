package com.company;

import OSM.OSMEntity;
import OSM.OSMNode;
import OSM.OSMWay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;

/**
 * Created by nick on 11/10/15.
 */
public class PathSegment {
    private final static int INITIAL_CHILD_CAPACITY = 8;
    public static boolean debug = true;
    public static int maxDepth = 0, pathsProcessed = 0;

    public final WaySegments line;
    public final PathSegment parentPathSegment;
    public final List<PathSegment> childPathSegments = new ArrayList<>(INITIAL_CHILD_CAPACITY);
    public final int oneWayDirection;
    public OSMNode originatingNode; //the node this path segment "begins" from

    private class LineIntersection {
        public final WaySegments intersectingLine;
        public final OSMNode intersectingNode;
        public LineIntersection(final WaySegments intersectingLine, final OSMNode intersectingNode) {
            this.intersectingLine = intersectingLine;
            this.intersectingNode = intersectingNode;
        }
    }

    public PathSegment(final PathSegment parent, final WaySegments startingWay, final OSMNode originatingNode) {
        parentPathSegment = parent;
        line = startingWay;
        this.originatingNode = originatingNode;
        oneWayDirection = determineOneWayDirection(line.line);
    }
    private static int determineOneWayDirection(OSMWay way) {
        final String oneWayTag = way.getTag("oneway");

        //check the oneway status of the way
        if(oneWayTag == null) {
            return 0;
        } else if(oneWayTag.equals(OSMEntity.TAG_YES)) {
            return 1;
        } else if(oneWayTag.equals("-1")) {
            return 2;
        } else {
            return 0;
        }
    }
    public boolean process(final HashMap<Long, WaySegments> availableLines, int depth) {
        /*TODO: check direction of the main way, and direction we're traveling
        TODO: Don't add path for any of the following reasons:
            - oneway tag on this way forbids entry
            - the other way in question was already added to the path (i.e. a looping path)
            -
            - turn restriction prevents transition to way (TODO)
        */

        depth++;
        maxDepth = Math.max(maxDepth, depth);
        String debugBuffer = new String();
        for(int d=0;d<depth;d++) {
            debugBuffer += " ";
        }

        /*TODO determine which way to "walk" the current way, based on the segment matches' dot product (+ is forward, - is backward)
          - if backward, reverse the walk direction
          - when you reach a node, check the intersecting ways
            - check suitability (dot product, matching segment count, score)
          - repeat until dead-ended
        */

        //determine the direction of this line relative to the direction of route travel
        final List<OSMNode> segmentNodes = new ArrayList<>(line.line.getNodes().size());
        //final List<LineSegment> segmentList;
        boolean ourDirectionForward = line.matchObject.getAvgDotProduct() >= 0.0;

        final OSMNode firstNode, lastNode;
        //TODO: check oneway status
        if(ourDirectionForward) {
            if(oneWayDirection == 2) { //oneway runs counter our direction of travel
                System.out.println(debugBuffer + depth + ": PATH BLOCKS ENTRY TO " + line.line.getTag("name"));
                return false;
            }

            firstNode = line.segments.get(0).originNode;
            lastNode = line.segments.get(line.segments.size() - 1).destinationNode;

            //if entering from the originating node would cause us to go counter to the oneway, also bail
            if(originatingNode == lastNode) {
                System.out.println(debugBuffer + depth + ": 2PATH BLOCKS ENTRY TO " + line.line.getTag("name"));
                return false;
            }

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            segmentNodes.add(firstNode); //add the first node
            final ListIterator<LineSegment> iterator = line.segments.listIterator();
            while (iterator.hasNext()) {
                final LineSegment segment = iterator.next();
                if(segment.matchingSegments.size() > 0) {
                    if(segment.destinationNode != null) {
                        segmentNodes.add(segment.destinationNode);
                    }
                }
            }
        } else {
            if(oneWayDirection == 1) { //oneway runs counter our direction of travel
                System.out.println(debugBuffer + depth + ": PATH BLOCKS ENTRY TO " + line.line.getTag("name") + " (" + line.line.osm_id + ")");
                return false;
            }

            firstNode = line.segments.get(line.segments.size() - 1).destinationNode;
            lastNode = line.segments.get(0).originNode;

            //if entering from the originating node would cause us to go counter to the oneway, also bail
            if(originatingNode == lastNode) {
                System.out.println(debugBuffer + depth + ": 2PATH BLOCKS ENTRY TO " + line.line.getTag("name"));
                return false;
            }

            //walk the segments of this PathSegment's way, checking whether they contain nodes that intersect other ways
            segmentNodes.add(firstNode); //add the first node
            final ListIterator<LineSegment> iterator = line.segments.listIterator(line.segments.size());
            while (iterator.hasPrevious()) {
                final LineSegment segment = iterator.previous();
                if(segment.matchingSegments.size() > 0) {
                    if(segment.originNode != null) {
                        segmentNodes.add(segment.originNode);
                    }
                }
            }
        }

        //if this segment is the root segment of a Path, default its originating node to the "first" (based on our direction of travel) node on the path
        if(parentPathSegment == null && originatingNode == null) {
            originatingNode = firstNode;
        }

        //and generate the list of lines that intersect this line, to check for path continuation
        //System.out.println(debugBuffer + depth + ": CHECK INT for " + line.line.getTag("name") + " (" + line.line.osm_id + ")");
        List<LineIntersection> intersectingLines = new ArrayList<>(INITIAL_CHILD_CAPACITY);
        for(final OSMNode containedNode : segmentNodes) {
            //don't allow paths back through the originating node of this path
            if(containedNode == originatingNode) {
                //System.out.println(debugBuffer + depth + ": Skipping originating node " + containedNode.osm_id);
                continue;
            }

            //if the node is contained within 2+ ways, it's an intersection node
            if(containedNode.containingWayCount > 1) {
                for(final OSMWay containingWay : containedNode.containingWays.values()) {
                    //add to the array if not the same as this line, and not the parent's line (i.e. no U-turns)
                    if(containingWay.osm_id != line.line.osm_id){// && (parentPathSegment == null || containingWay.osm_id != parentPathSegment.line.line.osm_id)) {
                        //NOTE: the intersecting way may not be part of the availableLines array if it's too far from the route's path.  This is normal
                        final WaySegments matchedLine = availableLines.get(containingWay.osm_id);
                        if(matchedLine != null) {
                            intersectingLines.add(new LineIntersection(matchedLine, containedNode));
                           // System.out.println(debugBuffer + depth + ": ADDED " + containingWay.getTag("name") + " (" + containingWay.osm_id + ")");
                        } else {
                         //   System.out.println(debugBuffer + depth + ": NO Waysegments FOR  " + containingWay.getTag("name") + " (" + containingWay.osm_id + ")???");
                        }
                    } else {
                       // System.out.println(debugBuffer + depth + ": SKIPPED " + containingWay.getTag("name") + " (" + containingWay.osm_id + ")");
                    }
                }
            }
        }

        //if no intersecting ways, this is a dead end
        if(intersectingLines.size() == 0) {
            System.out.println(debugBuffer + depth + ": DEAD END at " + line.line.getTag("name") + " (" + line.line.osm_id + ")");
            return false;
        }

        PathSegment curPathSegment;
        System.out.println(debugBuffer + depth + ":CHECK " + line.line.getTag("name") + " (" + line.line.osm_id + "): " + intersectingLines.size() + " intersecting");
        for(final LineIntersection intersection : intersectingLines) {
            System.out.print(debugBuffer + depth + ":WITH " +  intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")");

            //if the current way doesn't contain any matching segments, skip it
            if(intersection.intersectingLine.matchObject.matchingSegmentCount == 0) {
                System.out.println(":::NO MATCH for " + line.line.getTag("name") + "(" + line.line.osm_id + ")->" + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")");
                continue;
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
                System.out.println(":::PREVADD " + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")");
                continue;
            }

            //TODO check if a turn restriction prevents turning at this junction

            //if there's a path from this way to the intersecting way, add a path
            System.out.println(":::add path " +  line.line.getTag("name") + "(" + line.line.osm_id + ")->" + intersection.intersectingLine.line.getTag("name") + " (" + intersection.intersectingLine.line.osm_id + ")");
            final PathSegment childPathSegment = new PathSegment(this, intersection.intersectingLine, intersection.intersectingNode);
            if(childPathSegment.process(availableLines, depth)) {
                childPathSegments.add(childPathSegment);
            }
        }

        return true;
    }
    public double calculateScore() {
        double totalScore = 0.0;
        for(PathSegment childPathSegment : childPathSegments) {
            totalScore += childPathSegment.calculateScore();
        }
        return totalScore;
    }
}
