package Conflation;

import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Manages the association of stops/waypoints with their respective OSM ways
 * Created by nick on 1/27/16.
 */
public class StopConflator {
    private final static String stopNameStreetSeparator = " & ";
    public static boolean debugEnabled = false;

    public final RouteConflator routeConflator;

    public StopConflator(final RouteConflator routeConflator) {
        this.routeConflator = routeConflator;
    }
    public void matchStopsToWays() {
        final int stopCount = routeConflator.getAllRouteStops().size();
        if(stopCount == 0) { //TODO add warning
            return;
        }
        final StreetNameMatcher matcher = new StreetNameMatcher(Locale.US);
        final double latitudeDelta = -StopWayMatch.maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * routeConflator.roughCentroid.latitude / 180.0);

        //loop through the route's stops, determining the best-matching way for each one
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            //get the stop's name components, to see if we can use the name to match to a nearby way
            final String stopName = stop.platform.getTag(OSMEntity.KEY_NAME);
            String[] stopStreetNames = null;
            if(stopName != null) {
                stopStreetNames = stopName.split(stopNameStreetSeparator);
                for (short s = 0; s < stopStreetNames.length; s++) {
                    String exName = matcher.expandedStreetName(stopStreetNames[s]);
                    //System.out.println(stopStreetNames[s] + ": " + exName);
                    stopStreetNames[s] = exName;
                }
            }

