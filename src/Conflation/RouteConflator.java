package Conflation;

import NewPathFinding.PathTree;
import OSM.*;
import com.company.Config;
import com.sun.istack.internal.NotNull;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.*;

/**
 * Tracks and processes a route_master-type OSM relation
 * Created by nick on 1/27/16.
 */
public class RouteConflator {
    public static class LineComparisonOptions {
        public double maxSegmentLength = 5.0, maxSegmentOrthogonalDistance = 10.0, maxSegmentMidPointDistance = 10.0, segmentSearchBoxSize = 30.0;
        private double minSegmentDotProduct, minFutureVectorDotProduct;

        public LineComparisonOptions() {
            setMaxSegmentAngle(30.0); //default to 30 degrees
            setMaxFutureVectorAngle(75.0);
        }
        public void setMaxSegmentAngle(final double angle) {
            minSegmentDotProduct = Math.cos(angle * Math.PI / 180.0);
        }
        public void setMaxFutureVectorAngle(final double angle) {
            minFutureVectorDotProduct = Math.cos(angle * Math.PI / 180.0);
        }
        public double getMinSegmentDotProduct() {
            return minSegmentDotProduct;
        }
        public double getMinFutureVectorDotProduct() {
            return minFutureVectorDotProduct;
        }
    }
    public final static String GTFS_AGENCY_ID = "gtfs:agency_id", GTFS_ROUTE_ID = "gtfs:route_id", GTFS_TRIP_ID = "gtfs:trip_id", GTFS_TRIP_MARKER = "gtfs:trip_marker", GTFS_STOP_ID = "gtfs:stop_id", GTFS_IGNORE = "gtfs:ignore";

    public enum RouteType {
        bus, railway, light_rail, monorail, train, tram, subway, ferry;

        public static RouteType fromString(final String routeType) {
            switch(routeType) {
                case OSMEntity.TAG_BUS:
                    return bus;
                case OSMEntity.TAG_LIGHT_RAIL:
                    return light_rail;
                case OSMEntity.TAG_TRAIN:
                    return train;
                case OSMEntity.TAG_SUBWAY:
                    return subway;
                case OSMEntity.TAG_TRAM:
                    return tram;
                case OSMEntity.TAG_FERRY:
                    return ferry;
            }
            return null;
        }

        /**
         * Gets the supplementary route type key (i.e. bus=yes, tram=yes, etc) for public_transport=stop_position
         * @return The key the stop_position should have if it supports the given route type
         */
        public String spKeyForRouteType() {
            switch (this) {
                case bus:
                    return OSMEntity.KEY_BUS;
                case railway:
                case light_rail:
                case train:
                    return OSMEntity.KEY_TRAIN;
                case monorail:
                    return OSMEntity.KEY_MONORAIL;
                case tram:
                    return OSMEntity.KEY_TRAM;
                case subway:
                    return OSMEntity.KEY_SUBWAY;
                case ferry:
                    return OSMEntity.KEY_FERRY;
            }
            return null;
        }
    }

    @NotNull
    public final RouteType routeType;
    public final String gtfsRouteId;
    private final OSMRelation importRouteMaster;
    protected final List<Route> importRoutes;
    private OSMRelation exportRouteMaster;
    private List<Route> exportRoutes;
    private HashMap<Long, StopArea> allRouteStops;
    public static boolean debugEnabled = false;
    public final String debugTripMarker = null;//"10673026:1";
    private RouteDataManager workingEntitySpace = null;
    public final LineComparisonOptions wayMatchingOptions;
    public final Map<String, List<String>> allowedRouteTags, allowedPlatformTags;

