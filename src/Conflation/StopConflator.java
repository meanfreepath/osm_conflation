package Conflation;

import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Manages the association of stops/waypoints with their respective OSM ways
 * Created by nick on 1/27/16.
 */
public class StopConflator {
    private final static String stopNameStreetSeparator = " & ";
    private final static double MAX_LEVENSHTEIN_DISTANCE_RATIO = 0.20;
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
        final double latitudeDelta = -StopArea.maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * routeConflator.roughCentroid.latitude / 180.0);

        //loop through the route's stops, determining the best-matching way for each one
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            //get the stop's name components, to see if we can use the name to match to a nearby way
            final String stopName = stop.platform.getTag(OSMEntity.KEY_NAME);
            final List<StreetNameMatcher.StreetNameComponents> nameComponents;
            if(stopName != null) {
                final String[] stopStreetNames = stopName.split(stopNameStreetSeparator);
                nameComponents = new ArrayList<>(stopStreetNames.length);
                for(final String s : stopStreetNames) {
                    nameComponents.add(matcher.createComponents(s));
                }
            } else {
                nameComponents = null;
            }

            //find the nearest OSM ways to the stop platforms, checking all lines whose bounding boxes intersect the stop's search area
            final Point platformCentroid = stop.platform.getCentroid();
            for(final WaySegments line : routeConflator.getCandidateLines().values()) {
                //check the line's bounding box intersects
                if(!Region.intersects(line.way.getBoundingBox().regionInset(latitudeDelta, longitudeDelta), stop.nearbyWaySearchRegion)) {
                    continue;
                }

                //skip this line if the nearest segment is not within the maximum distance to the stop
                if(line.closestSegmentToPoint(platformCentroid, StopArea.maxDistanceFromPlatformToWay) == null) {
                    //System.out.println("NO MATCH for platform " + stopPlatform.osm_id + "(ref " + stopPlatform.getTag("ref") + "/" + stopPlatform.getTag("name") + ")");
                    continue;
                }

                //Check if the way can be matched by name
                final String wayName = line.way.getTag(OSMEntity.KEY_NAME);
                if(nameComponents != null && wayName != null) {
                    final StreetNameMatcher.StreetNameComponents wayNameComponents = matcher.createComponents(wayName);
                    final String wayBaseName = String.join(" ", wayNameComponents.baseComponents);
                    final StreetNameMatcher.StreetNameComponents primaryStreetComponents = nameComponents.get(0);
                    final StreetNameMatcher.StreetNameComponents secondaryStreetComponents = nameComponents.size() > 1 ?  nameComponents.get(1) : null;

                    final String primaryStreetBaseName = String.join(" ", primaryStreetComponents.baseComponents);
                    final double primaryStringDistance = StreetNameMatcher.damerauLevenshteinDistance(primaryStreetBaseName, wayBaseName, 128);
                    if(debugEnabled) {
                        System.out.println("CHECK PLATFORM PRINAME: " + stop.platform.getTag("name") + "(" + stop.platform.getTag("ref") + ") vs " + wayName + "(" + primaryStreetBaseName + "/" + wayBaseName + "): " + primaryStringDistance + "/" + primaryStreetBaseName.length() + ", ratio " + (primaryStringDistance / primaryStreetBaseName.length()));
                    }
                    if (primaryStringDistance / primaryStreetBaseName.length() < MAX_LEVENSHTEIN_DISTANCE_RATIO) {
                        stop.addNameMatch(line, StopArea.SegmentMatchType.primaryStreet);
                    } else if (secondaryStreetComponents != null) {
                        final String secondaryStreetBaseName = String.join(" ", secondaryStreetComponents.baseComponents);
                        final double secondaryStringDistance = StreetNameMatcher.damerauLevenshteinDistance(secondaryStreetBaseName, wayBaseName, 128);
                        if(debugEnabled) {
                            System.out.println("CHECK PLATFORM SECNAME: " + stop.platform.getTag("name") + "(" + stop.platform.getTag("ref") + "): vs " + wayName + "(" + primaryStreetBaseName + "/" + wayBaseName + "): " + secondaryStringDistance + "/" + secondaryStreetBaseName.length() + ", ratio " + (secondaryStringDistance / secondaryStreetBaseName.length()));
                        }
                        if(secondaryStringDistance / secondaryStreetBaseName.length() < MAX_LEVENSHTEIN_DISTANCE_RATIO) {
                            stop.addNameMatch(line, StopArea.SegmentMatchType.crossStreet);
                        }
                    }
                }
            }
        }

        //now do a comparison based on proximity to the nearest OSM line
        for(final Route route : routeConflator.getExportRoutes()) {
            for(final StopArea routeStop : route.stops) {
                final Point platformCentroid = routeStop.platform.getCentroid();
                for(final WaySegments osmLine : routeConflator.getCandidateLines().values()) {
                    //check the segment's bounding box intersects
                    if (!Region.intersects(osmLine.way.getBoundingBox().regionInset(latitudeDelta, longitudeDelta), routeStop.nearbyWaySearchRegion)) {
                        continue;
                    }

                    //find the nearest segment to the platform on the way
                    final LineSegment nearestSegment = osmLine.closestSegmentToPoint(platformCentroid, StopArea.maxDistanceFromPlatformToWay);

                    //skip this line if not within the maximum distance to the stop
                    if (nearestSegment != null) {
                        //System.out.println("OSMLINE MATCH for platform " + routeStop.platform.osm_id + "(ref " + routeStop.platform.getTag("ref") + "/" + routeStop.platform.getTag("name") + ")");
                        routeStop.addProximityMatch(route.routeLine, nearestSegment, Point.distance(nearestSegment.closestPointToPoint(platformCentroid), platformCentroid), StopArea.SegmentMatchType.proximityToOSMWay);
                    }
                }
            }
        }

        //now pick the best matches for each platform
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            stop.chooseBestWayMatch();

            if(debugEnabled) {
                if (stop.bestWayMatch != null) {
                    System.out.println("Best match for " + stop.platform.osm_id + "(ref " + stop.platform.getTag("ref") + "/" + stop.platform.getTag("name") + "):" + stop.bestWayMatch.line.way.getTag("name") + "(" + stop.bestWayMatch.line.way.osm_id + ")");
                } else {
                    System.out.println("NO MATCH for " + stop.platform.osm_id + "(ref " + stop.platform.getTag("ref") + "/" + stop.platform.getTag("name") + ")");
                }
            }
        }
    }

    /**
     * Adds the best match for each stop platform to their best-matched nearby way
     * @param entitySpace
     */
    public void createStopPositionsForPlatforms(final OSMEntitySpace entitySpace) {
        for(final StopArea stopArea : routeConflator.getAllRouteStops()) {
            if(stopArea.bestWayMatch == null) {
                continue;
            }

            //Create (or update an existing node) to serve as the stop position node for the platform
            final LineSegment bestSegment = stopArea.bestWayMatch.closestSegmentToStop;
            final Point nearestPointOnSegment = bestSegment.closestPointToPoint(stopArea.platform.getCentroid());

            OSMNode nearestNodeOnWay = bestSegment.parentSegments.way.nearestNodeAtPoint(nearestPointOnSegment, StopArea.stopNodeTolerance);
            if(nearestNodeOnWay == null) {
                nearestNodeOnWay = bestSegment.parentSegments.insertNode(entitySpace.createNode(nearestPointOnSegment.latitude, nearestPointOnSegment.longitude, null), bestSegment);
                stopArea.chooseBestWayMatch();
            }

            //then add a node on the nearest point and add to the relation and the way
            OSMPresetFactory.makeStopPosition(nearestNodeOnWay);
            nearestNodeOnWay.setTag(OSMEntity.KEY_NAME, stopArea.platform.getTag(OSMEntity.KEY_NAME));
            nearestNodeOnWay.setTag(OSMEntity.KEY_REF, stopArea.platform.getTag(OSMEntity.KEY_REF));
            nearestNodeOnWay.setTag("gtfs:stop_id", stopArea.platform.getTag("gtfs:stop_id"));
            nearestNodeOnWay.setTag(routeConflator.routeType, OSMEntity.TAG_YES); //TODO need proper key mapping (e.g. for subway, light_rail, etc)

            //and add the stop position to the stop area
            stopArea.setStopPosition(nearestNodeOnWay);

            //warn if no decent match is found
            /*if(stopArea.bestWayMatch.line. == null) {
                System.out.println("No sufficient match found for stop #" + match.stopEntity.platform.osm_id + " (" + match.stopEntity.platform.getTag("name") + ")");
            }*/
        }
    }
    /**
     *
     * @param conflictDistance: the distance (in meters) to check for existing stops
     * @throws InvalidArgumentException
     */
    public void conflateStops(final double conflictDistance, final Collection<StopArea> allStops, final String routeType, final OSMEntitySpace workingEntitySpace) throws InvalidArgumentException {
        if(allStops.size() == 0) {
            return;
        }
        Region stopDownloadRegion = allStops.iterator().next().platform.getBoundingBox().clone();
        for(final StopArea stop : allStops) {
            stopDownloadRegion.includePoint(stop.platform.getCentroid());
        }


        //fetch all possible useful ways that intersect the route's combined bounding box
        final double latitudeDelta = -0.5 * conflictDistance / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * stopDownloadRegion.getCentroid().latitude / 180.0);
        stopDownloadRegion = stopDownloadRegion.regionInset(latitudeDelta, longitudeDelta);
        final OverpassConverter converter = new OverpassConverter();
        try {
            final String query;
            switch (routeType) {
                case OSMEntity.TAG_BUS: //bus stops are always nodes
                    final String[] queryComponents = {
                            String.format("node[\"highway\"=\"bus_stop\"](%.04f,%.04f,%.04f,%.04f)", stopDownloadRegion.origin.latitude, stopDownloadRegion.origin.longitude, stopDownloadRegion.extent.latitude, stopDownloadRegion.extent.longitude),
                            String.format("node[\"public_transport\"=\"platform\"](%.04f,%.04f,%.04f,%.04f)", stopDownloadRegion.origin.latitude, stopDownloadRegion.origin.longitude, stopDownloadRegion.extent.latitude, stopDownloadRegion.extent.longitude),
                            String.format("way[\"highway\"=\"bus_stop\"](%.04f,%.04f,%.04f,%.04f)", stopDownloadRegion.origin.latitude, stopDownloadRegion.origin.longitude, stopDownloadRegion.extent.latitude, stopDownloadRegion.extent.longitude),
                            String.format("way[\"public_transport\"=\"platform\"](%.04f,%.04f,%.04f,%.04f)", stopDownloadRegion.origin.latitude, stopDownloadRegion.origin.longitude, stopDownloadRegion.extent.latitude, stopDownloadRegion.extent.longitude)
                    };
                    query = "(" + String.join(";", queryComponents) + ");(._;>;);";
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
        existingStopsSpace.markAllEntitiesWithAction(OSMEntity.ChangeAction.none);

        if(debugEnabled) {
            try {
                existingStopsSpace.outputXml("stopdownload.osm");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //and another list of stops in the existing OSM data
        final ArrayList<OSMEntity> importedExistingStops = new ArrayList<>(existingStopsSpace.allEntities.size());
        for(final OSMEntity existingStop : existingStopsSpace.allEntities.values()) {
            importedExistingStops.add(workingEntitySpace.addEntity(existingStop, OSMEntity.TagMergeStrategy.keepTags, null));
        }

        //and compare them to the existing OSM data
        final String gtfsIdTag = "gtfs:stop_id";
        for(final StopArea stop : allStops) {
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
                boolean idMatchFound = false;
                if(importGtfsId.equals(existingGtfsId)) {
                    idMatchFound = true;
                   // System.out.println("GTFS id match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(gtfsIdTag) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                } else if(existingStopPlatform.hasTag(OSMEntity.KEY_REF)) { //try matching by ref if no importer id
                    final String existingRefTag = existingStopPlatform.getTag(OSMEntity.KEY_REF);
                    assert existingRefTag != null;
                    if(existingRefTag.trim().equals(importRefTag)) { //string match
                        idMatchFound = true;
                    } else if(importRefTagNumeric != Double.MAX_VALUE) { //try doing a basic numeric match if strings don't match (special case for already-imported King County metro data)
                        try {
                            final double existingRefTagNumeric = Double.parseDouble(existingRefTag);
                            idMatchFound = existingRefTagNumeric == importRefTagNumeric;
                        } catch(NumberFormatException ignored) {}
                    }
                }

                if(!idMatchFound) { //if no matching stops found, check that the import data doesn't conflict with existing stops
                    final double stopDistance = Point.distance(stop.platform.getCentroid(), existingStopPlatform.getCentroid());
                    if(stopDistance < conflictDistance) {
                        //System.out.println("Within distance of " + stop.platform.osm_id + "/" + stop.platform.getTag("name") + "! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME) + ", dist " + stopDistance);
                        stop.platform.setTag("gtfs:conflict", "yes");
                    }
                } else {
                    //copy the tags from the GTFS stop into the existing stop (we keep the existing platform's location, since these are usually better-positioned than the GTFS data's)
                    existingStopPlatform.copyTagsFrom(stop.platform, OSMEntity.TagMergeStrategy.copyTags);
                    workingEntitySpace.deleteEntity(stop.platform); //delete the GTFS entity from the working space - no longer needed
                    stop.platform = existingStopPlatform; //and point the StopArea's platform to the existing platform entity

                    stop.platform.removeTag("gtfs:conflict"); //in case previously marked as a conflict
                    break; //bail since we've matched this stop
                }
            }
        }
    }
}
