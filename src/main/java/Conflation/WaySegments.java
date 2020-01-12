package Conflation;

import Importer.InvalidArgumentException;
import OSM.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

/**
 * Container for an OSM way and its calculated line segments
 * Created by nick on 11/9/15.
 */
public abstract class WaySegments {
    public enum OneWayDirection {
        none, forward, backward
    }
    private final static Comparator<SegmentMatch> SEGMENT_MATCH_COMPARATOR = new Comparator<SegmentMatch>() {
        @Override
        public int compare(SegmentMatch o1, SegmentMatch o2) {
            return o1.matchingSegment.segmentIndex < o2.matchingSegment.segmentIndex ? -1 : 1;
        }
    };
    public final static long debugWayId = 0L;//244322295L;//157766372L

    @NotNull
    public final OSMWay way;
    @NotNull
    public final ArrayList<LineSegment> segments;
    @NotNull
    public final OneWayDirection oneWayDirection;
    @Nullable
    protected List<WeakReference<WaySegmentsObserver>> observers = null;
    @NotNull
    protected final RouteConflator.LineComparisonOptions wayMatchingOptions;

    /**
     * Create a new object, split into segments with the given maximum length
     * @param way the way to use
     * @param wayMatchingOptions the options used for matching ways
     */
    public WaySegments(@NotNull OSMWay way, @NotNull RouteConflator.LineComparisonOptions wayMatchingOptions) {
        assert way.getNodes().size() >= 2; // Ways must have 2 or more nodes to be valid

        this.way = way;
        this.wayMatchingOptions = wayMatchingOptions;
        final double maxSegmentLength = wayMatchingOptions.maxSegmentLength;
        oneWayDirection = determineOneWayDirection(way);

        //generate a list of line segments out of this line
        segments = new ArrayList<>((int) Math.ceil(way.length() / maxSegmentLength));
        OSMNode originNode = way.getFirstNode();
        int nodeIndex = 0, segmentIndex = 0;
        for(final OSMNode destinationNode: way.getNodes()) {
            if (destinationNode == originNode) { //skip the first iteration
                continue;
            }

            //first get the distance between the 2 nodes
            final Point destinationPoint = destinationNode.getCentroid(), originPoint = originNode.getCentroid();
            final double vectorX = destinationPoint.x - originPoint.x, vectorY = destinationPoint.y - originPoint.y;
            final double segmentLength = Point.distance(originNode.getCentroid(),destinationNode.getCentroid());

            //if less than the length threshold, add as a segment
            if (segmentLength < maxSegmentLength) {
                final LineSegment segment = createLineSegment(originNode.getCentroid(), destinationNode.getCentroid(), originNode, destinationNode, segmentIndex++, nodeIndex);
                segments.add(segment);
            } else { //otherwise, split into a number of segments, each equal to or shorter than the maximum segment length
                final int segmentsToAdd = (int) Math.ceil(segmentLength / maxSegmentLength);
                OSMNode miniOriginNode = originNode;
                Point miniOrigin = originNode.getCentroid(), miniDestination;
                double destinationLat, destinationLon;

                //add the first segment (with the origin node) and subsequent segments (which have no existing nodes)
                for (int seg = 0; seg < segmentsToAdd - 1; seg++) {
                    destinationLon = miniOrigin.x + vectorX / segmentsToAdd;
                    destinationLat = miniOrigin.y + vectorY / segmentsToAdd;
                    miniDestination = new Point(destinationLon, destinationLat);

                    segments.add(createLineSegment(miniOrigin, miniDestination, miniOriginNode, null, segmentIndex++, nodeIndex));

                    miniOrigin = miniDestination;
                    miniOriginNode = null;
                }

                //add the last segment, with its last node as the original destination node
                miniDestination = destinationNode.getCentroid();
                segments.add(createLineSegment(miniOrigin, miniDestination, miniOriginNode, destinationNode, segmentIndex++, nodeIndex));
            }
            //System.out.println("END MAINSEGMENT #" + mainSegmentIndex + ": " + originNode.osm_id + "-" + destinationNode.osm_id);
            originNode = destinationNode;
            nodeIndex++;
        }
    }
    protected WaySegments(@NotNull WaySegments originalSegments, @NotNull final OSMWay splitWay, @NotNull List<LineSegment> splitSegments) {
        this.way = splitWay;
        this.oneWayDirection = originalSegments.oneWayDirection;
        this.segments = new ArrayList<>(splitSegments);
        this.wayMatchingOptions = originalSegments.wayMatchingOptions;

        //update the indexes of the segments to work with their new parent
        recalculateSegmentIndexes(segments, this);

        //also copy any observers
        if(originalSegments.observers != null) {
            observers = new ArrayList<>(originalSegments.observers);
        }
    }
    protected abstract LineSegment createLineSegment(final Point miniOrigin, final Point miniDestination, final OSMNode miniOriginNode, final OSMNode miniDestinationNode, int segmentIndex, int nodeIndex);
    protected abstract LineSegment copyLineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode);
    /**
     * Inserts a node on the given segment, splitting it into two segments
     * NOTE: does not check if node lies on onSegment!
     * @param node The node to add
     * @param onSegment The segment to add the node to
     * @return If an existing node is within the tolerance distance, that node, otherwise the input node
     */
    public OSMNode insertNode(final OSMNode node, LineSegment onSegment) {
        final Point nodePoint = node.getCentroid();

        //create a new segment starting from the node, and ending at onSegment's destination Point
        final LineSegment insertedSegment = createLineSegment(nodePoint, onSegment.destinationPoint, node, onSegment.destinationNode, onSegment.segmentIndex + 1, onSegment.nodeIndex + 1);

        //and replace onSegment with a version of itself that's truncated to the new node's position
        final LineSegment newOnSegment = copyLineSegment(onSegment, nodePoint, node);
        segments.set(segments.indexOf(onSegment), newOnSegment);

        //increment the node index of all following segments
        for(final LineSegment segment : segments) {
            if(segment.segmentIndex > onSegment.segmentIndex) {
                segment.segmentIndex++;
                segment.nodeIndex++;
            }
        }

        //add the segment and node to this line and its way
        segments.add(insertedSegment.segmentIndex, insertedSegment);
        way.insertNode(node, insertedSegment.nodeIndex);

        //and notify any observers
        if(observers != null) {
            final LineSegment[] newSegments = {newOnSegment, insertedSegment};
            final List<WeakReference<WaySegmentsObserver>> observersToNotify = new ArrayList<>(observers);
            for (final WeakReference<WaySegmentsObserver> observerReference : observersToNotify) {
                final WaySegmentsObserver observer = observerReference.get();
                if(observer != null) {
                    observer.waySegmentsAddedSegment(this, onSegment, newSegments);
                }
            }
        }

        return node;
    }
    /**
     * Maps the "oneway" tag of the way to the OneWayDirection enum
     * @param way
     * @return
     */
    @NotNull
    public static OneWayDirection determineOneWayDirection(@NotNull OSMWay way) {
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
     * Appends a node to the current segments list
     * TODO: split long segments into smaller ones
     * @param node
     * @throws InvalidArgumentException
     */
    public void appendNode(@NotNull OSMNode node) throws InvalidArgumentException {
        if(segments.size() == 0) {
            throw new InvalidArgumentException("No existing line segments!");
        }
        final LineSegment lastSegment = segments.get(segments.size() - 1);

        //the new segment starts from the last segment's destinationPoint
        final LineSegment newSegment = createLineSegment(lastSegment.destinationPoint, node.getCentroid(), lastSegment.destinationNode, node, lastSegment.segmentIndex + 1, lastSegment.nodeIndex + 1);
        segments.add(newSegment);
        way.appendNode(node);
    }
    public boolean addObserver(@NotNull WaySegmentsObserver observer) {
        if(observers == null) {
            observers = new ArrayList<>(32);
        }

        for(final WeakReference<WaySegmentsObserver> observerReference : observers) {
            if(observerReference.get() == observer) {
                return false;
            }
        }
        return observers.add(new WeakReference<>(observer));
    }
    public boolean removeObserver(@NotNull WaySegmentsObserver observer) {
        if(observers != null) {
            for(final WeakReference<WaySegmentsObserver> observerReference : observers) {
                if(observerReference.get() == observer) {
                    return observers.remove(observerReference);
                }
            }
        }
        return false;
    }
    @NotNull
    public static String outputSegments(@NotNull List<LineSegment> segments) {
        final List<String> segs = new ArrayList<>(segments.size());
        for(final LineSegment segment : segments) {
            segs.add(segment.toString());
        }
        return String.join("\n\t", segs);
    }
    public String outputSegments() {
        return outputSegments(segments);
    }

    /**
     * Returns the segment which is closest to the given point
     * @param point the point to check
     * @param maxSearchDistance - the maximum distance to search, in meters
     * @return the closest LineSegment
     */
    @Nullable
    public final LineSegment closestSegmentToPoint(@NotNull Point point, final double maxSearchDistance) {
        double minDistance = maxSearchDistance, curDistance;
        LineSegment closestSegment = null;
        Point closestPointOnSegment;
        for(final LineSegment segment : segments) {
            closestPointOnSegment = segment.closestPointToPoint(point);
            curDistance = Point.distance(point, closestPointOnSegment);
            if(curDistance < minDistance) {
                minDistance = curDistance;
                closestSegment = segment;
            }
        }
        return closestSegment;
    }
    @NotNull
    public WaySegments[] split(@NotNull OSMNode[] splitNodes, @NotNull OSMEntitySpace entitySpace) throws InvalidArgumentException {
        //run the split on the underlying way
        final OSMWay[] splitWays = entitySpace.splitWay(way, splitNodes);

        //System.out.println(splitWays.length + " split ways:");
        if(splitWays.length == 1 && splitWays[0] == way) { //i.e. the split wasn't run, just return an array containing this WaySegments
            return new WaySegments[]{this};
        }

        //create a WaySegments object for each way
        final WaySegments[] splitWaySegments = new WaySegments[splitWays.length];
        int idx = 0;
        final List<LineSegment> originalLineSegments = new ArrayList<>(segments);
        for(final OSMWay splitWay : splitWays) {
            //System.out.println("check way " + splitWay.debugOutput());
            final List<LineSegment> curLineSegments = new ArrayList<>(originalLineSegments.size());

            boolean inWay = false;
            ListIterator<LineSegment> segmentListIterator = originalLineSegments.listIterator();
            for(;;) {
                /*
                  reset the list iterator if we've reached the end of the original segment list without finding the end of the splitWay
                  (i.e. when the first/last nodes change when splitting a closed way)
                 */
                if(!segmentListIterator.hasNext()) {
                    segmentListIterator = originalLineSegments.listIterator();
                }

                final LineSegment segment = segmentListIterator.next();
                if(inWay) {
                    curLineSegments.add(segment);
                    segmentListIterator.remove();
                } else if(segment.originNode == splitWay.getFirstNode()) {
                    inWay = true;
                    curLineSegments.add(segment);
                    segmentListIterator.remove();
                }

                //bail once we've reached the end of the splitWay
                if(inWay && segment.destinationNode == splitWay.getLastNode()) {
                    break;
                }
            }
            //System.out.println(outputSegments(curLineSegments));

            final WaySegments ws;
            if(splitWay != way) {  //for the new ways, create a new WaySegments, copying the appropriate properties over
                if(this instanceof OSMWaySegments) {
                    ws = new OSMWaySegments((OSMWaySegments) this, splitWay, curLineSegments);
                } else if(this instanceof RouteLineWaySegments) {
                    ws = new RouteLineWaySegments((RouteLineWaySegments) this, splitWay, curLineSegments);
                } else {
                    ws = null;
                }
            } else { //otherwise, just reassign the line segments
                ws = this;

                segments.clear();
                segments.addAll(curLineSegments);

                //and recalculate the indexes for the LineSegments to reflect their new positioning
                recalculateSegmentIndexes(segments, null);
            }
            splitWaySegments[idx++] = ws;

//            System.out.println("\t" + ws.way.osm_id + ": from " + ws.way.getFirstNode().osm_id + " to " + ws.way.getLastNode().osm_id + ": " + ws.segments.size() + " segments, " + ws.way.getNodes().size() + " nodes");
        }


        //notify the observers of the split
        if(observers != null) {
            final List<WeakReference<WaySegmentsObserver>> observersToNotify = new ArrayList<>(observers);
            for (final WeakReference<WaySegmentsObserver> observerReference : observersToNotify) {
                final WaySegmentsObserver observer = observerReference.get();
                if(observer != null) {
                    observer.waySegmentsWasSplit(this, splitNodes, splitWaySegments);
                }
            }
        }

        return splitWaySegments;
    }
    /**
     * Recalculates the node and segment indexes for the given LineSegment objects
     * @param segments the list of LineSegment objects to calculate the indexes for
     * @param parent the new parent WaySegments to assign the the segments (set to NULL to avoid assignment)
     */
    private static void recalculateSegmentIndexes(@NotNull List<LineSegment> segments, @Nullable WaySegments parent) {
        int newSegmentIndex = 0, newNodeIndex = 0;
        for(final LineSegment segment : segments) {
            if(parent != null) {
                segment.setParent(parent);
            }
            segment.segmentIndex = newSegmentIndex++;
            segment.nodeIndex = newNodeIndex;
            if (segment.destinationNode != null) {
                ++newNodeIndex;
            }
        }
    }
    public String toString() {
        return String.format("WaySegments @%d: way #%d (%s): [%d->%d], %d segments", hashCode(), way.osm_id, way.getTag(OSMEntity.KEY_NAME), way.getFirstNode() != null ? way.getFirstNode().osm_id : 0, way.getLastNode() != null ? way.getLastNode().osm_id : 0, segments.size());
    }
}