    public RouteConflator(final OSMRelation routeMaster, final RouteDataManager dataManager, LineComparisonOptions lineComparisonOptions) throws InvalidArgumentException {
        importRouteMaster = routeMaster;
        this.workingEntitySpace = dataManager;
        wayMatchingOptions = lineComparisonOptions;
        routeType = RouteType.fromString(routeMaster.getTag(OSMEntity.KEY_ROUTE_MASTER));
        gtfsRouteId = routeMaster.getTag(GTFS_ROUTE_ID);
        if(routeType == null) {
            final String errMsg[] = {"Invalid route type provided"};
            throw new InvalidArgumentException(errMsg);
        }

        allowedRouteTags = wayTagsForRouteType(routeType);
        allowedPlatformTags = platformTagsForRouteType(routeType);

        //generate the list of subroutes to process
        final List<OSMRelation.OSMRelationMember> subRoutes = routeMaster.getMembers();
        importRoutes = new ArrayList<>(subRoutes.size());
        for(final OSMRelation.OSMRelationMember member : subRoutes) {
            if (!(member.member instanceof OSMRelation)) { //should always be the case
                continue;
            }
            final OSMRelation subRoute = (OSMRelation) member.member;

            //check if the GTFS importer has added a way to the relation, to indicate the path of the route
            OSMWay routePath = null;
            final List<OSMRelation.OSMRelationMember> lineMembers = subRoute.getMembers("");
            if(lineMembers.size() > 0 && lineMembers.get(0).member instanceof OSMWay) {
                routePath = (OSMWay) lineMembers.get(0).member;
            }

            //create a list of StopArea objects to represent the stops on the route
            final ArrayList<StopArea> routeStops = new ArrayList<>(Route.INITIAL_STOPS_CAPACITY);
            for(final OSMRelation.OSMRelationMember stopMember : subRoute.getMembers(OSMEntity.TAG_PLATFORM)) {
                routeStops.add(new StopArea(stopMember.member));
            }

            importRoutes.add(new Route(subRoute, routePath, routeStops, this));
        }
    }

    public static String gtfsFileNameForRoute(final String gtfsRouteId) {
        return String.format("%s/gtfsroute_%s.osm", Config.sharedInstance.outputDirectory, gtfsRouteId);
    }

