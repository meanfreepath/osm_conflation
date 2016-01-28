package Conflation;

import OSM.Point;

/**
 * Created by nick on 11/9/15.
 */
public class SegmentMatch {
    public final double orthogonalDistance, midPointDistance, dotProduct;
    public final LineSegment mainSegment, matchingSegment;
    public boolean consolidated = false;
    public int consolidatedMatches = 0;

    public SegmentMatch(LineSegment segment1, LineSegment segment2, double orthDistance, double midDistance, double dotProduct) {
        mainSegment = segment1;
        matchingSegment = segment2;
        orthogonalDistance = orthDistance;
        midPointDistance = midDistance;
        this.dotProduct = dotProduct;
    }
    public static boolean checkCandidateForMatch(final RouteConflator.LineComparisonOptions options, final LineSegment segment1, final LineSegment segment2, final LineMatch lineMatch) {
        //take the dot product
        final double dotProduct = (segment1.vectorX * segment2.vectorX + segment1.vectorY * segment2.vectorY) / (segment1.vectorMagnitude * segment2.vectorMagnitude);

        //find the intersection of the orthogonal vector with the candidate segment
        final double vecA, vecB, vecC, vecD, xInt, yInt;
        if(segment1.vectorY == 0.0) { //i.e. segment1 is purely east-west
            xInt = segment1.midPointX;
            if(segment2.vectorY == 0.0) { //case where both are parallel
                yInt = segment2.midPointY;
            } else {
                vecB = segment2.vectorY / segment2.vectorX;
                vecD = segment2.midPointY - vecB * segment2.midPointX;
                yInt = xInt * vecB + vecD;
            }
        } else if(segment1.vectorX == 0.0) { //i.e. segment1 is purely north-south
            yInt = segment1.midPointY;
            if (segment2.vectorX == 0.0) { //case where both are parallel
                xInt = segment2.midPointX;
            } else {
                vecB = segment2.vectorY / segment2.vectorX;
                vecD = segment2.midPointY - vecB * segment2.midPointX;
                xInt = (yInt - vecD) / vecB;
            }
        } else if(segment2.vectorY == 0.0) { //segment2 is east-west
            yInt = segment2.midPointY;
            if(segment1.vectorY != 0.0) {
                vecA = segment1.orthogonalVectorY / segment1.orthogonalVectorX;
                vecC = segment1.midPointY - vecA * segment1.midPointX;
                xInt = (yInt - vecA) / vecC;
            } else {
                xInt = segment1.midPointX;
            }
        } else if(segment2.vectorX == 0.0) { //segment2 is north-south
            xInt = segment2.midPointX;
            if(segment1.vectorX != 0.0) {
                vecA = segment1.orthogonalVectorY / segment1.orthogonalVectorX;
                vecC = segment1.midPointY - vecA * segment1.midPointX;
                yInt = xInt * vecA + vecC;
            } else {
                yInt = segment2.midPointY;
            }
        } else {
            vecA = segment1.orthogonalVectorY / segment1.orthogonalVectorX;
            vecB = segment2.vectorY / segment2.vectorX;
            vecC = segment1.midPointY - vecA * segment1.midPointX;
            vecD = segment2.midPointY - vecB * segment2.midPointX;
            xInt = (vecD - vecC) / (vecA - vecB);
            yInt = xInt * vecA + vecC;
        }

        final double latitudeFactor = Math.cos(segment1.midPointY * Math.PI / 180.0);
        final double oDiffX = (xInt - segment1.midPointX) * latitudeFactor, oDiffY = yInt - segment1.midPointY, mDiffX = (segment2.midPointX - segment1.midPointX) * latitudeFactor, mDiffY = (segment2.midPointY - segment1.midPointY);

        final double orthogonalDistance = Point.distance(oDiffY, oDiffX);
        final double midPointDistance = Point.distance(mDiffY, mDiffX);

        /*if(segment2.parentSegments.line.osm_id == 263557332){
            //System.out.println("[" + vectorX + "," + vectorY + "]...A:" + vecA + ", B:" + vecB + ", C:" + vecC + ", D:" + vecD + "::: point:(" + midPointX + "," + midPointY + "/" + ((vecA * midPointX + vecC) + ")"));
            System.out.println("DP MATCH: " + segment2.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist: (" + oDiffX + "," + oDiffY + ") " + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
        }*/

        //if the segments meet the threshold requirements, store the match in a SegmentMatch object
        if(Math.abs(dotProduct) >= options.getMinSegmentDotProduct() && orthogonalDistance <= options.maxSegmentOrthogonalDistance && midPointDistance <= options.maxSegmentMidPointDistance) {
            //System.out.println("DP MATCH: " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            //System.out.println("DP MATCH: " + segment2.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            final SegmentMatch match = new SegmentMatch(segment1, segment2, orthogonalDistance, midPointDistance, dotProduct);
            segment1.matchingSegments.add(match);
            segment2.matchingSegments.add(match);
            lineMatch.addMatch(match);
            return true;
        }
        return false;
    }
}
