package Conflation;

import OSM.*;
import Overpass.OverpassConverter;
import PathFinding.PathTree;
import PathFinding.RoutePathFinder;
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

    /**
     * Class used for indexing OSMWays in the downloaded region into subregions, for more efficient
     * bounding box checks
     */
    public static class Cell {
        public final static List<Cell> allCells = new ArrayList<>(128);
        public final static double cellSizeInMeters = 500.0;
        private static double cellSize;
        private static double searchBuffer = 0.0;

        public static void initCellsForBounds(final Region bounds) {
            //wipe any existing cells (i.e. from a previous run)
            allCells.clear();

            //and prepare the region, including a buffer zone equal to the greatest of the various search/bounding box dimensions
            cellSize = SphericalMercator.metersToCoordDelta(cellSizeInMeters, bounds.getCentroid().y);
            searchBuffer = -SphericalMercator.metersToCoordDelta(Math.max(RouteConflator.wayMatchingOptions.segmentSearchBoxSize, Math.max(StopArea.maxConflictSearchDistance, StopArea.maxDistanceFromPlatformToWay)), bounds.getCentroid().y);

            //get the min/max extent of the bounded region
            final Point rcOrigin = Cell.getCellOriginForPoint(bounds.origin), rcExtent = Cell.getCellOriginForPoint(bounds.extent);
            for(double y = rcOrigin.y; y < rcExtent.y; y += Cell.cellSize) {
                for(double x = rcOrigin.x; x < rcExtent.x; x += Cell.cellSize) {
                    createCellForPoint(new Point(x, y));
                }
            }
        }

        public static Cell createCellForPoint(final Point point) {
            final Point cellOrigin = getCellOriginForPoint(point);
            Cell cell = new Cell(cellOrigin);
            allCells.add(cell);
            return cell;
        }
        public static Point getCellOriginForPoint(final Point point) {
            return new Point(Math.floor(point.x * cellSize) / cellSize, Math.floor(point.y / cellSize) * cellSize);
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

    public final OSMRelation importRouteMaster;
    public final List<Route> importRoutes;
    public final String routeType;
    private OSMRelation exportRouteMaster;
    private List<Route> exportRoutes;
    private HashMap<Long, StopArea> allRouteStops;
    public static boolean debugEnabled = false;
    public final long debugRouteId = -429426L;//*0L;
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
        condenseSubRoutes(subRoutes);

        System.out.println("Used " + importRoutes.size() + " unique out of " + subRoutes.size() + " possible subroutes");
    }

    /**
     * Condense the list of subroutes down, based on the stops they hit.  If a subroute's stops are an ordered subset
     * of any other subroute, disregard the former subroute
     * @param allSubRoutes
     */
    public void condenseSubRoutes(final List<OSMRelation.OSMRelationMember> allSubRoutes) {
        //Now add the subroutes to the importRoutes list - these are the subroutes that will be processed for matches
        final Map<OSMRelation, List<String>> routeStopIds = new HashMap<>(allSubRoutes.size());
        for(final OSMRelation.OSMRelationMember member : allSubRoutes) {
            if (!(member.member instanceof OSMRelation)) { //should always be the case
                continue;
            }
            final OSMRelation subRoute = (OSMRelation) member.member;

            //create an ordered list of the stops in the subroute
            final ArrayList<String> stopIds = new ArrayList<>(subRoute.getMembers().size());
            for (final OSMRelation.OSMRelationMember subRouteMember : subRoute.getMembers("platform")) {
                stopIds.add(subRouteMember.member.getTag(OSMEntity.KEY_REF));
            }
            routeStopIds.put(subRoute, stopIds);
        }

        //now create a list of the subroutes whose stops are the same (or are an ordered subset of) another subroute
        final Map<OSMRelation, List<String>> stopIdDuplicates = new HashMap<>(routeStopIds.size());
        int i = 0, j;
        for(final Map.Entry<OSMRelation, List<String>> stopIds : routeStopIds.entrySet()) {
            j = 0;
            if(stopIdDuplicates.containsKey(stopIds.getKey())) { //don't process if already marked as a subset/dupe of other
                //System.out.println(i + ":" + j + "::" + stopIds.getKey().osm_id + " already a duplicate OUTER");
                i++;
                continue;
            }
            for(final Map.Entry<OSMRelation, List<String>> otherStopIds : routeStopIds.entrySet()) {
                if(stopIds.getValue() == otherStopIds.getValue()) { //don't compare equals!
                    //System.out.println(i + ":" + j++ + " IS SAME");
                    continue;
                }
                if(stopIdDuplicates.containsKey(otherStopIds.getKey())) { //don't process if already marked as a subset/dupe of other
                    //System.out.println(i + ":" + j++ + "::" + stopIds.getKey().osm_id + " already a duplicate INNER");
                    continue;
                }

                //if the stops in both sets are equal and in order, mark one of them as a duplicate
                if(stopIds.getValue().equals(otherStopIds.getValue())) {
                    //System.out.println(i + ":" + j++ + "::" + stopIds.getKey().osm_id + " is EQUAL to " + otherStopIds.getKey().osm_id);
                    stopIdDuplicates.put(otherStopIds.getKey(), otherStopIds.getValue());
                    continue;
                }

                //check if the otherStopIds array fully contains stopIds, including stop order
                final List<String> mostStops, leastStops;
                if(stopIds.getValue().size() > otherStopIds.getValue().size()) {
                    mostStops = stopIds.getValue();
                    leastStops = otherStopIds.getValue();
                } else {
                    mostStops = otherStopIds.getValue();
                    leastStops = stopIds.getValue();
                }
                //initial containsAll() check (unordered)
                if(mostStops.containsAll(leastStops)) {
                    //also do an order check, by finding the common elements with retainAll() and comparing
                    final List<String> commonElements = new ArrayList<>(mostStops);
                    commonElements.retainAll(leastStops);

                    //if the common elements .equals() the smaller array, it's an ordered subset and can be de-duped
                    if(commonElements.equals(leastStops)) {
                        if(mostStops == stopIds.getValue()) {
                            stopIdDuplicates.put(otherStopIds.getKey(), otherStopIds.getValue());
                            //System.out.println(i + ":" + j + "::" + otherStopIds.getKey().osm_id + " is DUPLICATE OF" + stopIds.getKey().osm_id);
                        } else if (mostStops == otherStopIds.getValue()){
                            stopIdDuplicates.put(stopIds.getKey(), stopIds.getValue());
                            //System.out.println(i + ":" + j + "::" + stopIds.getKey().osm_id + " is DUPLICATE OF" + otherStopIds.getKey().osm_id);
                            break; //bail since the outer item is no longer to be used
                        }
                    }
                }
                j++;
            }
            i++;
        }

        //now add the de-duplicated subroutes to the list to be processed
        for(final Map.Entry<OSMRelation, List<String>> routeStops : routeStopIds.entrySet()) {
            if(stopIdDuplicates.containsKey(routeStops.getKey())) {
                // System.out.println("DUPED " + routeStops.getKey().osm_id + ": " + String.join(":", routeStops.getValue()));
                continue;
            }
            //System.out.println("USING " + routeStops.getKey().osm_id + ": " + String.join(":", routeStops.getValue()));
            importRoutes.add(new Route(routeStops.getKey(), wayMatchingOptions));
        }
    }
    public static boolean validateRouteType(final String routeType) {
        if(routeType == null) {
            return false;
        }
        switch (routeType) {
            case OSMEntity.TAG_BUS: //bus stops are always nodes
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
                routePaths.add(route.routePath);
                if(routePathsBoundingBox == null) {
                    routePathsBoundingBox = new Region(route.routePath.getBoundingBox());
                } else {
                    routePathsBoundingBox = Region.union(routePathsBoundingBox, route.routePath.getBoundingBox());
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

        System.out.println("Processing with " + workingEntitySpace.allWays.size() + " ways");

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
                regionWay.setTag("gtfs:ignore", "yes");
                //System.out.println(r.toString());
            }
        }
        workingEntitySpace.markAllEntitiesWithAction(OSMEntity.ChangeAction.none);

        //now that the data is downloaded, create the necessary OSM relations in the new working entity space
        exportRouteMaster = workingEntitySpace.createRelation(importRouteMaster.getTags(), null);
        exportRoutes = new ArrayList<>(importRoutes.size());
        allRouteStops = new HashMap<>(importRoutes.size() * 64);
        for(final Route importRoute : importRoutes) {
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

            //and create the object to represent the subroute in the working entity space
            final Route exportRoute = new Route(importRoute, workingEntitySpace, exportRouteStops);
            exportRoutes.add(exportRoute);
            exportRouteMaster.addMember(exportRoute.routeRelation, OSMEntity.MEMBERSHIP_DEFAULT);
        }

        //create the Cell index for all the ways, for faster lookup below
        Cell.initCellsForBounds(workingEntitySpace.getBoundingBox());

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
            Region testRegion = curRegion.clone();
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

            //get a handle on the WaySegments that geographically match the route's routeLine
            route.routeLine.findMatchingLineSegments(this);
        }

        //update the route's stop proximity matches to include the match info on the OSM ways
        final long timeStartStopMatching = new Date().getTime();
        stopConflator.matchStopsToWays();
        stopConflator.createStopPositionsForPlatforms(workingEntitySpace);
        System.out.println("Matched stops in " + (new Date().getTime() - timeStartStopMatching) + "ms");

        for(final Route route : exportRoutes) {
            route.spliteRouteLineByStops(workingEntitySpace);

            try {
                route.debugOutputSegments(workingEntitySpace, candidateLines.values());
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InvalidArgumentException e) {
                e.printStackTrace();
            }
        }


        //TODO: debug bail
        if(Math.random() < 2)
            return;

        //with the candidate lines determined, begin the pathfinding stage to lock down the path between the route's stops
        final List<RoutePathFinder> routePathFinderFinders = new ArrayList<>(exportRoutes.size());
        for(final Route route : exportRoutes) {
            System.out.println("Begin PathFinding for subroute \"" + route.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + route.routeRelation.osm_id + ")");
            if (debugRouteId != 0 && route.routeRelation.osm_id != debugRouteId) {
                continue;
            }
            final RoutePathFinder routePathFinder = new RoutePathFinder(route, candidateLines, allowedRouteTags);
            routePathFinderFinders.add(routePathFinder);
            routePathFinder.findPaths(workingEntitySpace);

            //if the route wasn't fully matched, mark it
            if(routePathFinder.getFailedPaths() > 0) {
                workingEntitySpace.addEntity(route.routePath, OSMEntity.TagMergeStrategy.keepTags, null);
                route.routeRelation.addMember(route.routePath, "");
                route.routeRelation.setTag(OSMEntity.KEY_NAME, "**" + route.routeRelation.getTag(OSMEntity.KEY_NAME));
            }

            //and add the stops data to the OSMRelation for the route
            route.syncStopsWithRelation();

            if(debugEnabled) {
                System.out.println("--------------------------------------------------------\nPRE-SPLIT Paths for " + routePathFinder.route.routeRelation.osm_id + ":");
                for (final PathTree pathTree : routePathFinder.allPathTrees) {
                    System.out.println("PATH: " + pathTree.bestPath);
                }
            }

            //split any ways that aren't fully contained by the route path
            routePathFinder.splitWaysAtIntersections(workingEntitySpace);

            //debug paths
            if(debugEnabled) {
                System.out.println("--------------------------------------------------------\nFinal Paths for " + routePathFinder.route.routeRelation.osm_id + ":");
                for (final PathTree pathTree : routePathFinder.allPathTrees) {
                    System.out.println("PATH: " + pathTree.bestPath);
                }
            }

            //and finally, add the ways associated with the routeFinder's best path to the OSM route relation
            routePathFinder.addWaysToRouteRelation();

            if(debugEnabled) {
                try {
                    workingEntitySpace.outputXml("newresult" + route.routeRelation.osm_id + ".osm");
                    route.debugOutputSegments(workingEntitySpace, candidateLines.values());
                    routePathFinder.debugOutputPaths(workingEntitySpace);
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
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment newSegment) {

    }
}
