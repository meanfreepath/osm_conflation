package Conflation;

import OSM.*;
import Overpass.OverpassConverter;
import com.company.PathSegment;
import com.company.PathTree;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.util.*;

/**
 * Tracks and processes a route_master-type OSM relation
 * Created by nick on 1/27/16.
 */
public class RouteConflator {
    public static class LineComparisonOptions {
        public double maxSegmentLength = 5.0, maxSegmentOrthogonalDistance = 10.0, maxSegmentMidPointDistance = 20.0, boundingBoxSize = 50.0;
        private double minSegmentDotProduct;
        public void setMaxSegmentAngle(final double angle) {
            minSegmentDotProduct = Math.cos(angle);
        }
        public double getMinSegmentDotProduct() {
            return minSegmentDotProduct;
        }
    }

    public final OSMRelation importRouteMaster;
    public final List<Route> importRoutes;
    public final String routeType;
    private OSMRelation exportRouteMaster;
    private List<Route> exportRoutes;
    private HashMap<Long, StopArea> allRouteStops;
    public Point roughCentroid = null; //crappy hack so we can have a latitude value to work with
    public static boolean debugEnabled = false;
    private OSMEntitySpace workingEntitySpace = null;
    private final LineComparisonOptions wayMatchingOptions;
    private HashMap<Long, WaySegments> candidateWaySegments;

