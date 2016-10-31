package Conflation;

import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.*;

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

    /**
     * Match the routeConflator's stops to the best possible OSM way, based on its name, position,
     * way's proximity to routeLineSegment, etc
     */
    protected void matchStopsToWays() {
        final int stopCount = routeConflator.getAllRouteStops().size();
        if(stopCount == 0) { //TODO add warning
            System.out.println("No stops found for route " + routeConflator.importRouteMaster.getTag(OSMEntity.KEY_NAME));
            return;
        }

        //first, add all the route's stops to the Cell index
        for (final RouteConflator.Cell cell : RouteConflator.Cell.allCells) {
            for(final StopArea stop : routeConflator.getAllRouteStops()) {
                if (Region.intersects(cell.boundingBox, stop.getNearbyStopSearchRegion())) {
                    cell.addStop(stop);
                }
            }
        }


        //run a comparison based on proximity to the nearest OSM line for each subroute
        for(final Route route : routeConflator.getExportRoutes()) {
            for(final StopArea routeStop : route.stops) {
                //skip stops that already have a stop position assigned (the stop's way(s) are associated with the stop position)
                if(routeStop.getStopPosition() != null) {
                    continue;
                }

                //check the routeLine's SegmentMatches for the best matching way for this platform
                final Region osmSegmentSearchRegion = routeStop.getNearbyWaySearchRegion();
                RouteLineSegment routeLineSegment;
                for(final LineSegment lineSegment : route.routeLine.segments) {
                    routeLineSegment = (RouteLineSegment) lineSegment;
                    if(routeLineSegment.bestMatchOverall != null && Region.intersects(lineSegment.boundingBox, osmSegmentSearchRegion)) {
                        routeStop.addProximityMatch(routeLineSegment.bestMatchOverall);
                    }
                }
            }
        }

        //loop through all of the route's stops, assigning name matches as needed
        final StreetNameMatcher matcher = new StreetNameMatcher(Locale.US);
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            //skip stops that already have a stop position assigned (the stop's way(s) are associated with the stop position)
            if(stop.getStopPosition() != null) {
                continue;
            }

            //get the stop's name components, to see if we can use the name to match to a nearby way
            final String stopName = stop.getPlatform().getTag(OSMEntity.KEY_NAME);
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

            //update the way matches to include matches based on the street and platform names
            for(final Map<Long, StopArea.StopWayMatch> wayMatchesForRoute: stop.wayMatches.values()) { //list of all OSM way matches for a routeLine
                for (final StopArea.StopWayMatch stopWayMatch : wayMatchesForRoute.values()) { //and the individual OSM way matches
                    //Check if the way can be matched by name
                    final String wayName = stopWayMatch.osmLine.way.getTag(OSMEntity.KEY_NAME);
                    if (nameComponents != null && wayName != null) {
                        final StreetNameMatcher.StreetNameComponents wayNameComponents = matcher.createComponents(wayName);
                        final String wayBaseName = String.join(" ", wayNameComponents.baseComponents);
                        final StreetNameMatcher.StreetNameComponents primaryStreetComponents = nameComponents.get(0);
                        final StreetNameMatcher.StreetNameComponents secondaryStreetComponents = nameComponents.size() > 1 ? nameComponents.get(1) : null;

                        final String primaryStreetBaseName = String.join(" ", primaryStreetComponents.baseComponents);
                        final double primaryStringDistance = StreetNameMatcher.damerauLevenshteinDistance(primaryStreetBaseName, wayBaseName, 128);
                        if (debugEnabled) {
                            System.out.println("CHECK PLATFORM PRINAME: " + stop + ": vs " + wayName + "(" + primaryStreetBaseName + "/" + wayBaseName + "): " + primaryStringDistance + "/" + primaryStreetBaseName.length() + ", ratio " + (primaryStringDistance / primaryStreetBaseName.length()));
                        }
                        if (primaryStringDistance / primaryStreetBaseName.length() < MAX_LEVENSHTEIN_DISTANCE_RATIO) {
                            stopWayMatch.setNameMatch(StopArea.WayMatchType.primaryStreet);
                        } else if (secondaryStreetComponents != null) {
                            final String secondaryStreetBaseName = String.join(" ", secondaryStreetComponents.baseComponents);
                            final double secondaryStringDistance = StreetNameMatcher.damerauLevenshteinDistance(secondaryStreetBaseName, wayBaseName, 128);
                            if (debugEnabled) {
                                System.out.println("CHECK PLATFORM SECNAME: " + stop + ": vs " + wayName + "(" + primaryStreetBaseName + "/" + wayBaseName + "): " + secondaryStringDistance + "/" + secondaryStreetBaseName.length() + ", ratio " + (secondaryStringDistance / secondaryStreetBaseName.length()));
                            }
                            if (secondaryStringDistance / secondaryStreetBaseName.length() < MAX_LEVENSHTEIN_DISTANCE_RATIO) {
                                stopWayMatch.setNameMatch(StopArea.WayMatchType.crossStreet);
                            }
                        }
                    }
                }
            }

            //with all the matches checked, choose the best one
            stop.chooseBestWayMatch();
        }
    }

    /**
     * Adds the best match for each stop platform to their best-matched nearby way
     * @param entitySpace
     */
    protected void createStopPositionsForPlatforms(final OSMEntitySpace entitySpace) {
        for(final StopArea stopArea : routeConflator.getAllRouteStops()) {
            if(stopArea.bestWayMatch == null) {
                continue;
            }

            //check if there's an existing stop_position node that can be associated with the StopArea, and if so there's no need to set now
            if(stopArea.getStopPosition() != null) {
                continue;
            }

            //get a handle on the best-matching way to the StopArea, and the nearest point to it
            final LineSegment bestSegment = stopArea.bestWayMatch.getClosestSegmentToStopPlatform();
            if(bestSegment == null) {
                System.out.println("WARNING: " + stopArea + " has no nearby matching segment");
                for (final StopArea.SummarizedMatch otherMatch : stopArea.bestWayMatches) {
                    if (otherMatch == stopArea.bestWayMatch) {
                        System.out.println("\tBEST MATCH: " + otherMatch);
                    } else {
                        System.out.println("\t\tALTMATCH: " + otherMatch);
                    }
                }

                continue;
            }
            final Point nearestPointOnSegment = bestSegment.closestPointToPoint(stopArea.getPlatform().getCentroid());

            //Create (or update an existing node) to serve as the stop position node for the platform
            OSMNode nearestNodeOnWay = bestSegment.getParent().way.nearestNodeAtPoint(nearestPointOnSegment, StopArea.stopNodeTolerance);

            //double check the nearest node doesn't have already have a stop_position tag - may belong to another nearby stop!
            if(nearestNodeOnWay != null && OSMEntity.TAG_STOP_POSITION.equals(nearestNodeOnWay.getTag(OSMEntity.KEY_PUBLIC_TRANSPORT))) {
                final String stopAreaRef = stopArea.getPlatform().getTag(OSMEntity.KEY_REF);
                final String stopAreaGtfsId = stopArea.getPlatform().getTag(StopArea.KEY_GTFS_STOP_ID);
                final String nearestNodeRef = nearestNodeOnWay.getTag(OSMEntity.KEY_REF);
                final String nearestNodeGtfsId = nearestNodeOnWay.getTag(StopArea.KEY_GTFS_STOP_ID);

                //if the node has a ref or gtfs:stop_id tag, don't use it if they do not match the StopArea's values
                if(stopAreaRef != null && nearestNodeRef != null && !stopAreaRef.equals(nearestNodeRef)) {
                    nearestNodeOnWay = null;
                } else if(stopAreaGtfsId != null && nearestNodeGtfsId != null && !stopAreaGtfsId.equals(nearestNodeGtfsId)) {
                    nearestNodeOnWay = null;
                }
            }

            //and create a new node if a good candidate can't be found on the way
            if(nearestNodeOnWay == null) {
                nearestNodeOnWay = bestSegment.getParent().insertNode(entitySpace.createNode(nearestPointOnSegment.x, nearestPointOnSegment.y, null), bestSegment);
            }

            //and add the stop position to the stop area
            stopArea.setStopPosition(nearestNodeOnWay);
            nearestNodeOnWay.setTag(routeConflator.routeType, OSMEntity.TAG_YES); //TODO need proper key mapping (e.g. for subway, light_rail, etc)

            //clear the way matches for the stop, since they're no longer needed once the stop position is set
            if(stopArea.getStopPosition() != null) {
                stopArea.clearAllWayMatches();
            }

            //warn if no decent match is found
            /*if(stopArea.bestWayMatch.line. == null) {
                System.out.println("No sufficient match found for stop " + stopArea);
            }*/
        }
    }
    /**
     * Downloads all OSM stops in the current route's area, and conflates them with the route's associated import stops
     * @throws InvalidArgumentException
     */
    public void conflateStopsWithOSM() throws InvalidArgumentException {
        conflateStopsWithOSM(routeConflator.getAllRouteStops(), routeConflator.routeType, routeConflator.getWorkingEntitySpace());
    }

    /**
     * Downloads all OSM stops in the given stop list's area, and conflates them with the given stop list's stops
     * @param allStops the list of stops to conflate with existing OSM data
     * @param routeType the type of stops to search for (bus, train, etc)
     * @param destinationEntitySpace the entity space the stops (existing and imported) will be placed in
     * @throws InvalidArgumentException
     */
    public void conflateStopsWithOSM(final Collection<StopArea> allStops, final String routeType, final OSMEntitySpace destinationEntitySpace) throws InvalidArgumentException {
        if(allStops.size() == 0) {
            return;
        }

        //fetch all possible useful entities that intersect the route's stops' combined bounding box
        final Point[] includedStops = new Point[allStops.size()];
        int s = 0;
        for(final StopArea stop : allStops) {
            includedStops[s++] = stop.getPlatform().getCentroid();
        }
        Region stopDownloadRegion = new Region(includedStops);

        //expand the total area little further, to ensure the start/end of the route is included (problem with King County data)
        final double stopSearchBuffer = -SphericalMercator.metersToCoordDelta(100.0/*StopArea.duplicateStopPlatformBoundingBoxSize*/, stopDownloadRegion.getCentroid().y);
        stopDownloadRegion = stopDownloadRegion.regionInset(stopSearchBuffer, stopSearchBuffer);

        final OverpassConverter converter = new OverpassConverter();
        try {
            final String query;
            switch (routeType) {
                case OSMEntity.TAG_BUS: //bus stops are typically nodes, but may also be ways
                    final LatLonRegion stopDownloadRegionLL = SphericalMercator.mercatorToLatLon(stopDownloadRegion);
                    final String[] queryComponents = {
                            String.format("node[\"highway\"=\"bus_stop\"](%.07f,%.07f,%.07f,%.07f)", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("node[\"public_transport\"=\"platform\"](%.07f,%.07f,%.07f,%.07f)", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("node[\"public_transport\"=\"stop_position\"](%.07f,%.07f,%.07f,%.07f)", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("way[\"highway\"=\"bus_stop\"](%.07f,%.07f,%.07f,%.07f)", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("way[\"public_transport\"=\"platform\"](%.07f,%.07f,%.07f,%.07f)", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude)
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

        if(debugEnabled||true) {
            try {
                existingStopsSpace.outputXml("stopdownload.osm");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //and another list of stops in the existing OSM data
        final ArrayList<OSMEntity> importedExistingStops = new ArrayList<>(existingStopsSpace.allEntities.size());
        for(final OSMEntity existingStop : existingStopsSpace.allEntities.values()) {
            importedExistingStops.add(destinationEntitySpace.addEntity(existingStop, OSMEntity.TagMergeStrategy.keepTags, null));
        }

        //and compare them to the existing OSM data
        for(final StopArea stop : allStops) {
            final String importGtfsId = stop.getPlatform().getTag(StopArea.KEY_GTFS_STOP_ID);
            assert importGtfsId != null;
            final String importRefTag = stop.getPlatform().getTag(OSMEntity.KEY_REF);
            double importRefTagNumeric;
            try {
                assert importRefTag != null;
                importRefTagNumeric = Double.parseDouble(importRefTag);
            } catch(NumberFormatException e) {
                importRefTagNumeric = Double.MAX_VALUE;
            }

            final ListIterator<OSMEntity> importedExistingStopsIterator = importedExistingStops.listIterator();
            while (importedExistingStopsIterator.hasNext()) {
                final OSMEntity existingEntity = importedExistingStopsIterator.next();
                final String existingGtfsId = existingEntity.getTag(StopArea.KEY_GTFS_STOP_ID);

                //if the GTFS id or ref match, merge the existing stop entity with the import stop's data
                final boolean idMatchFound = checkMatchingTags(existingGtfsId, importGtfsId, importRefTag, importRefTagNumeric, existingEntity);
                if(!idMatchFound) { //if no matching entities found, check that the import data doesn't conflict with existing stops
                    //check whether the entity is a platform or a stop_position
                    final String entityType = existingEntity.getTag(OSMEntity.KEY_PUBLIC_TRANSPORT);

                    if(entityType == null || OSMEntity.TAG_PLATFORM.equals(entityType)) { //if a platform, mark as a conflict
                        final double stopDistance = Point.distance(stop.getPlatform().getCentroid(), existingEntity.getCentroid());
                        if (stopDistance < StopArea.maxDistanceBetweenDuplicateStops) {
                            //System.out.println("Within distance of " + stop + "! " + existingEntity.osm_id + ": " + existingEntity.getTag(OSMEntity.KEY_REF) + "/" + existingEntity.getTag(OSMEntity.KEY_NAME) + ", dist " + stopDistance);
                            stop.getPlatform().setTag(StopArea.KEY_GTFS_CONFLICT, "yes");
                        }
                    } else if(OSMEntity.TAG_STOP_POSITION.equals(entityType)) { //no action taken on existing stop positions
                        //System.out.println("No GTFS/REF MATCH FOR " + existingEntity.getTags().toString());
                    }
                } else {
                    //check whether the entity is a platform or a stop_position (may be null if simply a highway=bus_stop)
                    final String entityType = existingEntity.getTag(OSMEntity.KEY_PUBLIC_TRANSPORT);

                    if(entityType == null || OSMEntity.TAG_PLATFORM.equals(entityType)) {
                        //copy the tags from the GTFS stop into the existing stop (we keep the existing platform's location, since these are usually better-positioned than the GTFS data's)
                        existingEntity.copyTagsFrom(stop.getPlatform(), OSMEntity.TagMergeStrategy.copyTags);
                        destinationEntitySpace.deleteEntity(stop.getPlatform()); //delete the GTFS entity from the working space - no longer needed
                        stop.setPlatform(existingEntity); //and point the StopArea's platform to the existing platform entity

                        stop.getPlatform().removeTag(StopArea.KEY_GTFS_CONFLICT); //in case previously marked as a conflict

                        //remove from the imported entity list since we've matched it
                        importedExistingStopsIterator.remove();
                    } else if(OSMEntity.TAG_STOP_POSITION.equals(entityType)) {
                        stop.setStopPosition((OSMNode) existingEntity);

                        //remove from the imported entity list since we've matched it
                        importedExistingStopsIterator.remove();
                    }
                }
            }
        }
    }
    private static boolean checkMatchingTags(final String existingGtfsId, final String importGtfsId, final String importRefTag, final double importRefTagNumeric, final OSMEntity existingEntity) {
        //if the GTFS id or ref match, merge the existing stop with the import stop's data
        boolean idMatchFound = false;
        if(importGtfsId.equals(existingGtfsId)) {
            idMatchFound = true;
            // System.out.println("GTFS id match! " + existingEntity.osm_id + ": " + existingEntity.getTag(gtfsIdTag) + "/" + existingEntity.getTag(OSMEntity.KEY_NAME));
        } else if(existingEntity.hasTag(OSMEntity.KEY_REF)) { //try matching by ref if no importer id
            final String existingRefTag = existingEntity.getTag(OSMEntity.KEY_REF);
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
        return idMatchFound;
    }
}
