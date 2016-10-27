package NewPathFinding;

import Conflation.OSMWaySegments;
import Conflation.RouteConflator;
import Conflation.RouteLineSegment;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import OSM.OSMWay;
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

        if(debug) {
            System.out.println("For RL#" + routeLineSegmentsToConsider.get(0) + ": Advanced segment: " + lastPathSegment);
        }

        /**
         * If the last PathSegment was successfully processed, check its ending junction for ways sharing that node), we need to
         * check whether to fork this path or continue on
         */
        if(lastPathSegment.processingStatus == PathSegment.ProcessingStatus.complete) {
            final Junction lastPathSegmentEndJunction = lastPathSegment.getEndJunction();
            final RouteLineSegment curRouteLineSegment = routeLineSegmentsToConsider.get(0);

            //check that the lastPathSegment's ending Junction is within the RouteLineSegment's search area - if not bail
            if(!curRouteLineSegment.searchAreaForMatchingOtherSegments.containsPoint(lastPathSegmentEndJunction.junctionNode.getCentroid())) {
                return false;
            }

            //get the ways that contain this junction's node:
            final List<PathSegment> divergingPathSegments = new ArrayList<>(lastPathSegmentEndJunction.junctionNode.containingWayCount - 1);
            for(final OSMWay containingWay : lastPathSegmentEndJunction.junctionNode.containingWays.values()) {
                //lookup the related WaySegments for the way
                final OSMWaySegments line = routeConflator.getCandidateLines().get(containingWay.osm_id);
                if(line == null) {
                    if(debug) {
                        System.out.println("ERROR: no WaySegments found for " + containingWay);
                    }
                    continue;
                }

                //determine the travel direction (relative to the way) that will take us AWAY from the previous endJunction
                if(line.way.osm_id == lastPathSegment.getLine().way.osm_id) {
                    //if the junction is in the middle of the lastPathSegment's containing way, create a new one beginning at the junction and continuing in the same direction
                    if(lastPathSegmentEndJunction.junctionNode != containingWay.getFirstNode() && lastPathSegmentEndJunction.junctionNode != containingWay.getLastNode()) {
                        checkCreateNewPathSegment(line, lastPathSegmentEndJunction, lastPathSegment.travelDirection, parentPathTree, divergingPathSegments);
                    }
                } else {
                    //if the junction is an ending point for the way, create a single diverging PathSegments, traveling away from the junction
                    if(lastPathSegmentEndJunction.junctionNode == containingWay.getFirstNode()) {
                        checkCreateNewPathSegment(line, lastPathSegmentEndJunction, PathSegment.TravelDirection.forward, parentPathTree, divergingPathSegments);
                    } else if(lastPathSegmentEndJunction.junctionNode == containingWay.getLastNode()) {
                        checkCreateNewPathSegment(line, lastPathSegmentEndJunction, PathSegment.TravelDirection.backward, parentPathTree, divergingPathSegments);
                    } else { //if the junction is a midpoint for the intersecting way, create 2 PathSegments, one for each possible direction
                        checkCreateNewPathSegment(line, lastPathSegmentEndJunction, PathSegment.TravelDirection.forward, parentPathTree, divergingPathSegments);
                        checkCreateNewPathSegment(line, lastPathSegmentEndJunction, PathSegment.TravelDirection.backward, parentPathTree, divergingPathSegments);
                    }
                }
            }

            if(debug) {
                System.out.println("Junction " + lastPathSegmentEndJunction.junctionNode + ": " + divergingPathSegments.size() + " diverging segments");
            }
            if(divergingPathSegments.size() == 0) { //no diverging paths - dead end
                outcome = PathOutcome.deadEnded;
            } else {
                //add the first diverging PathSegment to this Path - no need to create a separate path for it
                addPathSegment(divergingPathSegments.get(0));
                advance(routeLineSegmentsToConsider, pathIterator, parentPathTree, routeConflator, debug);

                //and create (divergingPathCount - 1) new Paths to handle the possible branches
                final ListIterator<PathSegment> divergingPathIterator = divergingPathSegments.listIterator(1);
                while (divergingPathIterator.hasNext()) {
                    final Path newPath = new Path(this, divergingPathIterator.next());
                    pathIterator.add(newPath);
                }
            }
        } else if(lastPathSegment.processingStatus == PathSegment.ProcessingStatus.reachedDestination) {
            outcome = PathOutcome.waypointReached;
        } else if(lastPathSegment.processingStatus == PathSegment.ProcessingStatus.pendingAdvance) {
            //do nothing - waiting until the RouteLineSegment passes nearby again (if ever)
        } else {
            outcome = PathOutcome.deadEnded;
        }
        return true;
    }
    private static void checkCreateNewPathSegment(final OSMWaySegments line, final Junction originJunction, final PathSegment.TravelDirection travelDirection, final PathTree parentPathTree, final List<PathSegment> divergingPathSegments) {
        //TODO implement filtering?
        //if the line has a decent SegmentMatch

        //and if we travel it we're OK with the future Vector

        //then add it to the list
        divergingPathSegments.add(PathSegment.createNewPathSegment(line, originJunction, travelDirection, parentPathTree));
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

        //also need our parent PathTree to watch for any changes (splits etc) to the PathSegment's way
        pathSegment.getLine().addObserver(parentPathTree);
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
        return String.format("Path[%d->%s]: %s", pathSegments.get(0).getOriginJunction().junctionNode.osm_id, lastNodeId, String.join("->", streets));
    }
    /**
     * Checks the PathSegments' originating/ending nodes and splits any ways that extend past them
     * Only needs to be called on the "best" path
     */
    public void splitWaysAtIntersections(final OSMEntitySpace entitySpace, final Path previousPath) throws InvalidArgumentException {
       /* int segmentIndex = 0;
        PathSegment previousSegment = null;
        final List<OSMNode> splitNodes = new ArrayList<>(2);

        boolean isLastRoutePath = parentPathTree.nextPathTree == null;

        //check if the line is the same as the previous path's line
        if(debugEnabled) {
            System.out.println("------------------------------------------------------------------------------------------------");
            System.out.println("SPLIT PATH: " + this.toString());
            System.out.print("FIRST PATH segment " + firstPathSegment + "::");
        }
        if(parentPathTree.previousPathTree == null) { //split at first stop
            if(debugEnabled) {
                System.out.println("FIRST ON ROUTE");
            }
            splitNodes.add(firstPathSegment.originJunction.junctionNode);
        } else if(previousPath != null) { //continuing a previous path
            if(debugEnabled) {
                System.out.println("CONTINUING ROUTE FROM " + previousPath.lastPathSegment);
            }
            if(previousPath.lastPathSegment.getLine() != firstPathSegment.getLine()) {
                if(debugEnabled) {
                    System.out.println("USING NEW LINE");
                }
                splitNodes.add(firstPathSegment.originJunction.junctionNode);
            } else {
                if(debugEnabled) {
                    System.out.println("USING SAME LINE");
                }
            }
        }

        for(final PathSegment pathSegment : pathSegments) {
            /**
             * Check if we should split at the origin node:
             * - this is the first node on the entire route path
             * - this is a node on a continuing path which is on a different way than the last path's final way
             *
            if(previousSegment != null) { //skip the first segment on the path
                if(debugEnabled) {
                    System.out.println("CHECK SPLIT FOR CONTINUING #" + segmentIndex + ": " + previousSegment);
                }
                //if previous path used a different line than the current path
                final boolean previousLineSegmentHasSameLine = previousSegment.getLine() == pathSegment.getLine();
                if(previousLineSegmentHasSameLine) { //if not, don't split the previous path at its end
                    if(debugEnabled) {
                        System.out.println("SAME AS LAST, removing split there");
                    }
                    splitNodes.remove(splitNodes.size() - 1);
                }

                //run the split on the *previous* segment now that we've got the info on its start/end points
                runSplit(previousSegment, splitNodes, entitySpace);

                //if the current and previous paths don't share the same line, mark the current path as needing a split for the next round
                if(!previousLineSegmentHasSameLine) {
                    splitNodes.add(pathSegment.originJunction.junctionNode);
                }
            }

            //and add the end node (will be removed on the next iteration if the PathSegment after this uses the same way)
            splitNodes.add(pathSegment.endJunction.junctionNode);
            previousSegment = pathSegment;
            segmentIndex++;
        }

        /*finally, split the last PathSegment at its end if needed.  Right now it *may* be set to split at its first node,
          and is definitely set to split at its end node.  Cancel the end node split if the path will continue on the same way
        if(debugEnabled) {
            System.out.println("SPLIT LAST SEGMENT " + lastPathSegment + "?");
        }
        if(parentPathTree.nextPathTree != null) { //i.e. there's another path after this
            final Path nextPathTreeBestPath = parentPathTree.nextPathTree.bestPath;
            if(nextPathTreeBestPath == null) { //don't split at the end if the next path wasn't determined
                if(debugEnabled) {
                    System.out.print("NEXT PATH IS NULL, NO END SPLIT: ");
                }
                splitNodes.remove(splitNodes.size() - 1);
            } else if(nextPathTreeBestPath.firstPathSegment.getLine() == lastPathSegment.getLine()) { //don't split at the end if the path starts on the same way
                if(debugEnabled) {
                    System.out.print("NEXT PATH IS SAME, NO END SPLIT: ");
                }
                splitNodes.remove(splitNodes.size() - 1);
            } else {
                if(debugEnabled) {
                    System.out.print("NEXT PATH IS DIFFERENT, SHOULD END SPLIT: ");
                }
            }
        } else { //i.e. this is the last path on the entire route: definitely split
            if(debugEnabled) {
                System.out.println("LAST ON ROUTE!");
            }
        }

        runSplit(lastPathSegment, splitNodes, entitySpace);*/
    }
    private static void runSplit(final PathSegment pathSegmentToSplit, final List<OSMNode> splitNodes, final OSMEntitySpace entitySpace) throws InvalidArgumentException {
       /* if(splitNodes.size() > 0) {
            final ArrayList<String> splitNodeIds = new ArrayList<>(splitNodes.size());
            for(final OSMNode splitNode : splitNodes) {
                splitNodeIds.add(Long.toString(splitNode.osm_id));
            }
            pathSegmentToSplit.getLine().split(splitNodes.toArray(new OSMNode[splitNodes.size()]), entitySpace);
            if(debugEnabled) {
                System.out.println("SPLIT with " + splitNodes.size() + " nodes at " + String.join(",", splitNodeIds) + ", was matched? " + Boolean.toString(pathSegmentToSplit.isLineMatchedWithRoutePath()));
            }
            splitNodes.clear();
        } else {
            if(debugEnabled) {
                System.out.println("NO SPLIT");
            }
        }*/
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
        /*int segmentIndex = 0;
        final OSMNode pathBeginNode = (OSMNode) entitySpace.addEntity(parentPathTree.fromNode, OSMEntity.TagMergeStrategy.keepTags, null);
        OSMNode matchOriginNode = pathBeginNode, matchLastNode;
        for (final PathSegment pathSegment : pathSegments) {
            final OSMRelation pathRelation = entitySpace.createRelation(null, null);
            pathRelation.setTag("name", "PathSegment " + pathIndex + ":" + segmentIndex++  + ": " + pathSegment.getId());

            boolean inPathSegment = false;
            if(pathSegment.getTravelDirection() == PathSegment.TravelDirection.forward) {
                final OSMNode destinationNode = pathSegment.endJunction.processStatus == Junction.JunctionProcessStatus.continuePath ? pathSegment.endJunction.junctionNode : pathSegment.getLine().way.getLastNode();
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
                        matchLastNode = entitySpace.createNode(lineSegment.destinationPoint.latitude, lineSegment.destinationPoint.longitude, null);
                    }

                    final OSMWay pathSegmentWay = entitySpace.createWay(null, null);
                    pathSegmentWay.appendNode(matchOriginNode);
                    pathSegmentWay.appendNode(matchLastNode);
                    pathSegmentWay.setTag("highway", lineSegment.getParent().way.getTag("highway"));
                    pathSegmentWay.setTag("segment", lineSegment.segmentIndex + "/" + lineSegment.nodeIndex);
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
                final OSMNode destinationNode = pathSegment.endJunction.processStatus == Junction.JunctionProcessStatus.continuePath ? pathSegment.endJunction.junctionNode : pathSegment.getLine().way.getFirstNode();

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
                        matchLastNode = entitySpace.createNode(lineSegment.originPoint.latitude, lineSegment.originPoint.longitude, null);
                    }

                    final OSMWay pathSegmentWay = entitySpace.createWay(null, null);
                    pathSegmentWay.appendNode(matchOriginNode);
                    pathSegmentWay.appendNode(matchLastNode);
                    pathSegmentWay.setTag("highway", lineSegment.getParent().way.getTag("highway"));
                    pathSegmentWay.setTag("segment", lineSegment.segmentIndex + "/" + lineSegment.nodeIndex);
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
        }*/
    }
}
