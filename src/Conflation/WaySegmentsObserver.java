package Conflation;

import com.sun.javaws.exceptions.InvalidArgumentException;

/**
 * Created by nick on 2/7/16.
 */
public interface WaySegmentsObserver {
    void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException;
    void waySegmentsWasDeleted(final WaySegments waySegments) throws InvalidArgumentException;
    void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment newSegment);
}
