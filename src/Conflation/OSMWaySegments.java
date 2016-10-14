package Conflation;

import OSM.OSMNode;
import OSM.OSMWay;
import OSM.Point;
import OSM.Region;

import java.util.List;

/**
 * Created by nick on 9/30/16.
 */
public class OSMWaySegments extends WaySegments {
    public final Region boundingBoxForStopMatching, boundingBoxForSegmentMatching;

    public OSMWaySegments(OSMWay way, double maxSegmentLength) {
        super(way, maxSegmentLength);

        //set up the various bounding boxes
        final double lonFactor = 1.0 /  Math.cos(Math.PI * way.getCentroid().latitude / 180.0);
        final double stopMatchingLatitudeDelta = -StopArea.maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, stopMatchingLongitudeDelta = stopMatchingLatitudeDelta / lonFactor;
        boundingBoxForStopMatching = way.getBoundingBox().regionInset(stopMatchingLatitudeDelta, stopMatchingLongitudeDelta);
        final double wayMatchingLatitudeDelta = -RouteConflator.wayMatchingOptions.segmentSearchBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, wayMatchingLongitudeDelta = wayMatchingLatitudeDelta / lonFactor;
        boundingBoxForSegmentMatching = way.getBoundingBox().regionInset(wayMatchingLatitudeDelta, wayMatchingLongitudeDelta);
    }
    protected OSMWaySegments(final OSMWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);

        //set up the various bounding boxes
        final double lonFactor = 1.0 /  Math.cos(Math.PI * way.getCentroid().latitude / 180.0);
        final double stopMatchingLatitudeDelta = -StopArea.maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, stopMatchingLongitudeDelta = stopMatchingLatitudeDelta / lonFactor;
        boundingBoxForStopMatching = way.getBoundingBox().regionInset(stopMatchingLatitudeDelta, stopMatchingLongitudeDelta);
        final double wayMatchingLatitudeDelta = -RouteConflator.wayMatchingOptions.segmentSearchBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, wayMatchingLongitudeDelta = wayMatchingLatitudeDelta / lonFactor;
        boundingBoxForSegmentMatching = way.getBoundingBox().regionInset(wayMatchingLatitudeDelta, wayMatchingLongitudeDelta);
    }
    @Override
    protected LineSegment createLineSegment(final Point miniOrigin, final Point miniDestination, final OSMNode miniOriginNode, final OSMNode miniDestinationNode, int segmentIndex, int nodeIndex) {
        return new OSMLineSegment(this, miniOrigin, miniDestination, miniOriginNode, miniDestinationNode, segmentIndex, nodeIndex);
    }
    @Override
    protected LineSegment copyLineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        return new OSMLineSegment(segmentToCopy, destination, destinationNode);
    }
}
