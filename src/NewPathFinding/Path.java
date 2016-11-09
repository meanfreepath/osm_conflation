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
import java.util.stream.Collectors;

/**
 * Represents a possible (non-branched) path between two nodes
 * Created by nick on 1/27/16.
 */
public class Path {
    private final static int INITIAL_PATH_SEGMENT_CAPACITY = 64, INITIAL_DETOUR_PATH_SEGMENT_CAPACITY = 8;
    public static boolean debugEnabled = false;

    public enum PathOutcome {
        waypointReached, deadEnded, lengthLimitReached, detourLimitReached, unknown
    }

    private final List<PathSegment> pathSegments, detourPathSegments;
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
        firstPathSegment = lastPathSegment = initialSegment;
        if(initialSegment != null) {
            addPathSegment(initialSegment, -1);
        }
    }
    protected Path(final Path pathToClone, final PathSegment segmentToAdd) {
        parentPathTree = pathToClone.parentPathTree;
        pathSegments = new ArrayList<>(pathToClone.pathSegments.size() + INITIAL_PATH_SEGMENT_CAPACITY);
        detourPathSegments = new ArrayList<>(pathToClone.detourPathSegments.size() + INITIAL_DETOUR_PATH_SEGMENT_CAPACITY);
        detourSegmentCount = 0;
        detourSegmentLength = totalSegmentLength = 0.0;

        //add all the pathToClone's PathSegments, up to the given node
        for(final PathSegment pathSegment : pathToClone.pathSegments) {
            addPathSegment(pathSegment, -1);

            //don't process any PathSegment past the new PathSegment's origin node - i.e. where the Path is branched
            if(pathSegment.getEndNode() == segmentToAdd.getOriginNode()) {
                break;
            }
        }

        //and finally, add the new PathSegment to the list
        addPathSegment(segmentToAdd, -1);
    }
    protected boolean advance(final List<RouteLineSegment> routeLineSegmentsToConsider, final ListIterator<Path> pathIterator, final PathTree parentPathTree, final RouteConflator routeConflator, final List<OSMNode> iterationProcessedNodes, final boolean debug) {
        //bail if the outcome has already been determined for this Path
        if(outcome != PathOutcome.unknown) {
            return false;
        }

        //advance the last segment on this Path
        final boolean didAdvancePathSegment = lastPathSegment.advance(routeLineSegmentsToConsider, parentPathTree, routeConflator, debug);

        /**
         * If the last PathSegment was successfully processed, check its ending node for ways sharing that node; we need to
         * check whether to fork this path or continue on
         */
        switch(lastPathSegment.getProcessingStatus()) {
            case complete: //PathSegment processed normally
                final OSMNode endingNode = lastPathSegment.getEndNode();

                /**
                 * check if the ending node on the last-processed PathSegment was processed on the current iteration:
                 * if so, the Path has looped and will be discarded
                 */
                if(iterationProcessedNodes.contains(endingNode)) {
                    outcome = PathOutcome.deadEnded;
                    break;
                }
                iterationProcessedNodes.add(endingNode);

                //get a list of PathSegments that originate from the lastPathSegment's ending node:
                final List<PathSegment> divergingPathSegments = determineOutgoingPathSegments(routeConflator, endingNode, lastPathSegment);
                if(divergingPathSegments.size() == 0) { //no diverging paths - dead end
                    outcome = PathOutcome.deadEnded;
                } else {
                    //add the first diverging PathSegment to this Path - no need to create a separate path for it
                    addPathSegment(divergingPathSegments.get(0), -1);
                    advance(routeLineSegmentsToConsider, pathIterator, parentPathTree, routeConflator, iterationProcessedNodes, debug);

                    //and create (divergingPathCount - 1) new Paths to handle the possible branches
                    final ListIterator<PathSegment> divergingPathIterator = divergingPathSegments.listIterator(1);
                    while (divergingPathIterator.hasNext()) {
                        final PathSegment divergingPathSegment = divergingPathIterator.next();
                        final Path newPath = new Path(this, divergingPathSegment);
                        pathIterator.add(newPath);
                        if(debug) {
                            System.out.println("\tAdded new path beginning at " + divergingPathSegment.getOriginNode() + ", way " + divergingPathSegment.getLine().way.osm_id);
                        }
                    }
                }
                break;
            case reachedDestination: //PathSegment contains the destination stop
                outcome = PathOutcome.waypointReached;
                break;
            case pendingAdvance:
            case pendingActivation:
                //do nothing - waiting until the RouteLineSegment passes nearby again (if ever)
                break;
            case noFirstTraveledSegment:
            case zeroSegmentMatches:
            case failedSegmentMatch:
                outcome = PathOutcome.deadEnded;
                break;
            case inprocess: //shouldn't happen
                break;
        }
        return didAdvancePathSegment;
    }
    private void addPathSegment(final PathSegment pathSegment, final int atIndex) {
        if(atIndex < 0) { //i.e. add at end
            pathSegments.add(pathSegment);
            lastPathSegment = pathSegment;
            if(firstPathSegment == null) {
                firstPathSegment = pathSegments.get(0);
            }
        } else {
            pathSegments.add(atIndex, pathSegment);
            lastPathSegment = pathSegments.get(pathSegments.size() - 1);
            if(atIndex == 0) {
                firstPathSegment = pathSegment;
            }
        }
        totalSegmentLength += pathSegment.traveledSegmentLength;
        pathSegment.addContainingPath(this);

        //System.out.println("Added PathSegment " + pathSegment);
    }
    protected void insertPathSegment(final PathSegment newPathSegment, final PathSegment afterPathSegment) {
        final int index = pathSegments.indexOf(afterPathSegment);
        if(index < 0) {
            System.out.format("WARNING: %s not in pathSegments array!\n", afterPathSegment);
            return;
        } else {
            System.out.println("ADDED PATHSEGMENT " + newPathSegment);
        }
        addPathSegment(newPathSegment, index + 1);
    }
    public List<PathSegment> getPathSegments() {
        return pathSegments;
    }
    public double getTotalScore() {
        double pathScore = 0.0;
        for(final PathSegment segment : pathSegments) {
            pathScore += segment.getScore();
        }
        //System.out.format("Path has waypoint/path scores: %.01f/%.01f\n", waypointScore, pathScore);
        return pathScore / pathSegments.size();
    }
    @Override
    public String toString() {
        final List<String> streets = new ArrayList<>(pathSegments.size());
        streets.addAll(pathSegments.stream().map(PathSegment::toString).collect(Collectors.toList()));
        final String lastNodeId = lastPathSegment.getEndNode() != null ? Long.toString(lastPathSegment.getEndNode().osm_id) : "N/A";
        return String.format("Path[%d->%s] outcome %s: %s", firstPathSegment.getOriginNode().osm_id, lastNodeId, outcome.toString(), String.join("->", streets));
    }
    /*@Override
    public void finalize() throws Throwable {
        System.out.println("PATHDESTROY " + this);
        super.finalize();
    }*/
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
        for (int segmentIndex = 0; segmentIndex < pathSegments.size(); segmentIndex++) {
            final PathSegment pathSegment = pathSegments.get(segmentIndex);
            if (previousSegment == null) { //skip the first iteration
                previousSegment = pathSegment;
                continue;
            }

            //int origSize = pathSegments.size();
            if(debugEnabled) {
                System.out.println("DEBUG: Check for Split: " + pathSegment);
            }
            final OSMWay pathSegmentWay = pathSegment.getLine().way, previousSegmentWay = previousSegment.getLine().way;
            final OSMNode pathSegmentOriginNode = pathSegment.getOriginNode();

            //only need to split if the current pathSegment is on a different way than the previous pathSegment
            if (pathSegmentWay != previousSegmentWay) {
                if(debugEnabled) {
                    System.out.format("DEBUG: \tDifferent ways: %d[%d->%d] vs %d[%d->%d]\n", previousSegmentWay.osm_id, previousSegmentWay.getFirstNode().osm_id, previousSegmentWay.getLastNode().osm_id, pathSegmentWay.osm_id, pathSegmentWay.getFirstNode().osm_id, pathSegmentWay.getLastNode().osm_id);
                }
                final OSMNode splitNodes[] = {pathSegmentOriginNode};

                //split the previous pathSegment if it does not end with the first/last node of its contained way
                if (pathSegmentOriginNode != previousSegmentWay.getFirstNode() && pathSegmentOriginNode != previousSegmentWay.getLastNode()) {
                    if(debugEnabled) {
                        System.out.format("DEBUG: \tSPLIT PREVIOUS PS at %d: %s\n", pathSegmentOriginNode.osm_id, previousSegment);
                    }
                    previousSegment.getLine().split(splitNodes, entitySpace);
                }

                //split the current pathSegment if it does not start with the first/last node of its contained way
                if (pathSegmentOriginNode != pathSegmentWay.getFirstNode() && pathSegmentOriginNode != pathSegmentWay.getLastNode()) {
                    if(debugEnabled) {
                        System.out.format("\tSPLIT CURRENT PS at %d: %s\n", pathSegmentOriginNode.osm_id, pathSegment);
                    }
                    pathSegment.getLine().split(splitNodes, entitySpace);
                }
            }
            previousSegment = pathSegment;
        }

        //DEBUG TEST: run a bunch of splits on the pathSegments' ways and validate the result
        /*final ArrayList<PathSegment> middleSplits = new ArrayList<>(pathSegments.size());
        final ArrayList<OSMNode[]> splitNodesList = new ArrayList<>(pathSegments.size());
        for(final PathSegment pathSegment : pathSegments) {
            if(pathSegment.getLine().way.getNodes().size() > 2) {
                final OSMNode[] allNodes = pathSegment.getLine().way.getNodes().toArray(new OSMNode[pathSegment.getLine().way.getNodes().size()]);
                splitNodesList.add(Arrays.copyOfRange(allNodes, 1, pathSegment.getLine().way.getNodes().size() - 1));
                middleSplits.add(pathSegment);
                break;
            }
        }

        int idx = 0;
        for(final PathSegment middleSplitSeg : middleSplits) {
            int origSize = pathSegments.size();
            final OSMNode[] splitNodes = splitNodesList.get(idx++);
            StringBuilder sb = new StringBuilder(splitNodes.length);
            for(final OSMNode node : splitNodes) {
                sb.append(node.osm_id);
                sb.append(",");
            }
            System.out.format("MIDDLE SPLIT TEST AT nodes [%s] ON %d[%d->%d]\n", sb.toString(), middleSplitSeg.getLine().way.osm_id, middleSplitSeg.getLine().way.getFirstNode().osm_id, middleSplitSeg.getLine().way.getLastNode().osm_id);
            try {
                middleSplitSeg.getLine().split(splitNodes, entitySpace);
            } catch(Exception e) {
                e.printStackTrace();
            }
            if(origSize != pathSegments.size()) {
                System.out.println("SIZE MIDDLE INCREASED FROM " + origSize + " TO " + pathSegments.size());
                System.out.println(this);
            } else {
                System.out.println("NO SIZE MIDDLE INCREASE");
            }
            validatePath();
        }//*/
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
        final OSMNode pathBeginNode = (OSMNode) entitySpace.addEntity(parentPathTree.originStop.getStopPosition(parentPathTree.route.routeType), OSMEntity.TagMergeStrategy.keepTags, null);
        OSMNode matchOriginNode = pathBeginNode, matchLastNode;
        for (final PathSegment pathSegment : pathSegments) {
            final OSMRelation pathRelation = entitySpace.createRelation(null, null);
            pathRelation.setTag("name", String.format("PathSegment %d:%d | %s", pathIndex, segmentIndex++, pathSegment.getId()));
            pathRelation.setTag("way_id", Long.toString(pathSegment.getLine().way.osm_id));
            pathRelation.setTag("way_name", pathSegment.getLine().way.getTag(OSMEntity.KEY_NAME));

            boolean inPathSegment = false;
            if(pathSegment.travelDirection == PathSegment.TravelDirection.forward) {
                final OSMNode destinationNode = pathSegment.getEndNode() != null ? pathSegment.getEndNode() : pathSegment.getLine().way.getLastNode();
                final ListIterator<LineSegment> iterator = pathSegment.getLine().segments.listIterator();

                while (iterator.hasNext()) {
                    final LineSegment lineSegment = iterator.next();

                    //skip processing until we reach the contained part of the PathSegment
                    if(!inPathSegment) {
                        if(lineSegment.originNode == pathSegment.originNode) {
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
                final OSMNode destinationNode = pathSegment.getEndNode() != null ? pathSegment.getEndNode() : pathSegment.getLine().way.getFirstNode();

                while (iterator.hasPrevious()) {
                    final LineSegment lineSegment = iterator.previous();

                    if(!inPathSegment) { //skip processing until we reach the contained part of the PathSegment
                        if(lineSegment.destinationNode == pathSegment.originNode) {
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
    public int validatePath() {
        PathSegment previousPathSegment = null;
        int errorCount = 0;
        for(final PathSegment pathSegment : pathSegments) {
            //if(previousPathSegment != null && pathSegment.originNode != previousPathSegment.getEndNode()) {
            if(previousPathSegment != null && pathSegment.originNode != previousPathSegment.getEndNode()) {
                errorCount++;
                System.out.format("ERROR #%d: Invalid PathSegment order\n\t%s\n\t%s\n", errorCount, previousPathSegment, pathSegment);
            }
            previousPathSegment = pathSegment;
        }
        return errorCount;
    }

    protected static List<PathSegment> determineOutgoingPathSegments(final RouteConflator routeConflator, final OSMNode junctionNode, final PathSegment incomingPathSegment) {
        if(junctionNode.containingWayCount == 0) { //shouldn't happen unless dataset is out of sync
            System.out.format("ERROR: no containing ways found for junction node %s\n", junctionNode);
            return new ArrayList<>();
        }
        final List<PathSegment> divergingPathSegments = new ArrayList<>(junctionNode.containingWayCount - 1);

        for(final OSMWay containingWay : junctionNode.containingWays.values()) {
            final OSMWaySegments line = routeConflator.getCandidateLines().get(containingWay.osm_id);
            if(line == null) {
                System.out.println("ERROR: no WaySegments found for " + containingWay);
                continue;
            }

            //determine the travel direction (relative to the way) that will take us AWAY from the previous end node
            if(incomingPathSegment == null || line.way.osm_id != incomingPathSegment.getLine().way.osm_id) { //i.e. first node on a PathTree, or transitioning to a new line
                //if the junction is an ending point for the way, create a single diverging PathSegments, traveling away from the node
                if (junctionNode == containingWay.getFirstNode()) { //node is first node on way: just travel forward
                    checkCreateNewPathSegment(line, junctionNode, PathSegment.TravelDirection.forward, divergingPathSegments);
                } else if (junctionNode == containingWay.getLastNode()) { //node is last node on way: just travel backward
                    checkCreateNewPathSegment(line, junctionNode, PathSegment.TravelDirection.backward, divergingPathSegments);
                } else { //if the junction is a midpoint for the way, create 2 PathSegments, one for each possible direction
                    checkCreateNewPathSegment(line, junctionNode, PathSegment.TravelDirection.forward, divergingPathSegments);
                    checkCreateNewPathSegment(line, junctionNode, PathSegment.TravelDirection.backward, divergingPathSegments);
                }
            } else if (line.way.osm_id == incomingPathSegment.getLine().way.osm_id) {
                //if the junction is in the middle of the incomingPathSegment's containing way, create a new one beginning at the node and continuing in the same direction
                if (junctionNode != containingWay.getFirstNode() && junctionNode != containingWay.getLastNode()) {
                    checkCreateNewPathSegment(line, junctionNode, incomingPathSegment.travelDirection, divergingPathSegments);
                }
            }
        }
        return divergingPathSegments;
    }
    private static void checkCreateNewPathSegment(final OSMWaySegments line, final OSMNode originNode, final PathSegment.TravelDirection travelDirection, final List<PathSegment> divergingPathSegments) {
        //TODO implement filtering?
        //if the line has a decent SegmentMatch

        //and if we travel it we're OK with the future Vector

        //then add it to the list
        divergingPathSegments.add(PathSegment.createNewPathSegment(line, originNode, travelDirection));
    }

}
