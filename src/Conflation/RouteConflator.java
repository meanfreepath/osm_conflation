package Conflation;

import NewPathFinding.PathTree;
import OSM.*;
import com.company.Config;
import com.sun.istack.internal.NotNull;
import com.company.InvalidArgumentException;

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
    public final static String GTFS_DATASET_ID = "gtfs:dataset_id", GTFS_AGENCY_ID = "gtfs:agency_id", GTFS_ROUTE_ID = "gtfs:route_id", GTFS_TRIP_ID = "gtfs:trip_id", GTFS_TRIP_MARKER = "gtfs:trip_marker", GTFS_STOP_ID = "gtfs:stop_id", GTFS_IGNORE = "gtfs:ignore";

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

    /**
     * Global list of ALL stops on this processing run
     */
    protected static Map<String, StopArea> allStops = new HashMap<>(256);
    public static List<RouteConflator> allConflators = new ArrayList<>(16);

    @NotNull
    public final RouteType routeType;
    public final String gtfsRouteId;
    private final OSMRelation importRouteMaster;
    protected final List<Route> importRoutes;
    private OSMRelation exportRouteMaster;
    private List<Route> exportRoutes;
    private final HashMap<String, StopArea> allRouteStops; //represents all the stops on the route_master
    public static boolean debugEnabled = false;
    public final String debugTripMarker = null;//"10673026:1";
    private RouteDataManager workingEntitySpace = null;
    public final LineComparisonOptions wayMatchingOptions;
    public final Map<String, List<String>> allowedRouteTags, allowedPlatformTags;

    /**
     * Init RouteConflator objects for the given OSM route relations
     * @param importRouteMasterRelations - the relations we'd like to import
     * @param routeDataManager - the working entity space for the RouteConflators
     * @param matchingOptions
     * @throws InvalidArgumentException
     */
    public static void createConflatorsForRouteMasters(final List<OSMRelation> importRouteMasterRelations, final RouteDataManager routeDataManager, final LineComparisonOptions matchingOptions) throws InvalidArgumentException {
        //first ensure the routes are all of the same type
        String routeType = null;
        for (final OSMRelation routeMasterRelation : importRouteMasterRelations) {
            if (routeType == null) {
                routeType = routeMasterRelation.getTag(OSMEntity.KEY_ROUTE);
            } else if (!routeType.equals(routeMasterRelation.getTag(OSMEntity.KEY_ROUTE))) {
                throw new InvalidArgumentException("All routes must be of the same routeType");
            }
        }

        for (final OSMRelation importRouteMaster : importRouteMasterRelations) {
            System.out.format("Processing route “%s” (ref %s, GTFS id %s), %d trips…\n", importRouteMaster.getTag(OSMEntity.KEY_NAME), importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID), importRouteMaster.getMembers().size());
            //create an object to handle the processing of the data for this route_master
            allConflators.add(new RouteConflator(importRouteMaster, routeDataManager, matchingOptions));
        }
    }

    /**
     * Generates a simple list of the GTFS route ids for all the route masters
     * @return the GTFS route ids
     */
    public static List<String> getRouteMasterIds() {
        final List<String> routeIds = new ArrayList<>(allConflators.size());
        for(final RouteConflator routeConflator : allConflators) {
            routeIds.add(routeConflator.gtfsRouteId);
        }
        return routeIds;
    }

    public RouteConflator(final OSMRelation routeMaster, final RouteDataManager dataManager, LineComparisonOptions lineComparisonOptions) throws InvalidArgumentException {
        importRouteMaster = routeMaster;
        this.workingEntitySpace = dataManager;
        wayMatchingOptions = lineComparisonOptions;
        routeType = RouteType.fromString(routeMaster.getTag(OSMEntity.KEY_ROUTE_MASTER));
        gtfsRouteId = routeMaster.getTag(GTFS_ROUTE_ID);
        if(routeType == null) {
            throw new InvalidArgumentException("Invalid route type provided");
        }

        allowedRouteTags = wayTagsForRouteType(routeType);
        allowedPlatformTags = platformTagsForRouteType(routeType);

        //generate the list of subroutes to process
        final List<OSMRelation.OSMRelationMember> subRoutes = routeMaster.getMembers();
        importRoutes = new ArrayList<>(subRoutes.size());
        allRouteStops = new HashMap<>(subRoutes.size() * 64);
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
            OSMNode importPlatform;
            for(final OSMRelation.OSMRelationMember stopMember : subRoute.getMembers(OSMEntity.TAG_PLATFORM)) {
                //check if the stop has already got a global representation
                final String gtfsStopId = stopMember.member.getTag(GTFS_STOP_ID);
                StopArea stopArea = allStops.get(gtfsStopId);
                if(stopArea == null) {
                    //add the import platform node to the working entity space (may be replaced later when conflating stops with existing data)
                    importPlatform = (OSMNode) dataManager.addEntity(stopMember.member, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                    stopArea = new StopArea(importPlatform);
                    allStops.put(gtfsStopId, stopArea);
                }

                //and add to the route's list
                routeStops.add(stopArea);

                //and the route_master's list
                allRouteStops.put(gtfsStopId, stopArea);
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
     * @param allExistingRoutes - a list of all type=route relations in workingEntitySpace
     * @param existingSubRoutes - a list of the existing OSM routes that belong to the existing route_master relation (if any - will be empty if a new route_master was created)
     */
    protected void conflateExistingRouteRelations(final List<OSMRelation> allExistingRoutes, final List<OSMRelation> existingSubRoutes) {
        //first compile a list of existing routes to consider for conflation
        final String importRouteAgencyId = exportRouteMaster.getTag(GTFS_AGENCY_ID), importRouteMasterRef = exportRouteMaster.getTag(OSMEntity.KEY_REF);
        final List<OSMRelation> existingRouteCandidates;
        if(existingSubRoutes.size() > 0) { //if there's an existing route_master, use its child routes as the list
            existingRouteCandidates = existingSubRoutes;
        } else { //if there was no existing route_master, or it had no child routes, create a list of existing routes based on ref
            existingRouteCandidates = new ArrayList<>();
            for(final OSMRelation relation : allExistingRoutes) {
                if(importRouteMasterRef != null && importRouteMasterRef.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_REF))) {
                    existingRouteCandidates.add(relation);
                }
            }
        }

        //now iterate over the GTFS import routes (trips), matching them to the existing routes (or creating them if no match made)
        exportRoutes = new ArrayList<>(importRoutes.size());

        //simple class to make it easier to track import/existing route matches
        class RouteMatch {
            private final Route importRoute;
            private final OSMRelation matchingRelation;
            private final String matchType;
            private RouteMatch(Route route, OSMRelation relation, String type) {
                importRoute = route;
                matchingRelation = relation;
                matchType = type;
            }
        }

        //first try to match based on the TRIP_MARKER tag
        List<Route> unmatchedImportRoutes = new ArrayList<>(importRoutes);
        List<RouteMatch>matchedImportRoutes = new ArrayList<>(importRoutes.size());
        ListIterator<Route> unmatchedImportRoutesIterator = unmatchedImportRoutes.listIterator();
        while(unmatchedImportRoutesIterator.hasNext()) {
            final Route importRoute = unmatchedImportRoutesIterator.next();
            //System.out.println("CHECK subroute \"" + importRoute.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + importRoute.routeRelation.osm_id + ")");
            //first try to match up the import route (trip) with the matching routes, using the GTFS trip_marker
            ListIterator<OSMRelation> existingRouteCandidatesIterator = existingRouteCandidates.listIterator();
            while (existingRouteCandidatesIterator.hasNext()) {
                final OSMRelation relation = existingRouteCandidatesIterator.next();

                //break on the first TRIP_MARKER id match
                //System.out.println("check TRIP " + importRoute.tripMarker + " vs " + relation.getTag(GTFS_TRIP_MARKER));
                if (importRoute.tripMarker != null && importRoute.tripMarker.equals(relation.getTag(GTFS_TRIP_MARKER))) {
                    System.out.format("INFO: Matched trip to existing relation #%d using %s tag\n", relation.osm_id, GTFS_TRIP_MARKER);
                    existingRouteCandidatesIterator.remove();
                    unmatchedImportRoutesIterator.remove();
                    matchedImportRoutes.add(new RouteMatch(importRoute, relation, "marker"));
                    break;
                }
            }
        }

        //if nothing was found using the GTFS trip_marker tag, select one of the matching routes as the one to use
        //NOTE: this will overwrite any route with the same ref - may be a problem in areas where multiple agencies use the same route numbers!
        unmatchedImportRoutesIterator = unmatchedImportRoutes.listIterator();
        ListIterator<OSMRelation> existingRouteCandidatesIterator = existingRouteCandidates.listIterator();
        while(unmatchedImportRoutesIterator.hasNext()) {
            final Route importRoute = unmatchedImportRoutesIterator.next();
            if (existingRouteCandidatesIterator.hasNext()) {
                final OSMRelation existingRelation = existingRouteCandidatesIterator.next();
                existingRouteCandidatesIterator.remove();
                unmatchedImportRoutesIterator.remove();
                matchedImportRoutes.add(new RouteMatch(importRoute, existingRelation, "filler"));
                System.out.format("INFO: no matches for trip based on GTFS id: using first existing route instead: %s\n", existingRelation);
            } else {
                break;
            }
        }

        //and create brand new route relations for any leftover routes
        while(unmatchedImportRoutesIterator.hasNext()) {
            final Route importRoute = unmatchedImportRoutesIterator.next();
            final Route exportRoute = new Route(importRoute, importRoute.stops, workingEntitySpace);
            workingEntitySpace.addEntity(exportRoute.routeRelation, OSMEntity.TagMergeStrategy.copyTags, null, true, 0);
            exportRoutes.add(exportRoute);
            exportRouteMaster.addMember(exportRoute.routeRelation, OSMEntity.MEMBERSHIP_DEFAULT);
        }

        /*if the existing route_master has any leftover routes that weren't matched above, they were most likely
          canceled by the transit agency - delete them from the entity space (and from OSM)*/
        existingRouteCandidatesIterator = existingRouteCandidates.listIterator();
        while(existingRouteCandidatesIterator.hasNext()) {
            final OSMRelation leftoverRoute = existingRouteCandidatesIterator.next();
            final OSMRelation deleteRouteRelation = (OSMRelation) workingEntitySpace.addEntity(leftoverRoute, OSMEntity.TagMergeStrategy.copyTags, null, true, 0);
            workingEntitySpace.deleteEntity(deleteRouteRelation);
            System.out.format("INFO: Deleting unneeded existing relation %s\n", deleteRouteRelation);
        }

        //now iterate over the matched existing routes
        for(final RouteMatch routeMatch : matchedImportRoutes) {
            //copy over the GTFS-specific tags from the new GTFS trip object
            if(routeMatch.matchType.equals("marker")) {
                routeMatch.matchingRelation.setTag(GTFS_AGENCY_ID, routeMatch.importRoute.routeRelation.getTag(GTFS_AGENCY_ID));
                routeMatch.matchingRelation.setTag(GTFS_DATASET_ID, routeMatch.importRoute.routeRelation.getTag(GTFS_DATASET_ID));
                routeMatch.matchingRelation.setTag(GTFS_TRIP_ID, routeMatch.importRoute.routeRelation.getTag(GTFS_TRIP_ID));
                routeMatch.matchingRelation.setTag(GTFS_TRIP_MARKER, routeMatch.importRoute.routeRelation.getTag(GTFS_TRIP_MARKER));
            } else {
                routeMatch.matchingRelation.copyTagsFrom(routeMatch.importRoute.routeRelation, OSMEntity.TagMergeStrategy.copyTags);
            }

            //remove all existing members from the existing route relation - will be substituted with matched data later
            final List<OSMRelation.OSMRelationMember> existingRouteMembers = new ArrayList<>(routeMatch.matchingRelation.getMembers());
            for(final OSMRelation.OSMRelationMember member : existingRouteMembers) {
                routeMatch.matchingRelation.removeMember(member.member, Integer.MAX_VALUE);
            }
            //add the routePath from the import route
            final OSMWay importRoutePath = (OSMWay) routeMatch.importRoute.routeRelation.getMembers("").get(0).member;

            //and add the existing relation to the working space, and set up the local variables to track it
            final OSMRelation exportRouteRelation = (OSMRelation) workingEntitySpace.addEntity(routeMatch.matchingRelation, OSMEntity.TagMergeStrategy.copyTags, null, true, 0);
            final Route exportRoute = new Route(exportRouteRelation, importRoutePath, routeMatch.importRoute.stops, this);
            exportRoutes.add(exportRoute);
            exportRouteMaster.addMember(exportRoute.routeRelation, OSMEntity.MEMBERSHIP_DEFAULT);
        }

    }
    public boolean conflateRoutePaths(final StopConflator stopConflator) {
        for(final Route route : exportRoutes) {
            System.out.format("INFO: Begin conflation for subroute “%s” (tripMarker %s, routeLine way local id %d)\n", route.routeRelation.getTag(OSMEntity.KEY_NAME), route.routeRelation.getTag(GTFS_TRIP_MARKER), route.routeLine.way.osm_id);
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
