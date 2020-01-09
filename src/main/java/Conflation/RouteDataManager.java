package Conflation;

import OSM.*;
import Overpass.Exceptions;
import Overpass.OverpassConverter;
import com.company.Config;
import com.company.InvalidArgumentException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Manages all the OSM data for the conflation process
 * Created by nick on 11/10/16.
 */
public class RouteDataManager extends OSMEntitySpace implements WaySegmentsObserver {
    public static boolean debugEnabled = false;

    protected HashMap<Long, OSMWaySegments> candidateLines = null;

    public RouteDataManager(int capacity) {
        super(capacity);
        name = "Working space";
    }

    public boolean downloadRegionsForImportDataset(final List<RouteConflator> routeConflators, final RouteConflator.LineComparisonOptions wayMatchingOptions, final boolean cachingEnabled) throws InvalidArgumentException, Exceptions.UnknownOverpassError {

        //get a handle on the route path geometries for the subroutes
        final List<OSMWay> routePaths = new ArrayList<>(routeConflators.size() * 4);
        RouteConflator.RouteType routeType = null;
        Region routePathsBoundingBox = null;
        for(final RouteConflator routeConflator : routeConflators) {
            if(routeType == null) {
                routeType = routeConflator.routeType;
            } else if(routeType != routeConflator.routeType) {
                throw new InvalidArgumentException("All routes must be of the same routeType");
            }

            for (final Route route : routeConflator.importRoutes) {
                if (route.routeType != null) {
                    routePaths.add(route.routeLine.way);
                    if (routePathsBoundingBox == null) {
                        routePathsBoundingBox = new Region(route.routeLine.way.getBoundingBox());
                    } else {
                        routePathsBoundingBox = Region.union(routePathsBoundingBox, route.routeLine.way.getBoundingBox());
                    }
                }
            }
        }
        assert routeType != null;
        assert routePathsBoundingBox != null;

        //and get the download regions for them
        final double boundingBoxSize = SphericalMercator.metersToCoordDelta(wayMatchingOptions.segmentSearchBoxSize, routePathsBoundingBox.getCentroid().y);
        final List<Region> downloadRegions = generateCombinedDownloadRegions(routePaths, boundingBoxSize);

        //fetch all possible useful ways that intersect the route's combined bounding box
        final String queryFormat = "[\"%s\"~\"%s\"]";
        int downloadIdx = 0;
        for(final Region downloadRegion : downloadRegions) {
            final OverpassConverter converter = new OverpassConverter();

            //TODO: works fine for now, but if a route requires multiple search *keys*, will create excessive queries
            for(final Map.Entry<String,List<String>> searchKeys : RouteConflator.wayTagsForRouteType(routeType).entrySet()) {
                final String query = converter.queryForBoundingBox(String.format(queryFormat, searchKeys.getKey(), String.join("|", searchKeys.getValue())), downloadRegion, 0.0, OSMEntity.OSMType.way);
                converter.fetchFromOverpass(query, cachingEnabled);
                final Date now = new Date();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");
                converter.getEntitySpace().name = String.format("download_%s_%d", dateFormat.format(now), downloadIdx++);
                try {
                    converter.getEntitySpace().outputXml(Config.sharedInstance.cacheDirectory + "/" + converter.getEntitySpace().name + ".osm");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mergeWithSpace(converter.getEntitySpace(), OSMEntity.TagMergeStrategy.keepTags, null);
            }
        }

        //also fetch all route relations of the appropriate type, along with any parent route_master relations in the area
        final OverpassConverter relationConverter = new OverpassConverter();
        final LatLonRegion routePathsRegionLL = SphericalMercator.mercatorToLatLon(routePathsBoundingBox);
        final String relationQueryComponents[] = {
                String.format("relation[\"type\"=\"route\"][\"route\"=\"%s\"](%.07f,%.07f,%.07f,%.07f)", routeType, routePathsRegionLL.origin.latitude, routePathsRegionLL.origin.longitude, routePathsRegionLL.extent.latitude, routePathsRegionLL.extent.longitude),
        };
        final String relationQuery = "(" + String.join(";", relationQueryComponents) + ";rel(br););";
        relationConverter.fetchFromOverpass(relationQuery, cachingEnabled);
        mergeWithSpace(relationConverter.getEntitySpace(), OSMEntity.TagMergeStrategy.keepTags, null);

        System.out.format("INFO: Begin processing with entity space: %s\n", this);

        if (debugEnabled) {
            for (final Region r : downloadRegions) {
                List<OSMNode> rNodes = new ArrayList<>(5);
                rNodes.add(createNode(r.origin.x, r.origin.y, null));
                rNodes.add(createNode(r.extent.x, r.origin.y, null));
                rNodes.add(createNode(r.extent.x, r.extent.y, null));
                rNodes.add(createNode(r.origin.x, r.extent.y, null));
                rNodes.add(createNode(r.origin.x, r.origin.y, null));
                final OSMWay regionWay = createWay(null, rNodes);
                regionWay.setTag("landuse", "construction");
                regionWay.setTag(RouteConflator.GTFS_IGNORE, "yes");
                //System.out.println(r.toString());
            }
        }
        markAllEntitiesWithAction(OSMEntity.ChangeAction.none);

        //now that the data is downloaded, create the necessary OSM relations in the new working entity space:
        //create a list of all existing routes and route_masters in the working entity space
        final List<OSMRelation> existingRouteMasters = new ArrayList<>(allRelations.size()), existingRoutes = new ArrayList<>(allRelations.size());
        for(final OSMRelation relation : allRelations.values()) {
            if (OSMEntity.TAG_ROUTE_MASTER.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_TYPE))) {
                existingRouteMasters.add(relation);
            } else if (OSMEntity.TAG_ROUTE.equalsIgnoreCase(relation.getTag(OSMEntity.KEY_TYPE))) {
                existingRoutes.add(relation);
            }
        }

