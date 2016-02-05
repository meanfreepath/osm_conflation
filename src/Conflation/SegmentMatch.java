package Conflation;

import OSM.Point;

/**
 * Created by nick on 11/9/15.
 */
public class SegmentMatch {
    public final double orthogonalDistance, midPointDistance, dotProduct;
    public final LineSegment mainSegment, matchingSegment;

    public SegmentMatch(final LineSegment segment1, final LineSegment segment2, final double orthDistance, final double midDistance, final double dotProduct) {
        mainSegment = segment1;
        matchingSegment = segment2;
        orthogonalDistance = orthDistance;
        midPointDistance = midDistance;
        this.dotProduct = dotProduct;
    }
    public static boolean checkCandidateForMatch(final RouteConflator.LineComparisonOptions options, final LineSegment routeLineSegment, final LineSegment osmLineSegment) {
        //take the dot product
        final double dotProduct = (routeLineSegment.vectorX * osmLineSegment.vectorX + routeLineSegment.vectorY * osmLineSegment.vectorY) / (routeLineSegment.vectorMagnitude * osmLineSegment.vectorMagnitude);

        //find the intersection of the orthogonal vector with the candidate segment
        final double vecA, vecB, vecC, vecD, xInt, yInt;
        if(routeLineSegment.vectorY == 0.0) { //i.e. routeLineSegment is purely east-west
            xInt = routeLineSegment.midPointX;
            if(osmLineSegment.vectorY == 0.0) { //case where both are parallel
                yInt = osmLineSegment.midPointY;
            } else {
                vecB = osmLineSegment.vectorY / osmLineSegment.vectorX;
                vecD = osmLineSegment.midPointY - vecB * osmLineSegment.midPointX;
                yInt = xInt * vecB + vecD;
            }
        } else if(routeLineSegment.vectorX == 0.0) { //i.e. routeLineSegment is purely north-south
            yInt = routeLineSegment.midPointY;
            if (osmLineSegment.vectorX == 0.0) { //case where both are parallel
                xInt = osmLineSegment.midPointX;
            } else {
                vecB = osmLineSegment.vectorY / osmLineSegment.vectorX;
                vecD = osmLineSegment.midPointY - vecB * osmLineSegment.midPointX;
                xInt = (yInt - vecD) / vecB;
            }
        } else if(osmLineSegment.vectorY == 0.0) { //osmLineSegment is east-west
            yInt = osmLineSegment.midPointY;
            if(routeLineSegment.vectorY != 0.0) {
                vecA = routeLineSegment.orthogonalVectorY / routeLineSegment.orthogonalVectorX;
                vecC = routeLineSegment.midPointY - vecA * routeLineSegment.midPointX;
                xInt = (yInt - vecA) / vecC;
            } else {
                xInt = routeLineSegment.midPointX;
            }
        } else if(osmLineSegment.vectorX == 0.0) { //osmLineSegment is north-south
            xInt = osmLineSegment.midPointX;
            if(routeLineSegment.vectorX != 0.0) {
                vecA = routeLineSegment.orthogonalVectorY / routeLineSegment.orthogonalVectorX;
                vecC = routeLineSegment.midPointY - vecA * routeLineSegment.midPointX;
                yInt = xInt * vecA + vecC;
            } else {
                yInt = osmLineSegment.midPointY;
            }
        } else {
            vecA = routeLineSegment.orthogonalVectorY / routeLineSegment.orthogonalVectorX;
            vecB = osmLineSegment.vectorY / osmLineSegment.vectorX;
            vecC = routeLineSegment.midPointY - vecA * routeLineSegment.midPointX;
            vecD = osmLineSegment.midPointY - vecB * osmLineSegment.midPointX;
            xInt = (vecD - vecC) / (vecA - vecB);
            yInt = xInt * vecA + vecC;
        }

        final double latitudeFactor = Math.cos(routeLineSegment.midPointY * Math.PI / 180.0);
        final double oDiffX = (xInt - routeLineSegment.midPointX) * latitudeFactor, oDiffY = yInt - routeLineSegment.midPointY, mDiffX = (osmLineSegment.midPointX - routeLineSegment.midPointX) * latitudeFactor, mDiffY = (osmLineSegment.midPointY - routeLineSegment.midPointY);

        final double orthogonalDistance = Point.distance(oDiffY, oDiffX);
        final double midPointDistance = Point.distance(mDiffY, mDiffX);

        /*if(osmLineSegment.parentSegments.line.osm_id == 263557332){
            //System.out.println("[" + vectorX + "," + vectorY + "]...A:" + vecA + ", B:" + vecB + ", C:" + vecC + ", D:" + vecD + "::: point:(" + midPointX + "," + midPointY + "/" + ((vecA * midPointX + vecC) + ")"));
            System.out.println("DP MATCH: " + osmLineSegment.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist: (" + oDiffX + "," + oDiffY + ") " + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
        }*/

        //if the segments meet the threshold requirements, store the match in a SegmentMatch object
        if(Math.abs(dotProduct) >= options.getMinSegmentDotProduct() && orthogonalDistance <= options.maxSegmentOrthogonalDistance && midPointDistance <= options.maxSegmentMidPointDistance) {
            //System.out.println("DP MATCH: " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            //System.out.println("DP MATCH: " + osmLineSegment.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            final SegmentMatch match = new SegmentMatch(routeLineSegment, osmLineSegment, orthogonalDistance, midPointDistance, dotProduct);
            routeLineSegment.matchingSegments.add(match);
            osmLineSegment.matchingSegments.add(match);


            routeLineSegment.parentSegments.addMatchForLine(osmLineSegment.parentSegments, match);
            osmLineSegment.parentSegments.addMatchForLine(routeLineSegment.parentSegments, match);
            return true;
        }
        return false;
    }
}
