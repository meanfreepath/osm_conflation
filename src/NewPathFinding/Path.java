package NewPathFinding;

import Conflation.LineSegment;
import Conflation.OSMWaySegments;
import Conflation.RouteConflator;
import Conflation.RouteLineSegment;
import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Represents a possible (non-branched) path between two junctions
 * Created by nick on 1/27/16.
 */
public class Path {
    private final static int INITIAL_PATH_SEGMENT_CAPACITY = 64, INITIAL_DETOUR_PATH_SEGMENT_CAPACITY = 8;
    private final static double PATH_SEGMENT_LOOP_PENALTY = 500.0;
    public static boolean debugEnabled = false;
    public enum PathOutcome {
        waypointReached, deadEnded, lengthLimitReached, detourLimitReached, unknown
    }

    private final List<PathSegment> pathSegments, detourPathSegments, loopedPathSegments;
    private final List<Junction> junctions;
    public final PathTree parentPathTree;
    public PathSegment firstPathSegment = null, lastPathSegment = null;
    public PathOutcome outcome = PathOutcome.unknown;

    protected int detourSegmentCount = 0;
    protected double totalSegmentLength = 0.0, detourSegmentLength = 0.0;
    //public double scoreSegments = 0.0, scoreStops = 0.0, scoreAdjust = 0.0, scoreTotal = 0.0;

    protected Path(final PathTree parentPathTree, final PathSegment initialSegment) {
        this.parentPathTree = parentPathTree;
        pathSegments = new ArrayList<>(INITIAL_PATH_SEGMENT_CAPACITY);
        detourPathSegments = new ArrayList<>(INITIAL_DETOUR_PATH_SEGMENT_CAPACITY);
        loopedPathSegments = new ArrayList<>(INITIAL_DETOUR_PATH_SEGMENT_CAPACITY);
        junctions = new ArrayList<>(INITIAL_PATH_SEGMENT_CAPACITY);
        firstPathSegment = lastPathSegment = initialSegment;
        if(initialSegment != null) {
            addPathSegment(initialSegment);
        }
    }
    protected Path(final Path pathToClone, final PathSegment segmentToAdd) {
        parentPathTree = pathToClone.parentPathTree;
        pathSegments = new ArrayList<>(pathToClone.pathSegments.size() + INITIAL_PATH_SEGMENT_CAPACITY);
        detourPathSegments = new ArrayList<>(pathToClone.detourPathSegments.size() + INITIAL_DETOUR_PATH_SEGMENT_CAPACITY);
        loopedPathSegments = new ArrayList<>(pathToClone.loopedPathSegments.size() + INITIAL_DETOUR_PATH_SEGMENT_CAPACITY);
        junctions = new ArrayList<>(pathToClone.junctions.size() + INITIAL_PATH_SEGMENT_CAPACITY);
        detourSegmentCount = 0;
        detourSegmentLength = totalSegmentLength = 0.0;

        //add all the pathToClone's PathSegments, up to the given junction
        for(final PathSegment pathSegment : pathToClone.pathSegments) {
            pathSegments.add(pathSegment);
            totalSegmentLength += pathSegment.traveledSegmentLength;
            pathSegment.addContainingPath(this);

            //also track any detour and looped PathSegments
            if(pathToClone.detourPathSegments.contains(pathSegment)) {
                detourPathSegments.add(pathSegment);
                detourSegmentCount++;
                detourSegmentLength += pathSegment.detourSegmentLength;
            }
            if(pathToClone.loopedPathSegments.contains(pathSegment)) {
                loopedPathSegments.add(pathSegment);
            }

            //don't process any PathSegment past the new PathSegment's origin junction - i.e. where the Path is branched
            if(pathSegment.getEndJunction() == segmentToAdd.getOriginJunction()) {
                break;
            }
        }

        for(final Junction junction : pathToClone.junctions) {
            addJunction(junction);
        }

        //and finally, add the new PathSegment to the list
        addPathSegment(segmentToAdd);
    }
    protected boolean advance(final List<RouteLineSegment> routeLineSegmentsToConsider, final ListIterator<Path> pathIterator, final PathTree parentPathTree, final RouteConflator routeConflator, final boolean debug) {
        //bail if the outcome has already been determined for this Path
        if(outcome != PathOutcome.unknown) {
            return false;
        }

        //advance the last segment on this Path
        final boolean advancedPathSegment = lastPathSegment.advance(routeLineSegmentsToConsider, debug);

        /**
         * If the last PathSegment was successfully processed, check its ending junction for ways sharing that node), we need to
         * check whether to fork this path or continue on
         */
        if(lastPathSegment.processingStatus == PathSegment.ProcessingStatus.complete) {
            //get a list of PathSegments that originate from the lastPathSegment's ending junction node:
            final List<PathSegment> divergingPathSegments = lastPathSegment.getEndJunction().determineOutgoingPathSegments(routeConflator, lastPathSegment);
            if(divergingPathSegments.size() == 0) { //no diverging paths - dead end
                outcome = PathOutcome.deadEnded;
            } else {
                //add the first diverging PathSegment to this Path - no need to create a separate path for it
                addPathSegment(divergingPathSegments.get(0));
                advance(routeLineSegmentsToConsider, pathIterator, parentPathTree, routeConflator, debug);

                //and create (divergingPathCount - 1) new Paths to handle the possible branches
                final ListIterator<PathSegment> divergingPathIterator = divergingPathSegments.listIterator(1);
                while (divergingPathIterator.hasNext()) {
                    final PathSegment divergingPathSegment = divergingPathIterator.next();
                    final Path newPath = new Path(this, divergingPathSegment);
                    pathIterator.add(newPath);
                    if(debug) {
                        System.out.println("\tAdded new path beginning at " + divergingPathSegment.getOriginJunction().junctionNode + ", way " + divergingPathSegment.getLine().way.osm_id);
                    }
                }
            }
        } else if(lastPathSegment.processingStatus == PathSegment.ProcessingStatus.reachedDestination) {
            outcome = PathOutcome.waypointReached;
        } else if(lastPathSegment.processingStatus == PathSegment.ProcessingStatus.pendingAdvance || lastPathSegment.processingStatus == PathSegment.ProcessingStatus.pendingActivation) {
            //do nothing - waiting until the RouteLineSegment passes nearby again (if ever)
        } else {
            outcome = PathOutcome.deadEnded;
        }
        return true;
    }
    private void addPathSegment(final PathSegment pathSegment) {
        if(pathSegments.contains(pathSegment)) { //track segments that are contained multiple times in the path
            loopedPathSegments.add(pathSegment);
        }
        pathSegments.add(pathSegment);
        totalSegmentLength += pathSegment.traveledSegmentLength;
        pathSegment.addContainingPath(this);
        if(firstPathSegment == null) {
            firstPathSegment = pathSegments.get(0);
        }
        lastPathSegment = pathSegment;

        //track detour segments
        if(pathSegment.detourPathScore > 0.0) {
            detourPathSegments.add(pathSegment);
            detourSegmentCount += pathSegment.detourSegmentCount;
            detourSegmentLength += pathSegment.detourSegmentLength;
        }
        //System.out.println("Added PathSegment " + pathSegment);
    }
    protected void addJunction(final Junction junction) {
        junctions.add(junction);
    }

