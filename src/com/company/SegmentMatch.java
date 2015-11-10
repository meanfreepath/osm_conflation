package com.company;

/**
 * Created by nick on 11/9/15.
 */
public class SegmentMatch {
    final double orthogonalDistance, midPointDistance, dotProduct;
    final LineSegment mainSegment, matchingSegment;
    public SegmentMatch(LineSegment segment1, LineSegment segment2, double orthDistance, double midDistance, double dotProduct) {
        mainSegment = segment1;
        matchingSegment = segment2;
        orthogonalDistance = orthDistance;
        midPointDistance = midDistance;
        this.dotProduct = dotProduct;
    }
}
