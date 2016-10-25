package Conflation;

import OSM.*;

import java.util.List;

/**
 * Created by nick on 9/30/16.
 */
public class OSMWaySegments extends WaySegments {
    public final Region boundingBoxForStopMatching, boundingBoxForSegmentMatching;

    public OSMWaySegments(OSMWay way, double maxSegmentLength) {
        super(way, maxSegmentLength);

        //set up the various bounding boxes
        final double platformDistanceBuffer = -SphericalMercator.metersToCoordDelta(StopArea.waySearchAreaBoundingBoxSize, way.getCentroid().y), segmentSearchBuffer = -SphericalMercator.metersToCoordDelta(RouteConflator.wayMatchingOptions.segmentSearchBoxSize, way.getCentroid().y);
        boundingBoxForStopMatching = way.getBoundingBox().regionInset(platformDistanceBuffer, platformDistanceBuffer);
        boundingBoxForSegmentMatching = way.getBoundingBox().regionInset(segmentSearchBuffer, segmentSearchBuffer);
    }
    protected OSMWaySegments(final OSMWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);

        //set up the various bounding boxes
        final double platformDistanceBuffer = -SphericalMercator.metersToCoordDelta(StopArea.waySearchAreaBoundingBoxSize, way.getCentroid().y), segmentSearchBuffer = -SphericalMercator.metersToCoordDelta(RouteConflator.wayMatchingOptions.segmentSearchBoxSize, way.getCentroid().y);
        boundingBoxForStopMatching = way.getBoundingBox().regionInset(platformDistanceBuffer, platformDistanceBuffer);
        boundingBoxForSegmentMatching = way.getBoundingBox().regionInset(segmentSearchBuffer, segmentSearchBuffer);
    }
    @Override
    protected LineSegment createLineSegment(final Point miniOrigin, final Point miniDestination, final OSMNode miniOriginNode, final OSMNode miniDestinationNode, int segmentIndex, int nodeIndex) {
        return new OSMLineSegment(this, miniOrigin, miniDestination, miniOriginNode, miniDestinationNode, segmentIndex, nodeIndex);
    }
    @Override
    protected LineSegment copyLineSegment(final LineSegment segmentToCopy, final Point destination, final OSMNode destinationNode) {
        return new OSMLineSegment((OSMLineSegment) segmentToCopy, destination, destinationNode);
    }
}