        //using the above list, conflate the GTFS import routes/trips with the existing OSM route_master/route entities
        for(final RouteConflator routeConflator : routeConflators) {
            final List<OSMRelation> existingSubRoutes = routeConflator.conflateExistingRouteMaster(existingRouteMasters);
            routeConflator.conflateExistingRouteRelations(existingRoutes, existingSubRoutes);
        }

        //create the Cell index for all the ways, for faster lookup below
        Cell.initCellsForBounds(routePathsBoundingBox, wayMatchingOptions);

        //create OSMWaySegments objects for all downloaded ways
        candidateLines = new HashMap<>(allWays.size());
        final Date t0 = new Date();
        for (final OSMWay way : allWays.values()) {
            //only include completely-downloaded ways, with all their nodes present and complete
            if (way.getCompletionStatus() != OSMEntity.CompletionStatus.membersComplete) {
                purgeEntity(way);
                continue;
            }

            final OSMWaySegments line = new OSMWaySegments(way, wayMatchingOptions);
            candidateLines.put(way.osm_id, line);
            line.addObserver(this);

            for (final Cell cell : Cell.allCells) {
                if (Region.intersects(cell.boundingBox, way.getBoundingBox())) {
                    cell.addWay(line);
                }
            }
        }
        System.out.format("DEBUG: generated LineSegments in %dms\n", new Date().getTime() - t0.getTime());

