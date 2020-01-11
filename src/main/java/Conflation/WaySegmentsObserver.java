package Conflation;

import Importer.InvalidArgumentException;
import OSM.OSMNode;

/**
 * Created by nick on 2/7/16.
 */
public interface WaySegmentsObserver {
    void waySegmentsWasSplit(final WaySegments originalWaySegments, final OSMNode[] splitNodes, final WaySegments[] splitWaySegments) throws InvalidArgumentException;
    void waySegmentsWasDeleted(final WaySegments waySegments) throws InvalidArgumentException;
    void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment oldSegment, final LineSegment[] newSegments);
}
