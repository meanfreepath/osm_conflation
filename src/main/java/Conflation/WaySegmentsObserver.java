package Conflation;

import Importer.InvalidArgumentException;
import OSM.OSMNode;
import org.jetbrains.annotations.NotNull;

/**
 * Created by nick on 2/7/16.
 */
public interface WaySegmentsObserver {
    void waySegmentsWasSplit(@NotNull WaySegments originalWaySegments, @NotNull OSMNode[] splitNodes, @NotNull WaySegments[] splitWaySegments) throws InvalidArgumentException;
    void waySegmentsWasDeleted(@NotNull WaySegments waySegments) throws InvalidArgumentException;
    void waySegmentsAddedSegment(@NotNull WaySegments waySegments, @NotNull LineSegment oldSegment, @NotNull LineSegment[] newSegments);
}