    /**
     * Define the search tags for the ways for the given route type
     * @param relationType The type of route relation ("bus", "rail", etc)
     * @return The tags to search for the ways with
     */
    public static Map<String, List<String>> wayTagsForRouteType(final RouteType relationType) {
        String[] keys = null;
        String[][] tags = null;
        switch (relationType) {
            case bus:
                keys = new String[]{"highway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "residential", "unclassified", "service", "living_street"};
                break;
            case railway:
            case train:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail"};
                break;
            case light_rail:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "subway", "tram"};
                break;
            case monorail:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"monorail"};
                break;
            case tram:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "tram"};
                break;
            case subway:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "subway"};
                break;
            case ferry:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "subway"};
                break;
        }
        final Map<String, List<String>> keyMap = new HashMap<>(keys.length);
        int keyIdx = 0;
        for(final String key : keys) {
            final List<String> tagList = new ArrayList<>(tags[keyIdx].length);
            Collections.addAll(tagList, tags[keyIdx]);
            keyMap.put(key, tagList);
            keyIdx++;
        }

        return keyMap;
    }
    /**
     * Define the search tags for the platforms for the given route type
     * @param relationType The type of route relation ("bus", "rail", etc)
     * @return The tags to search for the platforms with
     */
    public static Map<String, List<String>> platformTagsForRouteType(final RouteType relationType) {
        String[] keys;
        String[][] tags;
        switch (relationType) {
            case bus:
                keys = new String[]{"highway", "public_transport"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"bus_stop"};
                tags[1] = new String[]{"platform", "stop_position"};
                break;
            case railway:
            case light_rail:
            case monorail:
            case train:
            case tram:
            case subway:
                keys = new String[]{"railway", "public_transport"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"platform"};
                tags[1] = new String[]{"platform", "stop_position"};
                break;
            case ferry:
                keys = new String[]{"amenity", "public_transport"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"ferry_terminal"};
                tags[1] = new String[]{"platform", "stop_position"};
                break;
            default:
                return null;
        }
        final Map<String, List<String>> keyMap = new HashMap<>(keys.length);
        int keyIdx = 0;
        for(final String key : keys) {
            final List<String> tagList = new ArrayList<>(tags[keyIdx].length);
            Collections.addAll(tagList, tags[keyIdx]);
            keyMap.put(key, tagList);
            keyIdx++;
        }

        return keyMap;
    }

    /**
     * Conflates the GTFS route object with any existing OSM route_master relations, based on the GTFS route id and/or ref tag
     * @param existingRouteMasters A list of all existing route_master relations in workingEntitySpace
     * @return a list of child route relations belonging to the matched route_master relation (will be blank if a new route_master was created)
     */
    protected List<OSMRelation> conflateExistingRouteMaster(final List<OSMRelation> existingRouteMasters) {
        //scan the route_masters list for route_masters matching the import route
        final String importRouteRef = importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteAgencyId = importRouteMaster.getTag(GTFS_AGENCY_ID);
        for(final OSMRelation relation : existingRouteMasters) {
            //break on the first GTFS id match
            if(gtfsRouteId != null && gtfsRouteId.equals(relation.getTag(GTFS_ROUTE_ID)) && importRouteAgencyId != null && importRouteAgencyId.equals(relation.getTag(GTFS_AGENCY_ID))) {
                exportRouteMaster = relation;
                System.out.format("INFO: Matched route master to existing relation #%d using %s tag\n", exportRouteMaster.osm_id, GTFS_ROUTE_ID);
                break;
            }

            //try matching on ref as well
            if(importRouteRef != null && importRouteRef.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_REF))) {
                exportRouteMaster = relation;
                System.out.format("INFO: Matched route master to existing relation #%d using ref tag\n", exportRouteMaster.osm_id);
                break;
            }
        }
        //tripRoute.setTag(GTFS_TRIP_MARKER, String.format(tripIdentifierFormat, trip.getField(tripGroupingField), trip.getField(GTFSObjectTrip.FIELD_DIRECTION_ID)));

        final List<OSMRelation> existingSubRoutes;
        if(exportRouteMaster != null) { //if the route_master was matched to an existing OSM route_master
            //remove all existing child routes from the route_master relation, cataloging them in a separate array
            existingSubRoutes = new ArrayList<>(exportRouteMaster.getMembers().size());
            final ListIterator<OSMRelation.OSMRelationMember> memberListIterator = exportRouteMaster.getMembers().listIterator();
            while(memberListIterator.hasNext()) {
                OSMRelation.OSMRelationMember relation = memberListIterator.next();
                if(relation.member instanceof OSMRelation) { //should always be the case, as
                    existingSubRoutes.add((OSMRelation) relation.member);
                } else {
                    System.out.format("WARNING: existing route_master #%d has a non-relation member!", exportRouteMaster.osm_id);
                }
                memberListIterator.remove();
            }

            //and copy over the tags from the GTFS route master
            exportRouteMaster.copyTagsFrom(importRouteMaster, OSMEntity.TagMergeStrategy.copyTags);
        } else { //otherwise, just create a new route_master object
            existingSubRoutes = new ArrayList<>();
            System.out.format("INFO: Creating new route master for route\n");
            exportRouteMaster = workingEntitySpace.createRelation(importRouteMaster.getTags(), null);
        }
        return existingSubRoutes;
    }

    /**
     * Conflates the GTFS import route's trips with existing OSM route relations
     * @param existingRoutes - a list of all type=route relations in workingEntitySpace
     * @param existingSubRoutes - a list of the existing OSM routes that belong to the existing route_master relation (if any - will be empty if a new route_master was created)
     */
    protected void conflateExistingRouteRelations(final List<OSMRelation> existingRoutes, final List<OSMRelation> existingSubRoutes) {
        //first compile a list of existing routes to consider for conflation
        final String importRouteAgencyId = exportRouteMaster.getTag(GTFS_AGENCY_ID), importRouteMasterRef = exportRouteMaster.getTag(OSMEntity.KEY_REF);
        final List<OSMRelation> matchingRoutes;
        final boolean existingSubRoutesPresent = existingSubRoutes.size() > 0;
        if(existingSubRoutesPresent) { //if there's an existing route_master, use its child routes as the list
            matchingRoutes = existingSubRoutes;
        } else { //if there was no existing route_master, or it had no child routes, create a list of existing routes based on ref
            matchingRoutes = new ArrayList<>();
            for(final OSMRelation relation : existingRoutes) {
                if(importRouteMasterRef != null && importRouteMasterRef.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_REF))) {
                    matchingRoutes.add(relation);
                }
            }
        }

        //now iterate over the GTFS import routes (trips), matching them to the existing routes (or creating them if no match made)
        exportRoutes = new ArrayList<>(importRoutes.size());
        allRouteStops = new HashMap<>(importRoutes.size() * 64);
        for(final Route importRoute : importRoutes) {
            //System.out.println("CHECK subroute \"" + importRoute.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + importRoute.routeRelation.osm_id + ")");
            //first try to match up the import route (trip) with the matching routes, using the GTFS trip_marker
            OSMRelation existingRouteRelation = null;
            ListIterator<OSMRelation> matchingRoutesIterator = matchingRoutes.listIterator();
            while(matchingRoutesIterator.hasNext()) {
                final OSMRelation relation = matchingRoutesIterator.next();

                //break on the first GTFS id match
                //System.out.println("check TRIP " + importRoute.tripMarker + " vs " + relation.getTag(GTFS_TRIP_MARKER));
                if(importRoute.tripMarker != null && importRoute.tripMarker.equals(relation.getTag(GTFS_TRIP_MARKER))) {
                    existingRouteRelation = relation;
                    System.out.format("INFO: Matched trip to existing relation #%d using %s tag\n", existingRouteRelation.osm_id, GTFS_TRIP_MARKER);
                    matchingRoutesIterator.remove();
                    break;
                }
            }

            //if nothing was found using the GTFS trip_marker tag, select one of the matching routes as the one to use
            //NOTE: this will overwrite any route with the same ref - may be a problem in areas where multiple agencies use the same route numbers!
            if(existingRouteRelation == null) {
                System.out.format("INFO: no matches for trip based on GTFS id: using first existing route instead\n");
                matchingRoutesIterator = matchingRoutes.listIterator();
                while (matchingRoutesIterator.hasNext()) {
                    final OSMRelation relation = matchingRoutesIterator.next();
                    if (!relation.hasTag(GTFS_TRIP_MARKER)) {
                        existingRouteRelation = relation;
                        matchingRoutesIterator.remove();
                        break;
                    }
                }
            }

            //now create the list of stops for the route
            final ArrayList<StopArea> exportRouteStops = generateStopAreasForRoute(importRoute);

            //and add the stops to the existing route relation
            final Route exportRoute;
            if(existingRouteRelation != null) {
                //copy over the tags from the GTFS trip object
                existingRouteRelation.copyTagsFrom(importRoute.routeRelation, OSMEntity.TagMergeStrategy.copyTags);

                //remove all existing memberList from the route relation - will be substituted with matched data later
                final List<OSMRelation.OSMRelationMember> existingRouteMembers = new ArrayList<>(existingRouteRelation.getMembers());
                for(final OSMRelation.OSMRelationMember member : existingRouteMembers) {
                    existingRouteRelation.removeMember(member.member, Integer.MAX_VALUE);
                }
                final OSMWay importRoutePath = (OSMWay) importRoute.routeRelation.getMembers("").get(0).member;
                final OSMRelation exportRouteRelation = (OSMRelation) workingEntitySpace.addEntity(existingRouteRelation, OSMEntity.TagMergeStrategy.copyTags, null, true);
                exportRoute = new Route(exportRouteRelation, importRoutePath, exportRouteStops, this);
            } else {
                exportRoute = new Route(importRoute, exportRouteStops, workingEntitySpace);
            }
            exportRoutes.add(exportRoute);
            exportRouteMaster.addMember(exportRoute.routeRelation, OSMEntity.MEMBERSHIP_DEFAULT);
        }

        /*if the existing route_master has any leftover routes that weren't matched above, they were most likely
          canceled by the transit agency - delete them from the entity space (and from OSM)*/
        if(existingSubRoutesPresent && matchingRoutes.size() > 0) {
            for(final OSMRelation relation : matchingRoutes) {
                workingEntitySpace.deleteEntity(relation);
            }
        }
    }

    /**
     * Used for generating the full list of StopAreas for this route, only when just outputting stops
     * @return
     */
    public ArrayList<StopArea> generateStopAreas() {
        allRouteStops = new HashMap<>(64 * importRoutes.size());
        final ArrayList<StopArea> allStops = new ArrayList<>(allRouteStops.size());
        for(final Route importRoute : importRoutes) {
            allStops.addAll(generateStopAreasForRoute(importRoute));
        }
        return allStops;
    }

    /**
     * Generates the StopArea objects for the given import route
     * @param importRoute the GTFS route to create the StopAreas for
     * @return
     */
    private ArrayList<StopArea> generateStopAreasForRoute(final Route importRoute) {
        final ArrayList<StopArea> exportRouteStops = new ArrayList<>(importRoute.stops.size());
        StopArea existingStop;
        OSMEntity newStopPlatform;
        OSMNode newStopPosition;
        for(final StopArea stop : importRoute.stops) {
            existingStop = allRouteStops.get(stop.getPlatform().osm_id);

            if(existingStop == null) {
                newStopPlatform = workingEntitySpace.addEntity(stop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null, true);
                if (stop.getStopPosition(routeType) != null) {
                    newStopPosition = (OSMNode) workingEntitySpace.addEntity(stop.getStopPosition(routeType), OSMEntity.TagMergeStrategy.keepTags, null, true);
                } else {
                    newStopPosition = null;
                }
                existingStop = new StopArea(newStopPlatform);
                existingStop.setStopPosition(newStopPosition, routeType);
                allRouteStops.put(stop.getPlatform().osm_id, existingStop);
            }
            exportRouteStops.add(existingStop);
        }
        return exportRouteStops;
    }
    public boolean conflateRoutePaths(final StopConflator stopConflator) {
        for(final Route route : exportRoutes) {
            System.out.println("Begin conflation for subroute \"" + route.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + route.routeRelation.osm_id + ")");
            if (debugTripMarker != null && !route.tripMarker.equals(debugTripMarker)) {
                System.out.println("skipping (not a flagged route)");
                continue;
            }

            //get a handle on the WaySegments that geographically match the route's routeLineSegment
            route.routeLine.findMatchingLineSegments(this);

            route.debugCheckMatchIndexIntegrity("matched LineSegments");
        }

        //update the route's stop proximity matches to include the match info on the OSM ways
        final long timeStartStopMatching = new Date().getTime();
        stopConflator.matchStopsToWays(this);
        stopConflator.createStopPositionsForPlatforms(this);
        System.out.println("Matched stops in " + (new Date().getTime() - timeStartStopMatching) + "ms");

        for(final Route route : exportRoutes) {
            route.debugCheckMatchIndexIntegrity("stops matched");
        }

        //with the candidate lines determined, begin the pathfinding stage to lock down the path between the route's stops
        int successfullyMatchedRoutes = 0;
        for(final Route route : exportRoutes) {
            final Date t0 = new Date();
            System.out.format("INFO: Begin PathFinding for subroute “%s” (tripMarker %s)\n", route.routeRelation.getTag(OSMEntity.KEY_NAME), route.tripMarker);
            if (debugTripMarker != null && !route.tripMarker.equals(debugTripMarker)) {
                System.out.println("skipping (not a flagged route)");
                continue;
            }

            if(debugEnabled) {
                try {
                    route.debugOutputSegments(workingEntitySpace);
                } catch (IOException | InvalidArgumentException e) {
                    e.printStackTrace();
                }
            }

            //run the pathfinding algorithm for each route
            route.findRoutePaths(this);

            //flush the match indexes for the routeLine, since they're no longer needed
            route.routeLine.flushMatchIndexes();
            //route.debugCheckMatchIndexIntegrity("match indexes flushed");
            System.out.format("INFO: paths found in %dms\n", new Date().getTime() - t0.getTime());

            //and add the stops data to the OSMRelation for the route
            route.syncStopsWithRelation();

            //split any ways that aren't fully contained by the route path
            route.routePathFinder.splitWaysAtIntersections(workingEntitySpace);

            route.debugCheckMatchIndexIntegrity("route ways split");

            //debug paths
            if(debugEnabled) {
                System.out.println("--------------------------------------------------------\nFinal Paths for " + route.routePathFinder.route.routeRelation.osm_id + ":");
                for (final PathTree pathTree : route.routePathFinder.routePathTrees) {
                    System.out.println("PATH: " + pathTree.bestPath);
                }
            }

            //and finally, add the ways associated with the routeFinder's best path to the OSM route relation
            route.routePathFinder.addWaysToRouteRelation();

            if(debugEnabled) {
                try {
                    workingEntitySpace.outputXml(Config.sharedInstance.debugDirectory + "/newresult" + route.routeRelation.osm_id + ".osm");
                    route.routePathFinder.debugOutputPaths(workingEntitySpace);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            //return TRUE if all patchs were successful
            boolean routeSuccessful = route.routePathFinder.getSuccessfulPaths() == route.routePathFinder.routePathTrees.size();
            if(routeSuccessful) {
                successfullyMatchedRoutes++;
            }
        }
        return successfullyMatchedRoutes == exportRoutes.size();
    }
    public Collection<StopArea> getAllRouteStops() {
        return allRouteStops.values();
    }
    public OSMRelation getExportRouteMaster() {
        return exportRouteMaster;
    }
    public List<Route> getExportRoutes() {
        return exportRoutes;
    }
    public RouteDataManager getWorkingEntitySpace() {
        return workingEntitySpace;
    }
}