    /**
     * Replace the given original segments in this path's list with newly-split PathSegments belonging to it
     * @param originalSegment
     * @param splitPathSegments
     */
    protected void replaceSplitPathSegment(final PathSegment originalSegment, final List<PathSegment> splitPathSegments) {
       /* final int originalSegmentIndex = pathSegments.indexOf(originalSegment);
        pathSegments.remove(originalSegmentIndex);
        originalSegment.removeContainingPath(this);

        //add the PathSegments in reverse order, so the segment replacing the original segment is always last (just for consistency's sake)
        final ListIterator<PathSegment> iterator = splitPathSegments.listIterator(splitPathSegments.size());
        while (iterator.hasPrevious()) {
            final PathSegment splitPathSegment = iterator.previous();
            addPathSegment(splitPathSegment);
        }


        if(pathSegments.size() > 0) {
            firstPathSegment = pathSegments.get(0);
            lastPathSegment = pathSegments.get(pathSegments.size() - 1);
        } else {
            firstPathSegment = lastPathSegment = null;
        }*/
    }
    public List<PathSegment> getPathSegments() {
        return pathSegments;
    }
    protected void markAsSuccessful(final PathSegment lastPathSegment) {
        addPathSegment(lastPathSegment);
        outcome = PathOutcome.waypointReached;
    }
    public double getTotalScore() {
        double pathScore = 0.0, waypointScore = 0.0;
        /*for(final PathSegment segment : pathSegments) {
            pathScore += segment.getPathScore();
            waypointScore += segment.getWaypointScore();
        }
        //deduct a penalty for looped PathSegments (we want the most direct route)
        for(final PathSegment loopedSegment : loopedPathSegments) {
            pathScore -= PATH_SEGMENT_LOOP_PENALTY;
        }*/
        return waypointScore + pathScore / pathSegments.size();
    }
    public List<OSMWay> getPathWays() {
        List<OSMWay> ways = new ArrayList<>(pathSegments.size());
        PathSegment lastPathSegment = null;
        for(final PathSegment pathSegment : pathSegments) {
            final OSMWay curWay = pathSegment.getLine().way;
            if(lastPathSegment != null) {
                if(curWay != lastPathSegment.getLine().way) {
                    ways.add(curWay);
                }
            } else {
                ways.add(curWay);
            }
            lastPathSegment = pathSegment;
        }
        return ways;
    }
    @Override
    public String toString() {
        final List<String> streets = new ArrayList<>(pathSegments.size());
        for(final PathSegment pathSegment : pathSegments) {
            streets.add(pathSegment.toString());
        }
        final String lastNodeId = lastPathSegment.getEndJunction() != null ? Long.toString(lastPathSegment.getEndJunction().junctionNode.osm_id) : "N/A";
        return String.format("Path[%d->%s] outcome %s: %s", firstPathSegment.getOriginJunction().junctionNode.osm_id, lastNodeId, outcome.toString(), String.join("->", streets));
    }
    @Override
    public void finalize() throws Throwable {
        System.out.println("PATHDESTROY " + this);
        super.finalize();
    }
    /**
     * Checks the PathSegments' originating/ending nodes and splits any ways that extend past them
     * Only needs to be called on the "best" path
     */
    public void splitWaysAtIntersections(final OSMEntitySpace entitySpace) throws InvalidArgumentException {
        //check if the line is the same as the previous path's line
        if(debugEnabled) {
            System.out.println("------------------------------------------------------------------------------------------------");
            System.out.println("CHECK SPLIT PATH: " + this.toString());
        }
        final Path previousPath = parentPathTree.previousPathTree != null ? parentPathTree.previousPathTree.bestPath : null;
        PathSegment previousSegment = previousPath != null ? previousPath.lastPathSegment : null;
        for(final PathSegment pathSegment : pathSegments) {
            if (previousSegment == null) { //skip the first iteration
                previousSegment = pathSegment;
                continue;
            }

            System.out.println("Check for Split: " + pathSegment);
            final OSMWay pathSegmentWay = pathSegment.getLine().way, previousSegmentWay = previousSegment.getLine().way;
            final OSMNode pathSegmentOriginNode = pathSegment.getOriginJunction().junctionNode;

            //only need to split if the current pathSegment is on a different way than the previous pathSegment
            if (pathSegmentWay != previousSegmentWay) {
                System.out.format("\tDifferent ways: %d vs %d\n", previousSegmentWay.osm_id, pathSegmentWay.osm_id);
                final OSMNode splitNodes[] = {pathSegmentOriginNode};

                //split the previous pathSegment if it does not end with the first/last node of its contained way
                if (pathSegmentOriginNode != previousSegmentWay.getFirstNode() && pathSegmentOriginNode != previousSegmentWay.getLastNode()) {
                    System.out.format("\tSPLIT PREVIOUS PS at %s:\n%s\n", pathSegmentOriginNode, previousSegment);
                    previousSegment.getLine().split(splitNodes, entitySpace);
                }

                //split the current pathSegment if it does not start with the first/last node of its contained way
                if (pathSegmentOriginNode != pathSegmentWay.getFirstNode() && pathSegmentOriginNode != pathSegmentWay.getLastNode()) {
                    System.out.format("\tSPLIT CURRENT PS at %s:\n%s\n", pathSegmentOriginNode, pathSegment);
                    pathSegment.getLine().split(splitNodes, entitySpace);
                }
            }

            previousSegment = pathSegment;
        }
    }
    /*public String scoreSummary() {
        final DecimalFormat format = new DecimalFormat("0.00");
        final StringBuilder output = new StringBuilder(pathSegments.size() * 128);
        for(final PathSegment segment : pathSegments) {
          /*  output.append(segment.line.way.getTag(OSMEntity.KEY_NAME));
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
    }*/
    public void debugOutputPathSegments(final OSMEntitySpace entitySpace, final int pathIndex) {
        int segmentIndex = 0;
        final OSMNode pathBeginNode = (OSMNode) entitySpace.addEntity(parentPathTree.originStop.getStopPosition(), OSMEntity.TagMergeStrategy.keepTags, null);
        OSMNode matchOriginNode = pathBeginNode, matchLastNode;
        for (final PathSegment pathSegment : pathSegments) {
            final OSMRelation pathRelation = entitySpace.createRelation(null, null);
            pathRelation.setTag("name", String.format("PathSegment %d:%d | %s", pathIndex, segmentIndex++, pathSegment.getId()));
            pathRelation.setTag("way_id", Long.toString(pathSegment.getLine().way.osm_id));
            pathRelation.setTag("way_name", pathSegment.getLine().way.getTag(OSMEntity.KEY_NAME));

            boolean inPathSegment = false;
            if(pathSegment.travelDirection == PathSegment.TravelDirection.forward) {
                final OSMNode destinationNode = pathSegment.getEndJunction() != null ? pathSegment.getEndJunction().junctionNode : pathSegment.getLine().way.getLastNode();
                final ListIterator<LineSegment> iterator = pathSegment.getLine().segments.listIterator();

                while (iterator.hasNext()) {
                    final LineSegment lineSegment = iterator.next();

                    //skip processing until we reach the contained part of the PathSegment
                    if(!inPathSegment) {
                        if(lineSegment.originNode == pathSegment.originJunction.junctionNode) {
                            inPathSegment = true;
                        } else {
                            continue;
                        }
                    }

                    if (lineSegment.destinationNode != null) {
                        matchLastNode = (OSMNode) entitySpace.addEntity(lineSegment.destinationNode, OSMEntity.TagMergeStrategy.keepTags, null);
                    } else {
                        matchLastNode = entitySpace.createNode(lineSegment.destinationPoint.x, lineSegment.destinationPoint.y, null);
                    }

                    final OSMWay pathSegmentWay = entitySpace.createWay(null, null);
                    pathSegmentWay.appendNode(matchOriginNode);
                    pathSegmentWay.appendNode(matchLastNode);
                    pathSegmentWay.setTag("highway", lineSegment.getParent().way.getTag("highway"));
                    pathSegmentWay.setTag("ref", lineSegment.segmentIndex + "/" + lineSegment.nodeIndex);
                    matchOriginNode.removeTag("wcount");
                    matchLastNode.removeTag("wcount");
                    pathRelation.addMember(pathSegmentWay, "");

                    matchOriginNode = matchLastNode;

                    //bail once we exit the contained part of the PathSegment
                    if(lineSegment.destinationNode == destinationNode) {
                        break;
                    }
                }
            } else {
                final ListIterator<LineSegment> iterator = pathSegment.getLine().segments.listIterator(pathSegment.getLine().segments.size());
                final OSMNode destinationNode = pathSegment.getEndJunction() != null ? pathSegment.getEndJunction().junctionNode : pathSegment.getLine().way.getFirstNode();

                while (iterator.hasPrevious()) {
                    final LineSegment lineSegment = iterator.previous();

                    if(!inPathSegment) { //skip processing until we reach the contained part of the PathSegment
                        if(lineSegment.destinationNode == pathSegment.originJunction.junctionNode) {
                            inPathSegment = true;
                        } else {
                            continue;
                        }
                    }

                    if (lineSegment.originNode != null) {
                        matchLastNode = (OSMNode) entitySpace.addEntity(lineSegment.originNode, OSMEntity.TagMergeStrategy.keepTags, null);
                    } else {
                        matchLastNode = entitySpace.createNode(lineSegment.originPoint.x, lineSegment.originPoint.y, null);
                    }

                    final OSMWay pathSegmentWay = entitySpace.createWay(null, null);
                    pathSegmentWay.appendNode(matchOriginNode);
                    pathSegmentWay.appendNode(matchLastNode);
                    pathSegmentWay.setTag("highway", lineSegment.getParent().way.getTag("highway"));
                    pathSegmentWay.setTag("ref", lineSegment.segmentIndex + "/" + lineSegment.nodeIndex);
                    pathRelation.addMember(pathSegmentWay, "");
                    matchOriginNode.removeTag("wcount");
                    matchLastNode.removeTag("wcount");

                    matchOriginNode = matchLastNode;

                    //bail once we exit the contained part of the PathSegment
                    if(lineSegment.originNode == destinationNode) {
                        break;
                    }
                }
            }
        }
    }
}
