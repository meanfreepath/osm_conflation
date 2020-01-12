package Conflation;

import OSM.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Created by nick on 9/30/16.
 */
public class OSMWaySegments extends WaySegments {
    public @NotNull final Region boundingBoxForStopMatching, boundingBoxForSegmentMatching;

    protected OSMWaySegments(@NotNull OSMWay way, @NotNull RouteConflator.LineComparisonOptions wayMatchingOptions) {
        super(way, wayMatchingOptions);

        //set up the various bounding boxes
        Region wayRegion = way.getBoundingBox();
        assert wayRegion != null;
        Point wayCentroid = wayRegion.getCentroid();
        final double platformDistanceBuffer = -SphericalMercator.metersToCoordDelta(StopArea.waySearchAreaBoundingBoxSize, wayCentroid.y), segmentSearchBuffer = -SphericalMercator.metersToCoordDelta(wayMatchingOptions.segmentSearchBoxSize, wayCentroid.y);
        boundingBoxForStopMatching = wayRegion.regionInset(platformDistanceBuffer, platformDistanceBuffer);
        boundingBoxForSegmentMatching = wayRegion.regionInset(segmentSearchBuffer, segmentSearchBuffer);
    }
    protected OSMWaySegments(@NotNull OSMWaySegments originalSegments, @NotNull OSMWay splitWay, @NotNull List<LineSegment> splitSegments) {
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
    protected LineSegment createLineSegment(@NotNull Point miniOrigin, @NotNull Point miniDestination, @Nullable OSMNode miniOriginNode, @Nullable OSMNode miniDestinationNode, int segmentIndex, int nodeIndex) {
        return new OSMLineSegment(this, miniOrigin, miniDestination, miniOriginNode, miniDestinationNode, segmentIndex, nodeIndex);
    }
    @Override
    protected LineSegment copyLineSegment(@NotNull LineSegment segmentToCopy, @NotNull Point destination, @Nullable OSMNode destinationNode) {
        return new OSMLineSegment((OSMLineSegment) segmentToCopy, destination, destinationNode);
    }
}
