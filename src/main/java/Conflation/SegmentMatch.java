package Conflation;

import NewPathFinding.PathSegment;
import OSM.OSMEntity;
import OSM.OSMWay;
import OSM.Point;
import OSM.Region;

import java.nio.charset.Charset;
import java.util.zip.CRC32;

/**
 * Created by nick on 11/9/15.
 */
public class SegmentMatch {
    private final static CRC32 idGenerator = new CRC32();
    private final static double DOT_PRODUCT_FOR_PARALLEL_LINE = 0.999; //segments with a dot product > than this are considered "parallel" for some calculations
    public final static short matchTypeNone = 0, matchTypeBoundingBox = 1, matchTypeDotProduct = 2, matchTypeDistance = 4, matchTypeTravelDirection = 8;
    public final static short matchMaskAll = matchTypeBoundingBox | matchTypeDistance | matchTypeDotProduct | matchTypeTravelDirection;

    //tag definitions used for checking for contraflow bus lanes
    private final static String[] integerTagsForward = {"lanes:bus:forward", "lanes:psv:forward"}, integerTagsBackward = {"lanes:bus:backward", "lanes:psv:backward"};
    private final static String[] stringTagsForward = {"bus:lanes:forward", "psv:lanes:forward"}, stringTagsBackward = {"bus:lanes:backward", "psv:lanes:backward"};

    public final long id;
    public final double orthogonalDistance, midPointDistance, dotProduct;
    public final RouteLineSegment mainSegment;
    public final OSMLineSegment matchingSegment;
    public final short type;
    public final PathSegment.TravelDirection travelDirection;

    public static int totalCount = 0;

    public static long idForParameters(final RouteLineSegment routeLineSegment, final OSMLineSegment osmLineSegment) {
        idGenerator.reset();
        idGenerator.update(String.format("SM:%d:%d", routeLineSegment.id, osmLineSegment.id).getBytes(Charset.forName("ascii")));
        return idGenerator.getValue();
    }
    public SegmentMatch(final RouteLineSegment routeLineSegment, final OSMLineSegment osmLineSegment, final double orthDistance, final double midDistance, final double dotProduct, final RouteConflator.LineComparisonOptions options) {
        id = idForParameters(routeLineSegment, osmLineSegment);
        mainSegment = routeLineSegment;
        matchingSegment = osmLineSegment;
        orthogonalDistance = orthDistance;
        midPointDistance = midDistance;
        this.dotProduct = dotProduct;
        travelDirection = dotProduct >= 0.0 ? PathSegment.TravelDirection.forward : PathSegment.TravelDirection.backward;

        short matchType = matchTypeBoundingBox; //assume we've already matched the bounding box
        if(Math.abs(dotProduct) >= options.getMinSegmentDotProduct()) {
            matchType |= matchTypeDotProduct;
        }
        if(orthogonalDistance <= options.maxSegmentOrthogonalDistance && midPointDistance <= options.maxSegmentMidPointDistance) {
            matchType |= matchTypeDistance;
        }
        final RouteConflator.RouteType routeType = RouteConflator.RouteType.bus; //TODO: get from the route line

        //check oneway directions
        final WaySegments.OneWayDirection oneWayDirection = matchingSegment.getParent().oneWayDirection;
        if(oneWayDirection == WaySegments.OneWayDirection.none || checkOneWayDirection(matchingSegment.getParent().way, routeType, oneWayDirection, dotProduct)) {
            matchType |= matchTypeTravelDirection;
        }
        type = matchType;
        totalCount++;
    }