        return true;
    }
    /**
     * Generates a set of rectangular regions which roughly corresponds with the download area for the given ways
     * TODO: improve algorithm to reduce overlap etc
     * @param ways the ways used to generate the bounding boxes
     * @param expansionAmount - the amount to expand the download regions, in meters
     * @return The list of regions to use
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
    /**
     * Downloads all OSM stops in the given stop list's area, and conflates them with the given stop list's stops
     * @param routeConflators the list of route_masters we're conflating the stops for
     */
    public void conflateStopsWithOSM(final List<RouteConflator> routeConflators, final boolean cachingEnabled) {
        if (routeConflators.size() == 0) {
            return;
        }
        conflateStopsWithOSM(RouteConflator.allStops.values(), routeConflators.get(0).routeType, cachingEnabled);
    }
    public void conflateStopsWithOSM(final Collection<StopArea> allStops, final RouteConflator.RouteType routeType, final boolean cachingEnabled) {

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

        final Map<String, List<String>> platformTags = RouteConflator.platformTagsForRouteType(routeType);
        assert platformTags != null;

        final OverpassConverter converter = new OverpassConverter();
        try {
            final String query;
            final LatLonRegion stopDownloadRegionLL = SphericalMercator.mercatorToLatLon(stopDownloadRegion);
            switch (routeType) {
                case bus: //bus stops are typically nodes, but may also be ways
                    final String[] queryComponentsBus = {
                            String.format("node[\"highway\"=\"bus_stop\"](%.07f,%.07f,%.07f,%.07f);rel(bn);", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("node[\"public_transport\"~\"platform|stop_position\"](%.07f,%.07f,%.07f,%.07f);rel(bn);", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("way[\"highway\"=\"bus_stop\"](%.07f,%.07f,%.07f,%.07f)->.a;.a>;.a<;", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("way[\"public_transport\"=\"platform\"](%.07f,%.07f,%.07f,%.07f)->.b;.b>;.b<;", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude)
                    };
                    query = "(" + String.join(" ", queryComponentsBus) + ");(._;);";
                    break;
                case light_rail:
                case train:
                case subway:
                case railway:
                case tram:
                    final String[] queryComponentsRail = {
                            String.format("node[\"railway\"~\"platform|tram_stop\"](%.07f,%.07f,%.07f,%.07f);rel(bn);", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("node[\"public_transport\"~\"platform|stop_position\"](%.07f,%.07f,%.07f,%.07f);rel(bn);", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("way[\"railway\"=\"platform\"](%.07f,%.07f,%.07f,%.07f)->.a;.a>;.a<;", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude),
                            String.format("way[\"public_transport\"=\"platform\"](%.07f,%.07f,%.07f,%.07f)->.b;.b>;.b<;", stopDownloadRegionLL.origin.latitude, stopDownloadRegionLL.origin.longitude, stopDownloadRegionLL.extent.latitude, stopDownloadRegionLL.extent.longitude)
                    };
                    query = "(" + String.join(";", queryComponentsRail) + ");(._;>;);";
                    break;
                case monorail: //TODO: not implemented yet
                case ferry:
                default:
                    return;
            }
            converter.fetchFromOverpass(query, cachingEnabled);
        } catch (InvalidArgumentException | Exceptions.UnknownOverpassError e) {
            e.printStackTrace();
            return;
        }

        //add the downloaded OSM stops to the working entity space
        final OSMEntitySpace existingStopsSpace = converter.getEntitySpace();
        existingStopsSpace.markAllEntitiesWithAction(OSMEntity.ChangeAction.none);

        if(debugEnabled) {
            try {
                existingStopsSpace.outputXml(String.format("%s/stopdownload.osm", Config.sharedInstance.cacheDirectory));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //and another list of stops in the existing OSM data
        final ArrayList<OSMEntity> importedExistingStops = new ArrayList<>(existingStopsSpace.allEntities.size());
        for(final OSMEntity existingStop : existingStopsSpace.allEntities.values()) {
            boolean includeEntity = false;
            for(final String platformKey : platformTags.keySet()) {
                if (existingStop.hasTag(platformKey)) { //only check for entities matching the route's platform tags
                    includeEntity = true;
                    break;
                }
            }
            if(includeEntity) {
                importedExistingStops.add(addEntity(existingStop, OSMEntity.TagMergeStrategy.keepTags, null, true, 1));
            }
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

                    //flag as a conflict if no gtfs:stop_id or ref tag
                    if(!existingEntity.hasTag(RouteConflator.GTFS_STOP_ID) && !existingEntity.hasTag(OSMEntity.KEY_REF) && (entityType == null || OSMEntity.TAG_PLATFORM.equals(entityType))) { //if a platform, mark as a conflict
                        final double stopDistance = Point.distance(stop.getPlatform().getCentroid(), existingEntity.getCentroid());
                        if (stopDistance < StopArea.maxDistanceBetweenDuplicateStops) {
                            //System.out.format("%s within distance of %s: dist %.01f!\n", stop, existingEntity, stopDistance);
                            stop.getPlatform().setTag(StopArea.KEY_GTFS_CONFLICT, "id #" + existingEntity.osm_id);
                        }
                    }/* else if(OSMEntity.TAG_STOP_POSITION.equals(entityType)) { //no action taken on existing stop positions
                        //System.out.println("No GTFS/REF MATCH FOR " + existingEntity.getTags().toString());
                    }//*/
                } else {
                    //check whether the entity is a platform or a stop_position (may be null if simply a highway=bus_stop)
                    final String entityType = existingEntity.getTag(OSMEntity.KEY_PUBLIC_TRANSPORT);

                    if(entityType == null || OSMEntity.TAG_PLATFORM.equals(entityType)) {
                        //copy the tags from the GTFS stop into the existing stop (we keep the existing platform's location, since these are usually better-positioned than the GTFS data's)
                        existingEntity.copyTagsFrom(stop.getPlatform(), OSMEntity.TagMergeStrategy.copyTags);
                        deleteEntity(stop.getPlatform()); //delete the GTFS entity from the working space - no longer needed
                        stop.setPlatform(existingEntity); //and point the StopArea's platform to the existing platform entity

                        stop.getPlatform().removeTag(StopArea.KEY_GTFS_CONFLICT); //in case previously marked as a conflict

                        //remove from the imported entity list since we've matched it
                        importedExistingStopsIterator.remove();
                    } else if(OSMEntity.TAG_STOP_POSITION.equals(entityType)) {
                        //check that the stop_position supports the current routeType (i.e. for mixed-mode platforms like shared bus/tram stops)
                        boolean supportsRouteType = OSMEntity.TAG_YES.equalsIgnoreCase(existingEntity.getTag(routeType.spKeyForRouteType()));
                        if(!supportsRouteType) {
                            boolean supportsOtherRouteType = false;
                            for(final RouteConflator.RouteType otherRouteType : RouteConflator.RouteType.values()) {
                                if(OSMEntity.TAG_YES.equalsIgnoreCase(existingEntity.getTag(otherRouteType.spKeyForRouteType()))) {
                                    supportsOtherRouteType = true;
                                    break;
                                }
                            }
                            supportsRouteType = !supportsOtherRouteType;
                        }
                        if(supportsRouteType) {
                            stop.setStopPosition((OSMNode) existingEntity, routeType);
                        }

                        //remove from the imported entity list since we've processed it
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
             //System.out.println("GTFS id match! " + existingEntity.osm_id + ": " + existingEntity.getTag(RouteConflator.GTFS_STOP_ID) + "/" + existingEntity.getTag(OSMEntity.KEY_NAME));
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

    /**
     * Define the search tags for the ways for the given route type
     * @param relationType The type of route relation ("bus", "rail", etc)
     * @return The tags to search for the ways with
     */
    public static Map<String, List<String>> wayTagsForRouteType(final RouteConflator.RouteType relationType) {
        String[] keys;
        String[][] tags;
        switch (relationType) {
            case bus:
                keys = new String[]{"highway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "residential", "unclassified", "service", "living_street"};
                break;
            case light_rail:
                keys = new String[]{"railway"};
                tags = new String[keys.length][];
                tags[0] = new String[]{"rail", "light_rail", "subway", "tram"};
                break;
            case subway:
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
     * @param relationType The type of route relation ("bus", "rail", etc)
     * @return The tags to search for the platforms with
     */
    public static Map<String, List<String>> platformTagsForRouteType(final RouteConflator.RouteType relationType) {
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

    public HashMap<Long, OSMWaySegments> getCandidateLines() {
        return candidateLines;
    }

    @Override
    public void waySegmentsWasSplit(final WaySegments originalWaySegments, OSMNode[] splitNodes, final WaySegments[] splitWaySegments) throws InvalidArgumentException {
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
