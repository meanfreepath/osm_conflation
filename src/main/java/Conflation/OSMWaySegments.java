package Conflation;

import OSM.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Created by nick on 9/30/16.
 */
public class OSMWaySegments extends WaySegments {
    public @NotNull final Region boundingBoxForStopMatching, boundingBoxForSegmentMatching;

    protected OSMWaySegments(OSMWay way, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        super(way, wayMatchingOptions);

        //set up the various bounding boxes
        Region wayRegion = way.getBoundingBox();
        assert wayRegion != null;
        Point wayCentroid = wayRegion.getCentroid();
        final double platformDistanceBuffer = -SphericalMercator.metersToCoordDelta(StopArea.waySearchAreaBoundingBoxSize, wayCentroid.y), segmentSearchBuffer = -SphericalMercator.metersToCoordDelta(wayMatchingOptions.segmentSearchBoxSize, wayCentroid.y);
        boundingBoxForStopMatching = wayRegion.regionInset(platformDistanceBuffer, platformDistanceBuffer);
        boundingBoxForSegmentMatching = wayRegion.regionInset(segmentSearchBuffer, segmentSearchBuffer);
    }
    protected OSMWaySegments(final OSMWaySegments originalSegments, final OSMWay splitWay, final List<LineSegment> splitSegments) {
        super(originalSegments, splitWay, splitSegments);

        //set up the various bounding boxes
        Region wayRegion = way.getBoundingBox();
        assert wayRegion != null;
        Point wayCentroid = wayRegion.getCentroid();
        final double platformDistanceBuffer = -SphericalMercator.metersToCoordDelta(StopArea.waySearchAreaBoundingBoxSize, wayCentroid.y), segmentSearchBuffer = -SphericalMercator.metersToCoordDelta(wayMatchingOptions.segmentSearchBoxSize, wayCentroid.y);
        boundingBoxForStopMatching = wayRegion.regionInset(platformDistanceBuffer, platformDistanceBuffer);
        boundingBoxForSegmentMatching = wayRegion.regionInset(segmentSearchBuffer, segmentSearchBuffer);
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
