package Conflation;

import NewPathFinding.PathTree;
import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.*;

/**
 * Tracks and processes a route_master-type OSM relation
 * Created by nick on 1/27/16.
 */
public class RouteConflator implements WaySegmentsObserver {
    public static class LineComparisonOptions {
        public double maxSegmentLength = 5.0, maxSegmentOrthogonalDistance = 10.0, maxSegmentMidPointDistance = 10.0, segmentSearchBoxSize = 30.0;
        private double minSegmentDotProduct;
        public void setMaxSegmentAngle(final double angle) {
            minSegmentDotProduct = Math.cos(angle * Math.PI / 180.0);
        }
        public double getMinSegmentDotProduct() {
            return minSegmentDotProduct;
        }
        public LineComparisonOptions() {
            minSegmentDotProduct = 0.866; //default to 30 degrees
        }
    }
    public final static String GTFS_AGENCY_ID = "gtfs:agency_id", GTFS_ROUTE_ID = "gtfs:route_id", GTFS_TRIP_ID = "gtfs:trip_id", GTFS_TRIP_MARKER = "gtfs:trip_marker", GTFS_IGNORE = "gtfs:ignore";

    /**
     * Class used for indexing OSMWays in the downloaded region into subregions, for more efficient
     * bounding box checks
     */
    protected static class Cell {
        public final static List<Cell> allCells = new ArrayList<>(128);
        public final static double cellSizeInMeters = 500.0;
        private static double cellSize;
        private static double searchBuffer = 0.0;

        private static void initCellsForBounds(final Region bounds) {
            //wipe any existing cells (i.e. from a previous run)
            allCells.clear();

            //and prepare the region, including a buffer zone equal to the greatest of the various search/bounding box dimensions
            cellSize = SphericalMercator.metersToCoordDelta(cellSizeInMeters, bounds.getCentroid().y);
            searchBuffer = -SphericalMercator.metersToCoordDelta(Math.max(RouteConflator.wayMatchingOptions.segmentSearchBoxSize, Math.max(StopArea.duplicateStopPlatformBoundingBoxSize, StopArea.waySearchAreaBoundingBoxSize)), bounds.getCentroid().y);

            //generate the cells needed to fill the entire bounds (plus the searchBuffer)
            final Region baseCellRegion = bounds.regionInset(searchBuffer, searchBuffer);
            for(double y = baseCellRegion.origin.y; y <= baseCellRegion.extent.y; y += Cell.cellSize) {
                for(double x = baseCellRegion.origin.x; x <= baseCellRegion.extent.x; x += Cell.cellSize) {
                    createCellForPoint(new Point(x, y));
                }
            }
        }
        private static Cell createCellForPoint(final Point point) {
            Cell cell = new Cell(point);
            allCells.add(cell);
            return cell;
        }

        public final Region boundingBox, expandedBoundingBox;
        public final List<OSMWaySegments> containedWays = new ArrayList<>(1024); //contains OSM ways only
        public final List<StopArea> containedStops = new ArrayList<>(64); //contains stop platforms only

        private Cell(final Point origin) {
            boundingBox = new Region(origin, new Point(origin.x + cellSize, origin.y + cellSize));
            expandedBoundingBox = boundingBox.regionInset(searchBuffer, searchBuffer);
        }
        public void addWay(final OSMWaySegments entity) {
            if(!containedWays.contains(entity)) {
                containedWays.add(entity);
            }
        }
        public void addStop(final StopArea stop) {
            if(!containedStops.contains(stop)) {
                containedStops.add(stop);
            }
        }
        @Override
        public String toString() {
            return String.format("Cell %s (%d ways, %d stops)", boundingBox, containedWays.size(), containedStops.size());
        }
    }

    public final String routeType;
    private final OSMRelation importRouteMaster;
    private final List<Route> importRoutes;
    private OSMRelation exportRouteMaster;
    private List<Route> exportRoutes;
    private HashMap<Long, StopArea> allRouteStops;
    public static boolean debugEnabled = false;
    public final long debugRouteId = -261383L*0L;
    private OSMEntitySpace workingEntitySpace = null;
    public final static LineComparisonOptions wayMatchingOptions = new LineComparisonOptions();
    protected HashMap<Long, OSMWaySegments> candidateLines = null;
    public final Map<String, List<String>> allowedRouteTags, allowedPlatformTags;

