package Conflation;

import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public final OSMWay way;
    public final ArrayList<LineSegment> segments;
    public final OneWayDirection oneWayDirection;
    protected List<WeakReference<WaySegmentsObserver>> observers = null;
    public final WeakReference<RouteConflator> parentRouteConflator;

    /**
     * Create a new object, split into segments with the given maximum length
     * @param way the way to use
     * @param routeConflator the parent RouteConflator of this line
     */
    public WaySegments(final OSMWay way, final RouteConflator routeConflator) {
        this.way = way;
        parentRouteConflator = new WeakReference<>(routeConflator);
        final double maxSegmentLength = routeConflator.wayMatchingOptions.maxSegmentLength;
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
    protected WaySegments(final WaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        this.way = splitWay;
        this.oneWayDirection = originalSegments.oneWayDirection;
        this.segments = new ArrayList<>(splitSegments);
        this.parentRouteConflator = originalSegments.parentRouteConflator;

        //copy the appropriate segments from the originalSegments
        int newSegmentIndex = 0, newNodeIndex = 0;
        for(final LineSegment segment : segments) {
            segment.setParent(this);
            segment.segmentIndex = newSegmentIndex++;
            segment.nodeIndex = newNodeIndex;
            if (segment.originNode != null) {
                ++newNodeIndex;
            }
        }

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
    public static OneWayDirection determineOneWayDirection(final OSMWay way) {
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
    public void appendNode(final OSMNode node) throws InvalidArgumentException {
        if(segments.size() == 0) {
            String[] errMsg = {"No existing line segments!"};
            throw new InvalidArgumentException(errMsg);
        }
        final LineSegment lastSegment = segments.get(segments.size() - 1);

        //the new segment starts from the last segment's destinationPoint
        final LineSegment newSegment = createLineSegment(lastSegment.destinationPoint, node.getCentroid(), lastSegment.destinationNode, node, lastSegment.segmentIndex + 1, lastSegment.nodeIndex + 1);
        segments.add(newSegment);
        way.appendNode(node);
    }
    public boolean addObserver(final WaySegmentsObserver observer) {
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
    public boolean removeObserver(final WaySegmentsObserver observer) {
        if(observers != null) {
            for(final WeakReference<WaySegmentsObserver> observerReference : observers) {
                if(observerReference.get() == observer) {
                    return observers.remove(observerReference);
                }
            }
        }
        return false;
    }
    public static String outputSegments(final List<LineSegment> segments) {
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
     * @param point
     * @param maxSearchDistance - the maximum distance to search, in meters
     * @return the closest LineSegment
     */
    public final LineSegment closestSegmentToPoint(final Point point, final double maxSearchDistance) {
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
    public WaySegments[] split(final OSMNode[] splitNodes, final OSMEntitySpace entitySpace) throws InvalidArgumentException {
        final List<OSMNode> actualSplitNodes = new ArrayList<>(splitNodes.length);
        for(final OSMNode node : splitNodes) {
            if(node == way.getFirstNode() || node == way.getLastNode()) {
                continue;
            }
            actualSplitNodes.add(node);
        }

        //System.out.println("Run split on " + way.getTag("name") + "? " + actualSplitNodes.size());

        if(actualSplitNodes.size() == 0) {
            return new WaySegments[]{this};
        }

        /*final List<Point> preSplitPoints = new ArrayList<>(segments.size() + 1);
        if(segments.size() > 0) {
            preSplitPoints.add(segments.get(0).originPoint);
            for (final LineSegment segment : segments) {
                preSplitPoints.add(segment.destinationPoint);
            }
        }

        /*final List<String> nodeIds = new ArrayList<>(actualSplitNodes.size());
        for(final OSMNode node : actualSplitNodes) {
            nodeIds.add(Long.toString(node.osm_id));
        }
        System.out.println("Line " + way.getTag("name") + "(" + way.debugOutput() + "), " + segments.size() + " segments, split at " + String.join(",", nodeIds) + ": ");//*/

        //run the split on the underlying way
        final OSMWay[] splitWays = entitySpace.splitWay(way, actualSplitNodes.toArray(new OSMNode[actualSplitNodes.size()]));
        //System.out.println(splitWays.length + " split ways:");

        //create a WaySegments object for each way
        final WaySegments[] splitWaySegments = new WaySegments[splitWays.length];
        int idx = 0;
        final List<LineSegment> originalLineSegments = new ArrayList<>(segments);
        for(final OSMWay splitWay : splitWays) {
            //System.out.println("check way " + splitWay.debugOutput());
            List<LineSegment> curLineSegments = new ArrayList<>(originalLineSegments.size());

            boolean inWay = false;
            for(final LineSegment segment : originalLineSegments) {
                if(inWay) {
                    curLineSegments.add(segment);
                } else if(segment.originNode == splitWay.getFirstNode()) {
                    inWay = true;
                    curLineSegments.add(segment);
                }

                if(segment.destinationNode == splitWay.getLastNode()) {
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

                int newSegmentIndex = 0, newNodeIndex = 0;
                for(final LineSegment segment : curLineSegments) {
                    segment.segmentIndex = newSegmentIndex++;
                    segment.nodeIndex = newNodeIndex;
                    if (segment.originNode != null) {
                        ++newNodeIndex;
                    }
                }

                //resyncLineMatches(); //TODO:222 how to handle LineMatches (are they needed?) with new matching system
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
    public String toString() {
        return "WaySegments " + way.getTag(OSMEntity.KEY_NAME) + "(" + way.osm_id + ":" + way.getFirstNode().osm_id + "->" + way.getLastNode().osm_id + "): " + segments.size() + " segments";
    }
}
