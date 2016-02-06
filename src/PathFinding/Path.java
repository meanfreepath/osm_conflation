package PathFinding;

import Conflation.WaySegments;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import OSM.OSMWay;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a possible (non-branched) path between two junctions
 * Created by nick on 1/27/16.
 */
public class Path {
    private final static int INITIAL_PATH_SEGMENT_CAPACITY = 64;
    public enum PathOutcome {
        waypointReached, deadEnded, unknown
    }

    private final List<PathSegment> pathSegments;
    public final PathTree parentPathTree;
    public PathSegment firstPathSegment = null, lastPathSegment = null;
    public PathOutcome outcome = PathOutcome.unknown;

    public int segmentCount = 0;
    public double scoreSegments = 0.0, scoreStops = 0.0, scoreAdjust = 0.0, scoreTotal = 0.0;

    public Path(final PathTree parentPathTree, final PathSegment initialSegment) {
        this.parentPathTree = parentPathTree;
        pathSegments = new ArrayList<>(INITIAL_PATH_SEGMENT_CAPACITY);
        firstPathSegment = lastPathSegment = initialSegment;
        if(initialSegment != null) {
            addPathSegment(initialSegment);
        }
    }
    public Path(final Path pathToClone, final PathSegment segmentToAdd) {
        parentPathTree = pathToClone.parentPathTree;
        pathSegments = new ArrayList<>(pathToClone.pathSegments.size() + INITIAL_PATH_SEGMENT_CAPACITY);
        pathSegments.addAll(pathToClone.pathSegments);
        firstPathSegment = pathToClone.firstPathSegment;
        lastPathSegment = pathToClone.lastPathSegment;

        if(segmentToAdd != null) {
            addPathSegment(segmentToAdd);
        }
    }
    private void addPathSegment(final PathSegment segment) {
        pathSegments.add(segment);
        if(firstPathSegment == null) {
            firstPathSegment = pathSegments.get(0);
        }
        lastPathSegment = segment;
    }
    public List<PathSegment> getPathSegments() {
        return pathSegments;
    }
    public void markAsSuccessful(final PathSegment lastPathSegment) {
        addPathSegment(lastPathSegment);
        outcome = PathOutcome.waypointReached;
    }
    public double getTotalScore() {
        double totalScore = 0.0;
        for(final PathSegment segment : pathSegments) {
            totalScore += segment.getScore();
        }
        return totalScore;
    }
    public List<OSMWay> getPathWays() {
        List<OSMWay> ways = new ArrayList<>(pathSegments.size());
        PathSegment lastPathSegment = null;
        for(final PathSegment pathSegment : pathSegments) {
            if(lastPathSegment != null) {
                if(pathSegment.line.way != lastPathSegment.line.way) {
                    ways.add(pathSegment.line.way);
                }
            } else {
                ways.add(pathSegment.line.way);
            }
            lastPathSegment = pathSegment;
        }
        return ways;
    }
    public String toString() {
        final List<String> streets = new ArrayList<>(pathSegments.size());
        for(final PathSegment segment : pathSegments) {
            streets.add(segment.line.way.getTag(OSMEntity.KEY_NAME) + "(" + segment.line.way.osm_id + ": " + segment.originJunction.junctionNode.osm_id + " to " + (segment.getEndJunction() != null ? segment.getEndJunction().junctionNode.osm_id : "(MATCH-END)")+ ")");
        }
        return String.join("->", streets);
    }
    /**
     * Checks the PathSegments' originating/ending nodes and splits any ways that extend past them
     * Only needs to be called on the "best" path
     */
    public void splitWaysAtIntersections(final OSMEntitySpace entitySpace) {
        //only split ways on successful paths
        if(outcome != PathOutcome.waypointReached) {
            return; //TODO check prev/next pathTrees as well?
        }

        final WaySegments previousPathTreeLine = parentPathTree.previousPathTree != null ? parentPathTree.previousPathTree.toLine : null;
        final WaySegments nextPathTreeLine = parentPathTree.nextPathTree != null ? parentPathTree.nextPathTree.fromLine: null;
        int segmentIndex = 0;
        final int lastSegmentIndex = pathSegments.size() - 1;
        PathSegment previousSegment = null;
        for(final PathSegment segment : pathSegments) {
            segment.usedWay = segment.line.way;
            final List<OSMNode> splitNodes = new ArrayList<>(2);

            final boolean isFirstSegmentOnPath = segmentIndex == 0, isLastSegmentOnPath = segmentIndex == lastSegmentIndex;
            boolean splitAtFirstNode = false, splitAtLastNode = false;
            //final boolean sharesPreviousSegmentWay
            /**
             * Check if we should split at the origin node:
             * - this is the first node on the entire route path
             * - this is a node on a continuing path which is on a different way than the last path's final way
             */
            if(!isFirstSegmentOnPath) { //"middle" path segments only check for splitting based on first/last nodes
                if(previousSegment != null && previousSegment.line.way.osm_id != segment.line.way.osm_id && segment.originJunction.junctionNode != segment.line.way.getFirstNode() && segment.originJunction.junctionNode != segment.line.way.getLastNode()) {
                    if (PathTree.debugEnabled) {
                        segment.originJunction.junctionNode.setTag("split", "begin");
                    }
                    splitAtFirstNode = true;
                    splitNodes.add(segment.originJunction.junctionNode);
                }
            } else { //the first path segment also checks whether we're continuing on the same way as the last path: if so, no need to split.
                //if this is the first path segment on the entire route, or we're continuing on the route path from a different way than the last path, we split
                if (previousPathTreeLine == null || previousPathTreeLine.way.osm_id != segment.line.way.osm_id && segment.originJunction.junctionNode != segment.line.way.getFirstNode() && segment.originJunction.junctionNode != segment.line.way.getLastNode()) {
                    if (PathTree.debugEnabled) {
                        segment.originJunction.junctionNode.setTag("split", "begin");
                    }
                    splitAtFirstNode = true;
                    splitNodes.add(segment.originJunction.junctionNode);
                }
            }

            //and at the ending node (only check on last segment, since above code block handles splitting for all other segments
            if(isLastSegmentOnPath) {
                final OSMNode endingNode = segment.getEndJunction() != null ? segment.getEndJunction().junctionNode : null;
                //check if the next path's starting way is the same as this segments, or if this is the last segment on the entire route path
                if(nextPathTreeLine == null || nextPathTreeLine.way.osm_id != segment.line.way.osm_id && endingNode != segment.line.way.getFirstNode() && endingNode != segment.line.way.getLastNode()) {
                    if (PathTree.debugEnabled) {
                        endingNode.setTag("split", "end");
                    }
                    splitNodes.add(endingNode);
                    splitAtLastNode = true;
                }
            }

            segmentIndex++;
            previousSegment = segment;

            if(splitNodes.size() == 0) {
                //System.out.println("DON'T SPLIT " + segment.line.way.getTag("name") + " (" + segment.line.way.osm_id + ")");
                continue;
            }

            //and run the split
            final ArrayList<String> splitNodeIds = new ArrayList<>(splitNodes.size());
            for(final OSMNode splitNode : splitNodes) {
                splitNodeIds.add(Long.toString(splitNode.osm_id));
            }
            System.out.println("SPLIT " + segment /*segment.line.way.getTag("name") + " (" + segment.line.way.osm_id + ")"*/ + " with " + splitNodes.size() + " nodes (f/l " + Boolean.toString(splitAtFirstNode) + "/" + Boolean.toString(splitAtLastNode) + "): " + String.join(",", splitNodeIds));
            try {
                final OSMWay splitWays[] = entitySpace.splitWay(segment.line.way, splitNodes.toArray(new OSMNode[splitNodes.size()]));

                //determine which of the newly-split ways to use for the PathSegment's usedWay property
                if(splitAtFirstNode) { //if split at the beginning, the way should begin/end at the origin junction, and contain the end junction
                    System.out.println("IS FIRST SEGMENT");
                    for(final OSMWay splitWay : splitWays) {
                        if((splitWay.getFirstNode() == segment.originJunction.junctionNode || splitWay.getLastNode() == segment.originJunction.junctionNode) && splitWay.getNodes().contains(segment.getEndJunction().junctionNode)) {
                            segment.usedWay = splitWay;
                            break;
                        }
                    }
                } else if(splitAtLastNode) {
                    System.out.println("IS LAST SEGMENT");
                    for(final OSMWay splitWay : splitWays) {
                        if((splitWay.getFirstNode() == segment.getEndJunction().junctionNode || splitWay.getLastNode() == segment.getEndJunction().junctionNode) && splitWay.getNodes().contains(segment.originJunction.junctionNode)) {
                            segment.usedWay = splitWay;
                            break;
                        }
                    }
                }
            } catch (InvalidArgumentException e) {
                e.printStackTrace();
            }
        }
    }
    public String scoreSummary() {
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
            output.append("\n");*/
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
}