    public RouteConflator(final OSMRelation routeMaster) throws InvalidArgumentException {
        importRouteMaster = routeMaster;
        routeType = importRouteMaster.getTag(OSMEntity.KEY_ROUTE_MASTER);
        if(!validateRouteType(routeType)) {
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
                routeStops.add(new StopArea(stopMember.member, null));
            }

            importRoutes.add(new Route(subRoute, routePath, routeStops, wayMatchingOptions));
        }
    }
    public static boolean validateRouteType(final String routeType) {
        if(routeType == null) {
            return false;
        }
        switch (routeType) {
            case OSMEntity.TAG_BUS:
            case OSMEntity.TAG_LIGHT_RAIL:
            case OSMEntity.TAG_TRAIN:
            case OSMEntity.TAG_SUBWAY:
            case OSMEntity.TAG_TRAM:
            case OSMEntity.TAG_FERRY:
                return true;
            default:
                return false;
        }
    }

    /**
     * Define the search tags for the ways for the given route type
     * @param relationType
     * @return
     */
    public static Map<String, List<String>> wayTagsForRouteType(final String relationType) {
        String[] keys;
        String[][] tags;
        switch (relationType) {
            case OSMEntity.TAG_BUS:
                keys = new String[]{"highway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "residential", "unclassified", "service", "living_street"};
                break;
            case OSMEntity.TAG_LIGHT_RAIL:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "subway", "tram"};
                break;
            case OSMEntity.TAG_SUBWAY:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "subway"};
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
     * Define the search tags for the platforms for the given route type
     * @param relationType
     * @return
     */
    public Map<String, List<String>> platformTagsForRouteType(final String relationType) {
        String[] keys;
        String[][] tags;
        switch (relationType) {
            case OSMEntity.TAG_BUS:
                keys = new String[]{"highway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"bus_stop", "bus_station"};
                break;
            case OSMEntity.TAG_LIGHT_RAIL:
            case OSMEntity.TAG_SUBWAY:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"platform"};
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
    public boolean downloadRegionsForImportDataset(final OSMEntitySpace intoEntitySpace) throws InvalidArgumentException {
        if(importRoutes.size() == 0) {
            System.out.println("No routes for Master " + importRouteMaster.getTag("name"));
            return false;
        }

        workingEntitySpace = intoEntitySpace;
        workingEntitySpace.name = "WORK";

        //get a handle on the route path geometries for the subroutes
        final List<OSMWay> routePaths = new ArrayList<>(importRoutes.size());
        Region routePathsBoundingBox = null;
        for(final Route route : importRoutes) {
            if(route.routeType != null && route.routeType.equals(OSMEntity.TAG_ROUTE)) {
                routePaths.add(route.routeLine.way);
                if(routePathsBoundingBox == null) {
                    routePathsBoundingBox = new Region(route.routeLine.way.getBoundingBox());
                } else {
                    routePathsBoundingBox = Region.union(routePathsBoundingBox, route.routeLine.way.getBoundingBox());
                }
            }
        }

        //and get the download regions for them
        final double boundingBoxSize = SphericalMercator.metersToCoordDelta(wayMatchingOptions.segmentSearchBoxSize, routePathsBoundingBox.getCentroid().y);
        final List<Region> downloadRegions = generateCombinedDownloadRegions(routePaths, boundingBoxSize);

        //fetch all possible useful ways that intersect the route's combined bounding box
        final String queryFormat = "[\"%s\"~\"%s\"]";
        int downloadIdx = 0;
        for(final Region downloadRegion : downloadRegions) {
            final OverpassConverter converter = new OverpassConverter();

            //TODO: works fine for now, but if a route requires multiple search *keys*, will create excessive queries
            for(final Map.Entry<String,List<String>> searchKeys : allowedRouteTags.entrySet()) {
                final String query = converter.queryForBoundingBox(String.format(queryFormat, searchKeys.getKey(), String.join("|", searchKeys.getValue())), downloadRegion, 0.0, OSMEntity.OSMType.way);
                converter.fetchFromOverpass(query);
                converter.getEntitySpace().name = "download_" + importRouteMaster.getTag(OSMEntity.KEY_REF) + "_" + downloadIdx++;
                try {
                    converter.getEntitySpace().outputXml("./cache/" + converter.getEntitySpace().name + ".osm");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                workingEntitySpace.mergeWithSpace(converter.getEntitySpace(), OSMEntity.TagMergeStrategy.keepTags, null);
            }
        }

        //also fetch all route relations of the appropriate type, along with any parent route_master relations in the area
        final OverpassConverter relationConverter = new OverpassConverter();
        final LatLonRegion routePathsRegionLL = SphericalMercator.mercatorToLatLon(routePathsBoundingBox);
        final String relationQueryComponents[] = {
                String.format("relation[\"type\"=\"route\"][\"route\"=\"%s\"](%.07f,%.07f,%.07f,%.07f)", routeType, routePathsRegionLL.origin.latitude, routePathsRegionLL.origin.longitude, routePathsRegionLL.extent.latitude, routePathsRegionLL.extent.longitude),
        };
        final String relationQuery = "(" + String.join(";", relationQueryComponents) + ";rel(br););";
        relationConverter.fetchFromOverpass(relationQuery);
        workingEntitySpace.mergeWithSpace(relationConverter.getEntitySpace(), OSMEntity.TagMergeStrategy.keepTags, null);

        System.out.format("INFO: Begin processing with entity space: %s\n", workingEntitySpace);

        if (debugEnabled) {
            for (final Region r : downloadRegions) {
                List<OSMNode> rNodes = new ArrayList<>(5);
                rNodes.add(workingEntitySpace.createNode(r.origin.x, r.origin.y, null));
                rNodes.add(workingEntitySpace.createNode(r.extent.x, r.origin.y, null));
                rNodes.add(workingEntitySpace.createNode(r.extent.x, r.extent.y, null));
                rNodes.add(workingEntitySpace.createNode(r.origin.x, r.extent.y, null));
                rNodes.add(workingEntitySpace.createNode(r.origin.x, r.origin.y, null));
                final OSMWay regionWay = workingEntitySpace.createWay(null, rNodes);
                regionWay.setTag("landuse", "construction");
                regionWay.setTag(GTFS_IGNORE, "yes");
                //System.out.println(r.toString());
            }
        }
        workingEntitySpace.markAllEntitiesWithAction(OSMEntity.ChangeAction.none);

        //now that the data is downloaded, create the necessary OSM relations in the new working entity space:
        //create a list of all routes and route_masters in the working entity space
        final List<OSMRelation> existingRouteMasters = new ArrayList<>(workingEntitySpace.allRelations.size()), existingRoutes = new ArrayList<>(workingEntitySpace.allRelations.size());
        for(final OSMRelation relation : workingEntitySpace.allRelations.values()) {
            if (OSMEntity.TAG_ROUTE_MASTER.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_TYPE))) {
                existingRouteMasters.add(relation);
            } else if (OSMEntity.TAG_ROUTE.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_TYPE))) {
                existingRoutes.add(relation);
            }
        }
        //using the above list, conflate the GTFS import routes/trips with the existing OSM route_master/route entities
        final List<OSMRelation> existingSubRoutes = conflateExistingRouteMaster(existingRouteMasters);
        conflateExistingRouteRelations(existingRoutes, existingSubRoutes);

        //create the Cell index for all the ways, for faster lookup below
        Cell.initCellsForBounds(routePathsBoundingBox);

        //create OSMWaySegments objects for all downloaded ways
        candidateLines = new HashMap<>(workingEntitySpace.allWays.size());
        for(final OSMWay way : workingEntitySpace.allWays.values()) {
            //only include completely-downloaded ways, with all their nodes present and complete
            if (!way.areAllNodesComplete()) {
                workingEntitySpace.purgeEntity(way);
                continue;
            }
            if (way.isComplete()) {
                final OSMWaySegments line = new OSMWaySegments(way, wayMatchingOptions.maxSegmentLength);
                candidateLines.put(way.osm_id, line);
                line.addObserver(this);

                for (final Cell cell : Cell.allCells) {
                    if (Region.intersects(cell.boundingBox, way.getBoundingBox())) {
                        cell.addWay(line);
                    }
                }
            }
        }

        /*int linesIndexed = 0;
        for(final Cell cell : Cell.allCells.values()) {
            linesIndexed += cell.containedWays.size();
        }
        System.out.println(candidateLines.size() + " lines, " + linesIndexed + " indexed");//*/

        return true;
    }

    /**
     * Conflates the GTFS route object with any existing OSM route_master relations, based on the GTFS route id and/or ref tag
     * @param existingRouteMasters A list of all existing route_master relations in workingEntitySpace
     * @return a list of child route relations belonging to the matched route_master relation (will be blank if a new route_master was created)
     */
    private List<OSMRelation> conflateExistingRouteMaster(final List<OSMRelation> existingRouteMasters) {
        //scan the route_masters list for route_masters matching the import route
        final String importGtfsRouteId = importRouteMaster.getTag(GTFS_ROUTE_ID), importRouteRef = importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteAgencyId = importRouteMaster.getTag(GTFS_AGENCY_ID);
        for(final OSMRelation relation : existingRouteMasters) {
            //break on the first GTFS id match
            if(importGtfsRouteId != null && importGtfsRouteId.equals(relation.getTag(GTFS_ROUTE_ID)) && importRouteAgencyId != null && importRouteAgencyId.equals(relation.getTag(GTFS_AGENCY_ID))) {
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
    private void conflateExistingRouteRelations(final List<OSMRelation> existingRoutes, final List<OSMRelation> existingSubRoutes) {
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
            //first try to match up the import route (trip) with the matching routes, using the GTFS trip_marker
            OSMRelation existingRouteRelation = null;
            ListIterator<OSMRelation> matchingRoutesIterator = matchingRoutes.listIterator();
            while(matchingRoutesIterator.hasNext()) {
                final OSMRelation relation = matchingRoutesIterator.next();

                //break on the first GTFS id match
                System.out.println("check TRIP " + importRoute.tripMarker + " vs " + relation.getTag(GTFS_TRIP_MARKER));
                if(importRoute.tripMarker != null && importRoute.tripMarker.equals(relation.getTag(GTFS_TRIP_MARKER)) && importRouteAgencyId != null && importRouteAgencyId.equals(relation.getTag(GTFS_AGENCY_ID))) {
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
            final ArrayList<StopArea> exportRouteStops = new ArrayList<>(importRoute.stops.size());
            StopArea existingStop;
            OSMEntity newStopPlatform;
            OSMNode newStopPosition;
            for(final StopArea stop : importRoute.stops) {
                existingStop = allRouteStops.get(stop.getPlatform().osm_id);

                if(existingStop == null) {
                    newStopPlatform = workingEntitySpace.addEntity(stop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null);
                    if (stop.getStopPosition() != null) {
                        newStopPosition = (OSMNode) workingEntitySpace.addEntity(stop.getStopPosition(), OSMEntity.TagMergeStrategy.keepTags, null);
                    } else {
                        newStopPosition = null;
                    }
                    existingStop = new StopArea(newStopPlatform, newStopPosition);
                    allRouteStops.put(stop.getPlatform().osm_id, existingStop);
                }
                exportRouteStops.add(existingStop);
            }

            //and add the stops to the existing route relation
            final Route exportRoute;
            if(existingRouteRelation != null) {
                //copy over the tags from the GTFS trip object
                existingRouteRelation.copyTagsFrom(importRoute.routeRelation, OSMEntity.TagMergeStrategy.copyTags);

                //remove all existing members from the route relation - will be substituted with matched data later
                final ListIterator<OSMRelation.OSMRelationMember> memberListIterator = existingRouteRelation.getMembers().listIterator();
                while(memberListIterator.hasNext()) {
                    memberListIterator.next();
                    memberListIterator.remove();
                }
                final OSMWay importRoutePath = (OSMWay) importRoute.routeRelation.getMembers("").get(0).member;
                final OSMRelation exportRouteRelation = (OSMRelation) workingEntitySpace.addEntity(existingRouteRelation, OSMEntity.TagMergeStrategy.copyTags, null);
                exportRoute = new Route(exportRouteRelation, importRoutePath, exportRouteStops, wayMatchingOptions);
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
     * Generates a set of rectangular regions which roughly corresponds with the download area for the given ways
     * TODO: improve algorithm to reduce overlap etc
     * @param ways
     * @param expansionAmount - the amount to expand the download regions, in meters
     * @return
     */
    private static List<Region> generateCombinedDownloadRegions(final List<OSMWay> ways, final double expansionAmount) {
        final List<Region> combinedRegions = new ArrayList<>(ways.size() * 8);
        for(final OSMWay way : ways) {
            combinedRegions.addAll(generateDownloadRegionsForWay(way, expansionAmount));
        }

        //combine significantly-overlapping regions
        final double minAreaOverlap = 0.8;
        final List<Region> downloadRegions = new ArrayList<>(combinedRegions.size()), processedRegions = new ArrayList<>(combinedRegions.size());
        for(final Region region1 : combinedRegions) {
            if(processedRegions.contains(region1)) { //skip if this region was de-duplicated in a previous pass
                continue;
            }

            final double region1Area = region1.area();
            boolean didCombine = false;
            for(final Region region2 : combinedRegions) {
                if(region1 == region2) {
                    continue;
                }

                //if region1 contains region2, just use region1
                if(Region.contains(region1, region2)) {
                    downloadRegions.add(region1);
                    processedRegions.add(region2);
                    didCombine = true;
                    break;
                }

                //check if the regions significantly overlap
                final Region intersectionRegion = Region.intersection(region1, region2);
                if(intersectionRegion != null) {
                    final double intersectionArea = intersectionRegion.area();
                    if (intersectionArea / region1Area >= minAreaOverlap && intersectionArea / region2.area() >= minAreaOverlap) {
                        //if so, add the union of the two regions
                        final Region unionRegion = Region.union(region1, region2);
                        //System.out.println("OVERLAPPED " + region1 + " and " + region2 + ", union: " + unionRegion);
                        downloadRegions.add(unionRegion);
                        processedRegions.add(region2);
                        didCombine = true;
                        break;
                    }
                }
            }

            //if the region wasn't processed in the last pass, it's a standalone region.  Add it
            if(!didCombine) {
                downloadRegions.add(region1);
            }
        }
        return downloadRegions;
    }

    private static List<Region> generateDownloadRegionsForWay(final OSMWay way, final double boundingBoxSize) {
        final double maxRectArea = 5000000.0;
        final List<Region> regions = new ArrayList<>((int) Math.ceil(way.getBoundingBox().area() / maxRectArea));
        final OSMNode firstNodeInWay = way.getFirstNode(), lastNodeInWay = way.getLastNode();
        OSMNode lastNode = null;
        Region curRegion = null;
        boolean virginRegion = true;
        for(final OSMNode node : way.getNodes()) {
            if(node == firstNodeInWay) { //don't until we get 2+ nodes
                lastNode = node;
                continue;
            }

            if(virginRegion) { //init the region
                assert lastNode != null;
                final Point points[] = {lastNode.getCentroid(), node.getCentroid()};
                curRegion = new Region(points);
            }

            //create a copy of the region to test its properties
            Region testRegion = new Region(curRegion);
            if(!virginRegion) {
                testRegion.includePoint(node.getCentroid());
            }
            testRegion = testRegion.regionInset(-boundingBoxSize, -boundingBoxSize);

            //check if the test region's area is within the max value
            if(node == lastNodeInWay || testRegion.area() > maxRectArea) { //if not
                regions.add(testRegion); //add to the regions list

                //and create a new region with the last and current nodes
                final Point points[] = {lastNode.getCentroid(), node.getCentroid()};
                curRegion = new Region(points);
            } else { //otherwise, just include the current point
                curRegion.includePoint(node.getCentroid());
            }

            virginRegion = false;
            lastNode = node;
        }
        return regions;
    }
    public void conflateRoutePaths(final StopConflator stopConflator) {
        for(final Route route : exportRoutes) {
            System.out.println("Begin conflation for subroute \"" + route.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + route.routeRelation.osm_id + ")");
            if (debugRouteId != 0 && route.routeRelation.osm_id != debugRouteId) {
                System.out.println("skipping (not a flagged route)");
                continue;
            }

            //get a handle on the WaySegments that geographically match the route's routeLineSegment
            route.routeLine.findMatchingLineSegments(this);

            route.debugCheckMatchIndexIntegrity();
        }

        //update the route's stop proximity matches to include the match info on the OSM ways
        final long timeStartStopMatching = new Date().getTime();
        stopConflator.matchStopsToWays();
        stopConflator.createStopPositionsForPlatforms(workingEntitySpace);
        System.out.println("Matched stops in " + (new Date().getTime() - timeStartStopMatching) + "ms");

        for(final Route route : exportRoutes) {
            route.debugCheckMatchIndexIntegrity();
        }

        //TODO: debug bail
        /*if(Math.random() < 2)
            return;//*/

        //with the candidate lines determined, begin the pathfinding stage to lock down the path between the route's stops
        for(final Route route : exportRoutes) {
            System.out.println("Begin PathFinding for subroute \"" + route.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + route.routeRelation.osm_id + ")");
            if (debugRouteId != 0 && route.routeRelation.osm_id != debugRouteId) {
                System.out.println("skipping (not a flagged route)");
                continue;
            }

            //run the pathfinding algorithm for each route
            route.findRoutePaths(this);

            route.debugCheckMatchIndexIntegrity();

            //and add the stops data to the OSMRelation for the route
            route.syncStopsWithRelation();

            if(debugEnabled) {
                System.out.println("--------------------------------------------------------\nPRE-SPLIT Paths for " + route.routePathFinder.route.routeRelation.osm_id + ":");
                for (final PathTree pathTree : route.routePathFinder.routePathTrees) {
                    System.out.println("PATH: " + pathTree.bestPath);
                }
            }

            //split any ways that aren't fully contained by the route path
            route.routePathFinder.splitWaysAtIntersections(workingEntitySpace);

            //debug paths
            if(debugEnabled) {
                System.out.println("--------------------------------------------------------\nFinal Paths for " + route.routePathFinder.route.routeRelation.osm_id + ":");
                for (final PathTree pathTree : route.routePathFinder.routePathTrees) {
                    System.out.println("PATH: " + pathTree.bestPath);
                }
            }

            //and finally, add the ways associated with the routeFinder's best path to the OSM route relation
            route.routePathFinder.addWaysToRouteRelation();

            if(true||debugEnabled) {
                try {
                    workingEntitySpace.outputXml("newresult" + route.routeRelation.osm_id + ".osm");
                    route.debugOutputSegments(workingEntitySpace, candidateLines.values());
                    route.routePathFinder.debugOutputPaths(workingEntitySpace);
                } catch (IOException | InvalidArgumentException e) {
                    e.printStackTrace();
                }
            }
        }
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
    public OSMEntitySpace getWorkingEntitySpace() {
        return workingEntitySpace;
    }
    public HashMap<Long, OSMWaySegments> getCandidateLines() {
        return candidateLines;
    }

    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
        for(final WaySegments ws : splitWaySegments) {
            if(ws != originalWaySegments) {
                candidateLines.put(ws.way.osm_id, (OSMWaySegments) ws);
            }
        }
    }
    @Override
    public void waySegmentsWasDeleted(final WaySegments waySegments) {
        candidateLines.remove(waySegments.way.osm_id);
    }
    @Override
    public void waySegmentsAddedSegment(final WaySegments waySegments, final LineSegment oldSegment, final LineSegment[] newSegments) {

    }
}
