package Conflation;

import OSM.OSMEntity;
import OSM.Point;
import OSM.Region;

/**
 * Created by nick on 11/9/15.
 */
public class SegmentMatch {
    private final static double DOT_PRODUCT_FOR_PARALLEL_LINE = 0.999; //segments with a dot product > than this are considered "parallel" for some calculations
    public final static short matchTypeNone = 0, matchTypeBoundingBox = 1, matchTypeDistance = 2, matchTypeDotProduct = 4, matchTypeTravelDirection = 8;
    public final static short matchMaskAll = matchTypeBoundingBox | matchTypeDistance | matchTypeDotProduct | matchTypeTravelDirection;

    public final double orthogonalDistance, midPointDistance, dotProduct;
    public final RouteLineSegment mainSegment;
    public final OSMLineSegment matchingSegment;
    public final short type;

    public static int totalCount = 0;

    public SegmentMatch(final RouteLineSegment segment1, final OSMLineSegment segment2, final double orthDistance, final double midDistance, final double dotProduct, final RouteConflator.LineComparisonOptions options) {
        mainSegment = segment1;
        matchingSegment = segment2;
        orthogonalDistance = orthDistance;
        midPointDistance = midDistance;
        this.dotProduct = dotProduct;

        short matchType = matchTypeBoundingBox; //assume we've already matched the bounding box
        if(Math.abs(dotProduct) >= options.getMinSegmentDotProduct()) {
            matchType |= matchTypeDotProduct;
        }
        if(orthogonalDistance <= options.maxSegmentOrthogonalDistance && midPointDistance <= options.maxSegmentMidPointDistance) {
            matchType |= matchTypeDistance;
        }
        //check oneway directions
        final WaySegments.OneWayDirection oneWayDirection = matchingSegment.getParent().oneWayDirection;
        if(oneWayDirection == WaySegments.OneWayDirection.none || oneWayDirection == WaySegments.OneWayDirection.forward && dotProduct > 0.0 || oneWayDirection == WaySegments.OneWayDirection.backward && dotProduct < 0.0) {
            matchType |= matchTypeTravelDirection;
        }
        type = matchType;
        totalCount++;
    }
    public static SegmentMatch checkCandidateForMatch(final RouteConflator.LineComparisonOptions options, final RouteLineSegment routeLineSegment, final OSMLineSegment osmLineSegment) {
        //take the dot product
        final double dotProduct = (routeLineSegment.vectorX * osmLineSegment.vectorX + routeLineSegment.vectorY * osmLineSegment.vectorY) / (routeLineSegment.vectorMagnitude * osmLineSegment.vectorMagnitude);

        final boolean debugOutput = false;//osmLineSegment.parentSegments.way.osm_id == WaySegments.debugWayId && routeLineSegment.nodeIndex == 18 && osmLineSegment.nodeIndex == 14;

        //find the intersection of the orthogonal vector with the candidate segment
        final double xInt, yInt;
        if(routeLineSegment.vectorY == 0.0) { //i.e. routeLineSegment is purely east-west
            xInt = routeLineSegment.midPointX;
            if(Math.abs(dotProduct) >= DOT_PRODUCT_FOR_PARALLEL_LINE) { //case where both are parallel
                yInt = osmLineSegment.midPointY;
            } else {
                final double vecB, vecD;
                vecB = osmLineSegment.vectorY / osmLineSegment.vectorX;
                vecD = osmLineSegment.midPointY - vecB * osmLineSegment.midPointX;
                yInt = xInt * vecB + vecD;
            }
        } else if(routeLineSegment.vectorX == 0.0) { //i.e. routeLineSegment is purely north-south
            yInt = routeLineSegment.midPointY;
            if (Math.abs(dotProduct) >= DOT_PRODUCT_FOR_PARALLEL_LINE) { //case where both are parallel
                xInt = osmLineSegment.midPointX;
            } else {
                final double vecB, vecD;
                vecB = osmLineSegment.vectorY / osmLineSegment.vectorX;
                vecD = osmLineSegment.midPointY - vecB * osmLineSegment.midPointX;
                xInt = (yInt - vecD) / vecB;
            }
        } else if(osmLineSegment.vectorY == 0.0) { //osmLineSegment is east-west
            yInt = osmLineSegment.midPointY;
            if(Math.abs(dotProduct) < DOT_PRODUCT_FOR_PARALLEL_LINE) {
                final double vecA, vecC;
                vecA = routeLineSegment.orthogonalVectorY / routeLineSegment.orthogonalVectorX;
                vecC = routeLineSegment.midPointY - vecA * routeLineSegment.midPointX;
                xInt = (yInt - vecA) / vecC;
            } else {
                xInt = routeLineSegment.midPointX;
            }
        } else if(osmLineSegment.vectorX == 0.0) { //osmLineSegment is north-south
            xInt = osmLineSegment.midPointX;
            if(Math.abs(dotProduct) < DOT_PRODUCT_FOR_PARALLEL_LINE) {
                final double vecA, vecC;
                vecA = routeLineSegment.orthogonalVectorY / routeLineSegment.orthogonalVectorX;
                vecC = routeLineSegment.midPointY - vecA * routeLineSegment.midPointX;
                yInt = xInt * vecA + vecC;
            } else {
                yInt = osmLineSegment.midPointY;
            }
        } else {
            final double vecA, vecB, vecC, vecD;
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

        if(debugOutput) {
            System.out.println(String.format("RTE %03d/%04d: [%.08f,%.08f] :: point:(%.05f,%.05f)", routeLineSegment.nodeIndex, routeLineSegment.segmentIndex, routeLineSegment.vectorX, routeLineSegment.vectorY, routeLineSegment.midPointX, routeLineSegment.midPointY));
            System.out.println(String.format("OSM %03d/%04d: [%.08f,%.08f] :: point:(%.05f,%.05f)", osmLineSegment.nodeIndex, osmLineSegment.segmentIndex, osmLineSegment.vectorX, osmLineSegment.vectorY, osmLineSegment.midPointX, osmLineSegment.midPointY));
            System.out.println(String.format("DP MATCH for %s: %.05f, dist (%f,%f), oDist:%f, intersect(%f,%f)", osmLineSegment.getParent().way.getTag("name"), dotProduct, oDiffX, oDiffY, orthogonalDistance, yInt, xInt));
        }
        if(Region.intersects(routeLineSegment.searchAreaForMatchingOtherSegments, osmLineSegment.searchAreaForMatchingOtherSegments)) {

            //if the segments meet the threshold requirements, store the match in a SegmentMatch object
            //if(Math.abs(dotProduct) >= options.getMinSegmentDotProduct() && orthogonalDistance <= options.maxSegmentOrthogonalDistance && midPointDistance <= options.maxSegmentMidPointDistance) {
            //System.out.println("DP MATCH: " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            //System.out.println("DP MATCH: " + osmLineSegment.parentSegments.line.getTag("name") + ": " + dotProduct + ", dist:" + orthogonalDistance + ", intersect: (" + yInt + "," + xInt + ")");
            return new SegmentMatch(routeLineSegment, osmLineSegment, orthogonalDistance, midPointDistance, dotProduct, options);
        }
        return null;
    }
    @Override
    public String toString() {
        return String.format("SegMatch %s/%s::: O/M: %.03f/%.03f, DP %.03f", mainSegment, matchingSegment, orthogonalDistance, midPointDistance, dotProduct);
    }
}
