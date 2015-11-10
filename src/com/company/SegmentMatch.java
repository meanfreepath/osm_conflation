package com.company;

/**
 * Created by nick on 11/9/15.
 */
public class SegmentMatch {
    final double orthogonalDistance, dotProduct;
    final LineSegment mainSegment, matchingSegment;
    public SegmentMatch(LineSegment segment1, LineSegment segment2, double distance, double dotProduct) {
        mainSegment = segment1;
        matchingSegment = segment2;
        orthogonalDistance = distance;
        this.dotProduct = dotProduct;
    }
}