            //find the nearest OSM ways to the stop platforms, checking all lines whose bounding boxes intersect the stop's search area
            final Region searchRegion = stop.platform.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            final Point platformCentroid = stop.platform.getCentroid();
            //for(final WaySegments matchingLine : lineComparison.candidateLines.values()) {
            for(final WaySegments line : routeConflator.getCandidateLines().values()) {
                //check the line's bounding box intersects
                if(!Region.intersects(line.way.getBoundingBox().regionInset(latitudeDelta, longitudeDelta), searchRegion)) {
                    continue;
                }

                //find the nearest segment to the platform on the way
                LineSegment nearestSegment = null;
                double minDistance = StopWayMatch.maxDistanceFromPlatformToWay;
                Point closestPoint;
                for(final LineSegment segment : line.segments) {
                    closestPoint = segment.closestPointToPoint(platformCentroid);
                    final double segmentDistance = Point.distance(closestPoint, platformCentroid);
                    if(segmentDistance < minDistance) {
                        nearestSegment = segment;
                        minDistance = segmentDistance;
                    }
                }
                /*SegmentMatch nearestSegmentMatch = null; TODO move to final stop matching
                double minDistance = StopWayMatch.maxDistanceFromPlatformToWay;
                Point closestPoint;
                for(final SegmentMatch segmentMatch : matchingLine.matchObject.matchingSegments) {
                    //require the matching segment to be a reasonable match to the route's travel path
                    if(Math.abs(segmentMatch.dotProduct) < StopWayMatch.minMatchingSegmentPathDotProduct) {
                        continue;
                    }

                    //System.out.println("Segment " + segmentMatch.matchingSegment.getDebugName() + ": " + segmentMatch.consolidatedMatches);
                    closestPoint = segmentMatch.matchingSegment.closestPointToPoint(platformCentroid);
                    final double segmentDistance = Point.distance(closestPoint, platformCentroid);
                    if(segmentDistance < minDistance) {
                        nearestSegmentMatch = segmentMatch;
                        minDistance = segmentDistance;
                    }
                }*/

                //skip this line if not within the maximum distance to the stop
                if(nearestSegment == null) {
                    //System.out.println("NO MATCH for platform " + stopPlatform.osm_id + "(ref " + stopPlatform.getTag("ref") + "/" + stopPlatform.getTag("name") + ")");
                    continue;
                }

                //also try matching based on the stop name and way names, preferring the stop's first name component
                boolean matchedByName = false;
                if(stopName != null) {
                    final String wayName = line.way.getTag(OSMEntity.KEY_NAME);
                    if (wayName != null) { //if the name matches use this way
                        if (stopStreetNames[0].equals(wayName)) {
                            stop.wayMatches.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.primaryStreet);
                            matchedByName = true;
                        } else if (stopStreetNames.length > 1 && stopStreetNames[1].equals(wayName)) {
                            stop.wayMatches.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.crossStreet);
                            matchedByName = true;
                        }
                    }
                }

                //if matching by name fails, try going by nearest way
                if(!matchedByName) {
                    if(minDistance < StopWayMatch.maxDistanceFromPlatformToWay) {
                        stop.wayMatches.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.proximity);
                    } else {
                        stop.wayMatches.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.none);
                    }
                }
            }
        }

        //now pick the best matches for each platform
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            if(stop.wayMatches.matches.size() == 0) {
                System.out.println("No matches found for stop #" + stop.platform.osm_id + " (" + stop.platform.getTag("name") + ")");
                continue;
            }
            //determine the best-matching way for the stop
            stop.wayMatches.chooseBestMatch();
        }
    }
    public void createStopPositionsForPlatforms(final OSMEntitySpace entitySpace) {
        //now pick the best matches for each platform
        for(final StopArea stopArea : routeConflator.getAllRouteStops()) {
            final StopWayMatch match = stopArea.wayMatches;
            if(match.bestMatch == null) {
                continue;
            }

            //Create (or update an existing node) to serve as the stop position node for the platform
            final LineSegment bestSegment = match.bestMatch.candidateSegmentMatch;
            final Point nearestPointOnSegment = bestSegment.closestPointToPoint(match.stopEntity.platform.getCentroid());

            OSMNode nearestNodeOnWay = bestSegment.parentSegments.way.nearestNodeAtPoint(nearestPointOnSegment, StopWayMatch.stopNodeTolerance);
            if(nearestNodeOnWay == null) {
                nearestNodeOnWay = bestSegment.parentSegments.insertNode(entitySpace.createNode(nearestPointOnSegment.latitude, nearestPointOnSegment.longitude, null), bestSegment);
            }

            //then add a node on the nearest point and add to the relation and the way
            nearestNodeOnWay.setTag(OSMEntity.KEY_NAME, match.stopEntity.platform.getTag(OSMEntity.KEY_NAME));
            nearestNodeOnWay.setTag(OSMEntity.KEY_REF, match.stopEntity.platform.getTag(OSMEntity.KEY_REF));
            nearestNodeOnWay.setTag("gtfs:stop_id", match.stopEntity.platform.getTag("gtfs:stop_id"));
            nearestNodeOnWay.setTag(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_STOP_POSITION);
            nearestNodeOnWay.setTag(routeConflator.routeType, OSMEntity.TAG_YES); //TODO need proper key mapping (e.g. for subway, light_rail, etc)

            //and add the stop position to the stop area
            match.stopEntity.setStopPosition(nearestNodeOnWay);

            //warn if no decent match is found
            if(match.bestMatch == null) {
                System.out.println("No sufficient match found for stop #" + match.stopEntity.platform.osm_id + " (" + match.stopEntity.platform.getTag("name") + ")");
            }
        }
    }
    /**
     *
     * @param conflictDistance: the distance (in meters) to check for existing stops
     * @throws InvalidArgumentException
     */
    public void conflateStops(final double conflictDistance) throws InvalidArgumentException {
        if(routeConflator.getAllRouteStops().size() == 0) {
            return;
        }

        //fetch all possible useful ways that intersect the route's combined bounding box
        final double latitudeDelta = -0.5 * conflictDistance / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * routeConflator.roughCentroid.latitude / 180.0);
        final Region stopDownloadRegion = routeConflator.importRouteMaster.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
        final OverpassConverter converter = new OverpassConverter();
        try {
            final String query;
            switch (routeConflator.routeType) {
                case OSMEntity.TAG_BUS: //bus stops are always nodes
                    query = converter.queryForBoundingBox("[\"highway\"=\"bus_stop\"]", stopDownloadRegion, 0.0, OSMEntity.OSMType.node);
                    break;
                case OSMEntity.TAG_LIGHT_RAIL:
                case OSMEntity.TAG_TRAIN:
                case OSMEntity.TAG_SUBWAY:
                case OSMEntity.TAG_TRAM:
                    query = converter.queryForBoundingBox("[\"railway\"=\"platform\"]", stopDownloadRegion, 0.0, null);
                    break;
                default:
                    return;
            }
            converter.fetchFromOverpass(query);
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
            return;
        }

        //add the downloaded OSM stops to the working entity space
        final OSMEntitySpace existingStopsSpace = converter.getEntitySpace();

        if(debugEnabled) {
            try {
                existingStopsSpace.outputXml("stopdownload.osm");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //and another list of stops in the existing OSM data
        final OSMEntitySpace workingEntitySpace = routeConflator.getWorkingEntitySpace();
        final ArrayList<OSMEntity> importedExistingStops = new ArrayList<>(existingStopsSpace.allEntities.size());
        for(final OSMEntity existingStop : existingStopsSpace.allEntities.values()) {
            importedExistingStops.add(workingEntitySpace.addEntity(existingStop, OSMEntity.TagMergeStrategy.keepTags, null));
        }

        //and compare them to the existing OSM data
        final String gtfsIdTag = "gtfs:stop_id";
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            final String importGtfsId = stop.platform.getTag("gtfs:stop_id");
            final String importRefTag = stop.platform.getTag(OSMEntity.KEY_REF);
            double importRefTagNumeric;
            try {
                assert importRefTag != null;
                importRefTagNumeric = Double.parseDouble(importRefTag);
            } catch(NumberFormatException e) {
                importRefTagNumeric = Double.MAX_VALUE;
            }

            for(final OSMEntity existingStopPlatform : importedExistingStops) {
                final String existingGtfsId = existingStopPlatform.getTag(gtfsIdTag);

                assert importGtfsId != null;

                //if the GTFS id or ref match, merge the existing stop with the import stop's data
                final Point existingStopLocation = new Point(existingStopPlatform.getCentroid().latitude, existingStopPlatform.getCentroid().longitude);
                OSMEntity mergedStopEntity = null;
                if(importGtfsId.equals(existingGtfsId)) {
                    mergedStopEntity = workingEntitySpace.mergeEntities(existingStopPlatform.osm_id, stop.platform.osm_id);
                    stop.platform = mergedStopEntity;
                    //System.out.println("GTFS id match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(gtfsIdTag) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                } else if(existingStopPlatform.hasTag(OSMEntity.KEY_REF)) { //try matching by ref if no importer id
                    final String existingRefTag = existingStopPlatform.getTag(OSMEntity.KEY_REF);
                    //TODO also match based on import dataset
                    assert existingRefTag != null;
                    if(existingRefTag.trim().equals(importRefTag)) { //string match
                        System.out.println("Ref string match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                        mergedStopEntity = workingEntitySpace.mergeEntities(existingStopPlatform.osm_id, stop.platform.osm_id);
                        stop.platform = mergedStopEntity;
                    } else if(importRefTagNumeric != Double.MAX_VALUE) { //try doing a basic numeric match if strings don't match (special case for already-imported King County metro data)
                        try {
                            final double existingRefTagNumeric = Double.parseDouble(existingRefTag);
                            if(existingRefTagNumeric == importRefTagNumeric) {
                                //System.out.println("Ref numeric match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                                mergedStopEntity = workingEntitySpace.mergeEntities(existingStopPlatform.osm_id, stop.platform.osm_id);
                                stop.platform = mergedStopEntity;
                            }
                        } catch(NumberFormatException ignored) {}
                    }
                }

                if(mergedStopEntity == null) { //if no matching stops found, check that the import data doesn't conflict with existing stops
                    final double stopDistance = Point.distance(stop.platform.getCentroid(), existingStopPlatform.getCentroid());
                    if(stopDistance < conflictDistance) {
                        //System.out.println("Within distance of " + stop.platform.osm_id + "/" + stop.platform.getTag("name") + "! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME) + ", dist " + stopDistance);
                        stop.platform.setTag("gtfs:conflict", "yes");
                    }
                } else {
                    //keep the existing platform's location if it's a node, since these are usually better-positioned than the GTFS data's
                    if(mergedStopEntity instanceof OSMNode) {
                        ((OSMNode) mergedStopEntity).setCoordinate(existingStopLocation);
                    }
                    mergedStopEntity.removeTag("gtfs:conflict"); //in case previously marked as a conflict
                    break; //bail since we've matched this stop
                }
            }
        }
    }
}