    public RouteConflator(final OSMRelation routeMaster, final LineComparisonOptions wayMatchingOptions) throws InvalidArgumentException {
        importRouteMaster = routeMaster;
        this.wayMatchingOptions = wayMatchingOptions;
        routeType = importRouteMaster.getTag(OSMEntity.KEY_ROUTE_MASTER);
        if(!validateRouteType(routeType)) {
            final String errMsg[] = {"Invalid route type provided"};
            throw new InvalidArgumentException(errMsg);
        }

        final List<OSMRelation.OSMRelationMember> subRoutes = routeMaster.getMembers();
        importRoutes = new ArrayList<>(subRoutes.size());

        for(final OSMRelation.OSMRelationMember member : subRoutes) {
            if(member.member instanceof OSMRelation) { //should always be the case
                importRoutes.add(new Route((OSMRelation) member.member, wayMatchingOptions));
                roughCentroid = member.member.getCentroid();
            }
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
    public boolean downloadRegionsForImportDataset(final OSMEntitySpace intoEntitySpace) throws InvalidArgumentException {
        if(importRoutes.size() == 0) {
            System.out.println("No routes for Master " + importRouteMaster.getTag("name"));
            return false;
        }

        workingEntitySpace = intoEntitySpace;

        //get a handle on the route path geometries for the subroutes
        final List<OSMWay> routePaths = new ArrayList<>(importRoutes.size());
        for(final Route route : importRoutes) {
            if(route.routeType != null && route.routeType.equals(OSMEntity.TAG_ROUTE)) {
                routePaths.add(route.routePath);
            }
        }


                /*final OverpassConverter converter = new OverpassConverter();
                final String query = converter.queryForBoundingBox("[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", combinedBoundingBox, 0.0004, OSMEntity.OSMType.way);
                converter.fetchFromOverpass(query);
                final OSMEntitySpace osmServerEntitySpace = converter.getEntitySpace();
                osmServerEntitySpace.outputXml("origdl.osm");//*/

        //and get the download regions for them
        final List<Region> downloadRegions = generateCombinedDownloadRegions(routePaths, wayMatchingOptions.boundingBoxSize);

        //fetch all possible useful ways that intersect the route's combined bounding box
        //TODO base query on route type
        for(final Region downloadRegion : downloadRegions) {
            final OverpassConverter converter = new OverpassConverter();
            final String query = converter.queryForBoundingBox("[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", downloadRegion, 0.0, OSMEntity.OSMType.way);
            converter.fetchFromOverpass(query);
            workingEntitySpace.mergeWithSpace(converter.getEntitySpace(), OSMEntity.TagMergeStrategy.keepTags, null);
        }
        System.out.println("Processing with " + workingEntitySpace.allWays.size() + " ways");

        if (debugEnabled) {
            for (final Region r : downloadRegions) {
                List<OSMNode> rNodes = new ArrayList<>(5);
                rNodes.add(workingEntitySpace.createNode(r.origin.latitude, r.origin.longitude, null));
                rNodes.add(workingEntitySpace.createNode(r.origin.latitude, r.extent.longitude, null));
                rNodes.add(workingEntitySpace.createNode(r.extent.latitude, r.extent.longitude, null));
                rNodes.add(workingEntitySpace.createNode(r.extent.latitude, r.origin.longitude, null));
                rNodes.add(workingEntitySpace.createNode(r.origin.latitude, r.origin.longitude, null));
                final OSMWay regionWay = workingEntitySpace.createWay(null, rNodes);
                regionWay.setTag("landuse", "construction");
                regionWay.setTag("gtfs:ignore", "yes");
                System.out.println(r.toString());
            }
        }

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
                existingStop = allRouteStops.get(stop.platform.osm_id);

                if(existingStop == null) {
                    newStopPlatform = workingEntitySpace.addEntity(stop.platform, OSMEntity.TagMergeStrategy.keepTags, null);
                    if (stop.getStopPosition() != null) {
                        newStopPosition = (OSMNode) workingEntitySpace.addEntity(stop.getStopPosition(), OSMEntity.TagMergeStrategy.keepTags, null);
                    } else {
                        newStopPosition = null;
                    }
                    existingStop = new StopArea(newStopPlatform, newStopPosition);
                    allRouteStops.put(stop.platform.osm_id, existingStop);
                }
                exportRouteStops.add(existingStop);
            }

            final Route exportRoute = new Route(importRoute, workingEntitySpace, exportRouteStops, wayMatchingOptions);
            exportRoutes.add(exportRoute);
            exportRouteMaster.addMember(exportRoute.routeRelation, OSMEntity.MEMBERSHIP_DEFAULT);
        }

        //create WaySegments objects for all downloaded ways
        candidateWaySegments = new HashMap<>(workingEntitySpace.allWays.size());
        for(final OSMWay way : workingEntitySpace.allWays.values()) {
            candidateWaySegments.put(way.osm_id, new WaySegments(way, wayMatchingOptions.maxSegmentLength));
        }

        return true;
    }
    /**
     * Generates a set of rectangular regions which roughly corresponds with the download area for the given ways
     * TODO: improve algorithm to reduce overlap etc
     * @param ways
     * @param expansionAmount
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
                        System.out.println("OVERLAPPED " + region1 + " and " + region2 + ", union: " + unionRegion);
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
        final Region wayBoundingBox = way.getBoundingBox();
        final double latitudeDelta = -boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * (wayBoundingBox.origin.latitude + wayBoundingBox.extent.latitude) / 360.0);

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
            testRegion = testRegion.regionInset(latitudeDelta, longitudeDelta);

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
    public void conflateRoutePaths(final StopConflator stopMatcher) {
        for(final Route route : exportRoutes) {
            System.out.println("Begin conflation for subroute \"" + route.routeRelation.getTag(OSMEntity.KEY_NAME) + "\" (id " + route.routeRelation.osm_id + ")");
            final long timeStartLineComparison = new Date().getTime();
            final double latitudeDelta = -wayMatchingOptions.boundingBoxSize / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * route.routeLine.segments.get(0).originPoint.latitude / 180.0);
            for(final LineSegment mainLineSegment : route.routeLine.segments) {
                final Region mainBoundingBox = mainLineSegment.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
                for(final WaySegments candidateLine : candidateWaySegments.values()) {
                    //don't match against any lines that have been marked as "ignore", such as other gtfs shape lines
                    if(candidateLine.way.hasTag("gtfs:ignore")) {
                        continue;
                    }

                    //check for candidate lines whose bounding box intersects this segment, and add to the candidate list for this segment
                    if(Region.intersects(mainBoundingBox, candidateLine.way.getBoundingBox().regionInset(latitudeDelta, longitudeDelta))) {
                        mainLineSegment.candidateWaySegments.add(candidateLine);
                    }
                }
            }

            //now that we have a rough idea of the candidate ways for each segment, run detailed checks on their segments' distance and dot product
            for(final LineSegment mainSegment : route.routeLine.segments) {
                for(final WaySegments candidateLine : mainSegment.candidateWaySegments) {
                    for(final LineSegment candidateSegment : candidateLine.segments) {
                        SegmentMatch.checkCandidateForMatch(wayMatchingOptions, mainSegment, candidateSegment, candidateLine.matchObject);
                    }
                }
            }

            //consolidate the segment match data for each matching line
            for(final WaySegments line : candidateWaySegments.values()) {
                line.matchObject.summarize();
            }
            System.out.println("Matched lines in " + (new Date().getTime() - timeStartLineComparison) + "ms");

            //TODO BEGIN LEGACY PATHFINDING
            final PathTree pathList = new PathTree(route);
            pathList.findPaths(candidateWaySegments);

            //finally, add the matched ways to the relation's members
            if(pathList.bestPath != null) {
                for (final PathSegment pathSegment : pathList.bestPath.pathSegments) {
                    route.routeRelation.addMember(pathSegment.line.way, OSMEntity.MEMBERSHIP_DEFAULT);
                }
            } else { //debug: if no matched path, output the best candidates instead
                for (final WaySegments line : candidateWaySegments.values()) {
                    if(line.matchObject.matchingSegments.size() > 2 && line.matchObject.getAvgDotProduct() >= 0.9) {
                        route.routeRelation.addMember(line.way, OSMEntity.MEMBERSHIP_DEFAULT);
                    }
                }
            }
            //TODO END LEGACY PATHFINDING

            //and add the stops data to the OSMRelation for the route
            route.syncStopsWithRelation();
            if(debugEnabled) {
                try {
                    workingEntitySpace.outputXml("newresult" + route.routePath.osm_id + ".osm");
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
    public HashMap<Long, WaySegments> getCandidateWaySegments() {
        return candidateWaySegments;
    }
}