    /**
     * Checks whether we can travel contraflow to the given way's oneway direction, using the way's tags
     * @param way The way to check
     * @param routeType The type of route (i.e. "bus")
     * @param oneWayDirection The direction we're attempting to travel on the way
     * @param dotProduct The dot product of the RouteLineSegment with the osmLineSegment on the way
     * @return true if travel is possible, false otherwise
     */
    private static boolean checkOneWayDirection(final OSMWay way, final RouteConflator.RouteType routeType, final WaySegments.OneWayDirection oneWayDirection, final double dotProduct) {
        final boolean travelingWithOneWayDirection = oneWayDirection == WaySegments.OneWayDirection.forward && dotProduct >= 0.0 || oneWayDirection == WaySegments.OneWayDirection.backward && dotProduct < 0.0;

        //if traveling against the oneway direction of the way, check if there are any exceptions based on the route's type
        if(!travelingWithOneWayDirection && routeType == RouteConflator.RouteType.bus) {
            //set up the tags to check for, based on the travel direction
            final String[] integerTags, stringTags;
            final String laneTag;
            if(oneWayDirection == WaySegments.OneWayDirection.forward) {
                integerTags = integerTagsBackward;
                stringTags = stringTagsBackward;
                laneTag = OSMEntity.TAG_OPPOSITE_LANE;
            } else {
                integerTags = integerTagsForward;
                stringTags = stringTagsForward;
                laneTag = OSMEntity.TAG_LANE;
            }

            //check the busway=* schema
            if (laneTag.equalsIgnoreCase(way.getTag(OSMEntity.KEY_BUSWAY))) {
                return true;
            }

            //check the lanes:bus:* schema
            for(final String tag : integerTags) {
                final String tagValue = way.getTag(tag);
                if(tagValue != null) {
                    try {
                        if(Integer.parseInt(tagValue) > 0) {
                            return true;
                        }
                    } catch(NumberFormatException ignored) {}
                }
            }

            //check the bus:lanes:* schema
            for(final String tag : stringTags) {
                final String tagValue = way.getTag(tag);
                if(tagValue != null && tagValue.contains(OSMEntity.TAG_YES)) {
                    return true;
                }
            }
        }
        return travelingWithOneWayDirection;
    }
    public static SegmentMatch checkCandidateForMatch(final RouteConflator.LineComparisonOptions options, final RouteLineSegment routeLineSegment, final OSMLineSegment osmLineSegment) {
        //run some basic validation checks
        if(routeLineSegment.vectorMagnitude <= Double.MIN_VALUE) {
            //System.out.println("ERROR: " + osmLineSegment + " has zero magnitude");
            return null;
        }
        if(osmLineSegment.vectorMagnitude < Double.MIN_VALUE) {
            //System.out.println("ERROR: " + osmLineSegment + " has zero magnitude");
            return null;
        }

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

        //calculate the orthogonal and midpoint distance
        final double orthogonalDistance = Point.distance(routeLineSegment.midPointX, routeLineSegment.midPointY, xInt, yInt), midPointDistance = Point.distance(osmLineSegment.midPointX, osmLineSegment.midPointY, routeLineSegment.midPointX, routeLineSegment.midPointY);

        if(debugOutput) {
            final double oDiffX = (xInt - routeLineSegment.midPointX), oDiffY = yInt - routeLineSegment.midPointY;
            System.out.println(String.format("RTE %03d/%04d: [%.01f,%.01f] :: point:(%.01f,%.01f)", routeLineSegment.nodeIndex, routeLineSegment.segmentIndex, routeLineSegment.vectorX, routeLineSegment.vectorY, routeLineSegment.midPointX, routeLineSegment.midPointY));
            System.out.println(String.format("OSM %03d/%04d: [%.01f,%.01f] :: point:(%.01f,%.01f)", osmLineSegment.nodeIndex, osmLineSegment.segmentIndex, osmLineSegment.vectorX, osmLineSegment.vectorY, osmLineSegment.midPointX, osmLineSegment.midPointY));
            System.out.println(String.format("DP MATCH for %s: %.05f, dist (%.01f,%.01f), oDist:%.02f, mDist: %.02f intersect(%.01f,%.01f)", osmLineSegment.getParent().way.getTag("name"), dotProduct, oDiffX, oDiffY, orthogonalDistance, midPointDistance, yInt, xInt));
        }
        if(Region.intersects(routeLineSegment.searchAreaForMatchingOtherSegments, osmLineSegment.boundingBox)) {

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
        return String.format("SegMatch %s/%s::: O/M: %.01f/%.03f, DP %.03f, matchType: %d", mainSegment, matchingSegment, orthogonalDistance, midPointDistance, dotProduct, type);
    }
    @Override
    public void finalize() throws Throwable {
        totalCount--;
      //  System.out.println("SMATCHDELETE " + this);
        super.finalize();
    }
}
