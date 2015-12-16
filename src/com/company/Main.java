package com.company;

import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

public class Main {
    private final static boolean debugEnabled = true;
    public static void main(String[] args) {

        OSMEntitySpace importSpace = new OSMEntitySpace(1024);

        //define the options for the LineComparison routines
        final LineComparison.ComparisonOptions options = new LineComparison.ComparisonOptions();
        options.boundingBoxSize = 50.0;
        options.maxSegmentLength = 10.0;
        options.setMaxSegmentAngle(10.0);
        options.maxSegmentOrthogonalDistance = 1.0 * options.maxSegmentLength;
        options.maxSegmentMidPointDistance = 4.0 * options.maxSegmentLength;

        try {
            importSpace.loadFromXML("routes.osm");

            /*OSMTaskManagerExporter exporter = new OSMTaskManagerExporter(importSpace);
            exporter.outputForOSMTaskingManager("boxes", "https://www.meanfreepath.com/kcstops/");
            if(Math.random() < 2) {
                return;
            }*/
            //first create a list of all the route_master relations in the import dataset
            final List<OSMRelation> importRouteMasterRelations = new ArrayList<>(importSpace.allRelations.size());
            String relationType;
            for(final OSMRelation relation : importSpace.allRelations.values()) {
                relationType = relation.getTag(OSMEntity.KEY_TYPE);
                if(relationType != null && relationType.equals(OSMEntity.TAG_ROUTE_MASTER)) {
                    importRouteMasterRelations.add(relation);
                }
            }

            //loop through the route masters, processing their subroutes in one entity space
            for(final OSMRelation importRouteMaster : importRouteMasterRelations) {

                final OSMEntitySpace workingEntitySpace = new OSMEntitySpace(importSpace, 32768);
                downloadRegionsForImportDataset(importSpace, workingEntitySpace, importRouteMaster, options);
                importSpace = null;

                //get a handle on the objects in the *working* entity space.  ALL OPERATIONS MUST BE DONE IN THIS SPACE!
                final OSMRelation osmRouteMaster = workingEntitySpace.allRelations.get(importRouteMaster.osm_id);
                final List<OSMRelation> routeMasterSubRoutes = new ArrayList<>(osmRouteMaster.getMembers().size());
                for(final OSMRelation.OSMRelationMember subRoute : osmRouteMaster.getMembers()) {
                    if(subRoute.member instanceof OSMRelation) {
                        final String routeTag = subRoute.member.getTag(OSMEntity.KEY_TYPE);
                        if(routeTag != null && routeTag.equals(OSMEntity.TAG_ROUTE)) {
                            routeMasterSubRoutes.add((OSMRelation) subRoute.member);
                        }
                    }
                }

                //fetch all existing stops in the route's bounding box
                conflateStops(osmRouteMaster, workingEntitySpace, osmRouteMaster.getBoundingBox(), 20.0);

                for(final OSMRelation relation : routeMasterSubRoutes) {
                    final List<OSMRelation.OSMRelationMember> members = relation.getMembers("");
                    final OSMWay routePath = (OSMWay) members.get(0).member;
                    routePath.setTag("gtfs:ethereal", "yes");
                    if (routePath.osm_id != -3) {
                       // continue;
                    }
                    System.out.println("Begin conflation for subroute \"" + relation.getTag(OSMEntity.KEY_NAME) + "\" (id " + relation.osm_id + ")");

                    //Break the candidate ways into LineSegments and match their geometries to the main route line
                    final long timeStartLineComparison = new Date().getTime();
                    final LineComparison comparison = new LineComparison(routePath, new ArrayList<>(workingEntitySpace.allWays.values()), options, debugEnabled);
                    comparison.matchLines();
                    System.out.println("Matched lines in " + (new Date().getTime() - timeStartLineComparison) + "ms");

                    //also, match the stops in the relation to their nearest matching way
                    final long timeStartStopMatching = new Date().getTime();
                    final List<StopWayMatch> allStopMatches = matchStopsToWays(relation, comparison.candidateLines.values(), workingEntitySpace);
                    System.out.println("Matched stops in " + (new Date().getTime() - timeStartStopMatching) + "ms");

                    //now find the optimal path from the first stop to the last stop, using the provided ways
                    final long timeStartPathfinding = new Date().getTime();
                    final PathTree pathList = new PathTree(relation);
                    pathList.findPaths(allStopMatches, comparison.candidateLines);
                    System.out.println("Found paths in " + (new Date().getTime() - timeStartPathfinding) + "ms");

                    //TODO: split ways that only partially overlap the main way


                    //finally, add the matched ways to the relation's members
                    //relation.removeMember(routePath);
                    if(pathList.bestPath != null) {
                        for (final PathSegment pathSegment : pathList.bestPath.pathSegments) {
                            relation.addMember(pathSegment.line.way, "");
                        }
                    } else { //debug: if no matched path, output the best candidates instead
                        for (final WaySegments line : comparison.candidateLines.values()) {
                            if(line.matchObject.matchingSegments.size() > 2 && line.matchObject.getAvgDotProduct() >= 0.9) {
                                relation.addMember(line.way, "");
                            }
                        }
                    }

                    if (debugEnabled) {
                        debugOutputSegments(workingEntitySpace, comparison);
                    }

                    workingEntitySpace.outputXml("newresult" + routePath.osm_id + ".osm");
                }

                //finally, add the completed relations to their own separate file for review and upload
                final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
                for(final OSMRelation relation : routeMasterSubRoutes) {
                    relationSpace.addEntity(relation, OSMEntity.TagMergeStrategy.keepTags, null);
                    relationSpace.deleteEntity(relation.getMembers("").get(0).member.osm_id); //remove the route path way
                }
                relationSpace.outputXml("relation.osm");
            }
            //importSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }

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
    private static boolean downloadRegionsForImportDataset(final OSMEntitySpace importSpace, final OSMEntitySpace osmServerEntitySpace, final OSMRelation routeMaster, final LineComparison.ComparisonOptions options) throws InvalidArgumentException {

        final List<OSMRelation.OSMRelationMember> masterMembers = routeMaster.getMembers();
        if(masterMembers.size() == 0) {
            System.out.println("No routes for Master " + routeMaster.getTag("name"));
            return false;
        }

        //get a handle on the route path geometries for the subroutes
        String memberType;
        final List<OSMWay> routePaths = new ArrayList<>(masterMembers.size());
        for(final OSMRelation.OSMRelationMember member : masterMembers) {
            memberType = member.member.getTag(OSMEntity.KEY_TYPE);
            if(memberType != null && memberType.equals(OSMEntity.TAG_ROUTE)) {
                final OSMRelation curRoute = (OSMRelation) member.member;
                routePaths.add((OSMWay) curRoute.getMembers("").get(0).member);
            }
        }


                /*final OverpassConverter converter = new OverpassConverter();
                final String query = converter.queryForBoundingBox("[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", combinedBoundingBox, 0.0004, OSMEntity.OSMType.way);
                converter.fetchFromOverpass(query);
                final OSMEntitySpace osmServerEntitySpace = converter.getEntitySpace();
                osmServerEntitySpace.outputXml("origdl.osm");//*/

        //and get the download regions for them
        final List<Region> downloadRegions = generateCombinedDownloadRegions(routePaths, options.boundingBoxSize);

        //fetch all possible useful ways that intersect the route's combined bounding box
        for(final Region downloadRegion : downloadRegions) {
            final OverpassConverter converter = new OverpassConverter();
            final String query = converter.queryForBoundingBox("[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", downloadRegion, 0.0, OSMEntity.OSMType.way);
            converter.fetchFromOverpass(query);
            osmServerEntitySpace.mergeWithSpace(converter.getEntitySpace(), OSMEntity.TagMergeStrategy.keepTags, null);
        }
        System.out.println("Processing with " + osmServerEntitySpace.allWays.size() + " ways");

        if (debugEnabled) {
            for (final Region r : downloadRegions) {
                List<OSMNode> rNodes = new ArrayList<>(5);
                rNodes.add(osmServerEntitySpace.createNode(r.origin.latitude, r.origin.longitude, null));
                rNodes.add(osmServerEntitySpace.createNode(r.origin.latitude, r.extent.longitude, null));
                rNodes.add(osmServerEntitySpace.createNode(r.extent.latitude, r.extent.longitude, null));
                rNodes.add(osmServerEntitySpace.createNode(r.extent.latitude, r.origin.longitude, null));
                rNodes.add(osmServerEntitySpace.createNode(r.origin.latitude, r.origin.longitude, null));
                final OSMWay regionWay = osmServerEntitySpace.createWay(null, rNodes);
                regionWay.setTag("landuse", "construction");
                regionWay.setTag("gtfs:ethereal", "yes");
                System.out.println(r.toString());
            }
        }
        return true;
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
    private static List<StopWayMatch> matchStopsToWays(final OSMRelation routeRelation, final Collection<WaySegments> candidateLines, final OSMEntitySpace existingEntitySpace) {
        final List<OSMRelation.OSMRelationMember> routeStops = routeRelation.getMembers("platform");
        final String stopNameStreetSeparator = " & ";
        final StreetNameMatcher matcher = new StreetNameMatcher(Locale.US);
        final List<StopWayMatch> stopWayMatches = new ArrayList<>(routeStops.size());
        final double latitudeDelta = -StopWayMatch.maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * routeStops.get(0).member.getCentroid().latitude / 180.0);

        //loop through the route's stops, determining the best-matching way for each one
        int stopIndex = 0, stopCount = routeStops.size();
        for(final OSMRelation.OSMRelationMember member : routeStops) {
            final OSMNode stopPlatform = (OSMNode) member.member;

            //get the stop's name components, to see if we can use the name to match to a nearby way
            final String stopName = stopPlatform.getTag(OSMEntity.KEY_NAME);
            String[] stopStreetNames = null;
            if(stopName != null) {
                stopStreetNames = stopName.split(stopNameStreetSeparator);
                for (short s = 0; s < stopStreetNames.length; s++) {
                    String exName = matcher.expandedStreetName(stopStreetNames[s]);
                    //System.out.println(stopStreetNames[s] + ": " + exName);
                    stopStreetNames[s] = exName;
                }
            }

            //init the match
            final StopWayMatch wayMatch = new StopWayMatch(stopPlatform, stopIndex++, stopCount);
            stopWayMatches.add(wayMatch);

            //find the nearest OSM ways to the stop platforms, checking all lines whose bounding boxes intersect the stop's search area
            final Region searchRegion = stopPlatform.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            final Point platformCentroid = stopPlatform.getCentroid();
            for(final WaySegments matchingLine : candidateLines) {
                //check the line's bounding box intersects
                if(!Region.intersects(matchingLine.way.getBoundingBox().regionInset(latitudeDelta, longitudeDelta), searchRegion)) {
                    continue;
                }

                //find the nearest segment to the platform on the way
                SegmentMatch nearestSegmentMatch = null;
                double minDistance = StopWayMatch.maxDistanceFromPlatformToWay;
                Point closestPoint;
                for(final SegmentMatch segmentMatch : matchingLine.matchObject.matchingSegments) {
                    //require the matching segment to be a reasonable match to the route's travel path
                    if(Math.abs(segmentMatch.dotProduct) < StopWayMatch.minMatchingSegmentPathDotProduct || segmentMatch.orthogonalDistance > StopWayMatch.maxMatchingSegmentPathDistance) {
                        continue;
                    }

                    //System.out.println("Segment " + segmentMatch.matchingSegment.getDebugName() + ": " + segmentMatch.consolidatedMatches);
                    closestPoint = segmentMatch.matchingSegment.closestPointToPoint(platformCentroid);
                    final double segmentDistance = Point.distance(closestPoint, platformCentroid);
                    if(segmentDistance < minDistance) {
                        nearestSegmentMatch = segmentMatch;
                        minDistance = segmentDistance;
                    }
                }

                //skip this line if not within the maximum distance to the stop
                if(nearestSegmentMatch == null) {
                    //System.out.println("NO MATCH for platform " + stopPlatform.osm_id + "(ref " + stopPlatform.getTag("ref") + "/" + stopPlatform.getTag("name") + ")");
                    continue;
                }

                //also try matching based on the stop name and way names, preferring the stop's first name component
                boolean matchedByName = false;
                if(stopName != null) {
                    final String wayName = matchingLine.way.getTag(OSMEntity.KEY_NAME);
                    if (wayName != null) { //if the name matches use this way
                        if (stopStreetNames[0].equals(wayName)) {
                            wayMatch.addWayMatch(nearestSegmentMatch, minDistance, StopWayMatch.MatchType.primaryStreet);
                            matchedByName = true;
                        } else if (stopStreetNames.length > 1 && stopStreetNames[1].equals(wayName)) {
                            wayMatch.addWayMatch(nearestSegmentMatch, minDistance, StopWayMatch.MatchType.crossStreet);
                            matchedByName = true;
                        }
                    }
                }

                //if matching by name fails, try going by nearest way
                if(!matchedByName) {
                    if(minDistance < StopWayMatch.maxDistanceFromPlatformToWay) {
                        wayMatch.addWayMatch(nearestSegmentMatch, minDistance, StopWayMatch.MatchType.proximity);
                    } else {
                        wayMatch.addWayMatch(nearestSegmentMatch, minDistance, StopWayMatch.MatchType.none);
                    }
                }
            }
        }

        //now pick the best matches for each platform
        for(final StopWayMatch match : stopWayMatches) {
            if(match.matches.size() == 0) {
                System.out.println("No matches found for stop #" + match.platformNode.osm_id + " (" + match.platformNode.getTag("name") + ")");
                continue;
            }
            //determine the best-matching way for the stop
            match.chooseBestMatch();

            //Create (or update an existing node) to serve as the stop position node for the platform
            final LineSegment bestSegment = match.bestMatch.candidateSegmentMatch.matchingSegment;
            final Point nearestPointOnSegment = bestSegment.closestPointToPoint(match.platformNode.getCentroid());

            OSMNode nearestNodeOnWay = bestSegment.parentSegments.way.nearestNodeAtPoint(nearestPointOnSegment, StopWayMatch.stopNodeTolerance);
            if(nearestNodeOnWay == null) {
                nearestNodeOnWay = bestSegment.parentSegments.insertNode(existingEntitySpace.createNode(nearestPointOnSegment.latitude, nearestPointOnSegment.longitude, null), bestSegment);
            }

            //then add a node on the nearest point and add to the relation and the way
            nearestNodeOnWay.setTag(OSMEntity.KEY_NAME, match.platformNode.getTag(OSMEntity.KEY_NAME));
            nearestNodeOnWay.setTag(OSMEntity.KEY_REF, match.platformNode.getTag(OSMEntity.KEY_REF));
            nearestNodeOnWay.setTag("gtfs:stop_id", match.platformNode.getTag("gtfs:stop_id"));
            nearestNodeOnWay.setTag(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_STOP_POSITION);
            nearestNodeOnWay.setTag(routeRelation.getTag(OSMEntity.KEY_ROUTE), OSMEntity.TAG_YES);

            //add to the WaySegments object as well
            bestSegment.parentSegments.matchObject.addStopMatch(match);

            //and add the stop position to the route relation
            match.stopPositionNode = nearestNodeOnWay;
            routeRelation.addMember(nearestNodeOnWay, "stop");

            //warn if no decent match is found
            if(match.bestMatch == null) {
                System.out.println("No sufficient match found for stop #" + match.platformNode.osm_id + " (" + match.platformNode.getTag("name") + ")");
            }
        }

        return stopWayMatches;
    }

    /**
     * Outputs the segment ways to an OSM XML file
     */
    private static void debugOutputSegments(final OSMEntitySpace entitySpace, final LineComparison comparison) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        //output the route's shape segments
        OSMNode originNode = null, lastNode;
        for(final LineSegment mainSegment : comparison.mainLine.segments) {
            final OSMWay segmentWay = segmentSpace.createWay(null, null);
            if(originNode == null) {
                if(mainSegment.originNode != null) {
                    originNode = (OSMNode) segmentSpace.addEntity(mainSegment.originNode, OSMEntity.TagMergeStrategy.keepTags, null);
                } else {
                    originNode = segmentSpace.createNode(mainSegment.originPoint.latitude, mainSegment.originPoint.longitude, null);
                }
            }
            if(mainSegment.destinationNode != null) {
                lastNode = (OSMNode) segmentSpace.addEntity(mainSegment.destinationNode, OSMEntity.TagMergeStrategy.keepTags, null);
            } else {
                lastNode = segmentSpace.createNode(mainSegment.destinationPoint.latitude, mainSegment.destinationPoint.longitude, null);
            }
            segmentWay.appendNode(originNode);
            segmentWay.appendNode(lastNode);
            segmentWay.setTag(OSMEntity.KEY_REF, comparison.mainLine.way.getTag(OSMEntity.KEY_NAME));
            segmentWay.setTag(OSMEntity.KEY_NAME, mainSegment.getDebugName());
            OSMEntity.copyTag(comparison.mainLine.way, segmentWay, "highway");
            OSMEntity.copyTag(comparison.mainLine.way, segmentWay, "railway");
            //OSMEntity.copyTag(mai, segmentWay, OSMEntity.KEY_NAME);
            originNode = lastNode;
        }

        //output the segments matching the main route path
        for (final WaySegments matchingLine : comparison.candidateLines.values()) {
            OSMNode matchOriginNode = null, matchLastNode;
            for(final LineSegment matchingSegment : matchingLine.segments) {
                if(matchingSegment.bestMatch == null) { //skip non-matching segments
                    continue;
                }

                if(matchOriginNode == null) { //i.e. first node on line
                    if(matchingSegment.originNode != null && entitySpace.allNodes.containsKey(matchingSegment.originNode.osm_id)) {
                        matchOriginNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(matchingSegment.originNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
                    } else {
                        matchOriginNode = segmentSpace.createNode(matchingSegment.originPoint.latitude, matchingSegment.originPoint.longitude, null);
                    }
                }
                if(matchingSegment.destinationNode != null && entitySpace.allNodes.containsKey(matchingSegment.destinationNode.osm_id)) {
                    matchLastNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(matchingSegment.destinationNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
                } else {
                    matchLastNode = segmentSpace.createNode(matchingSegment.destinationPoint.latitude, matchingSegment.destinationPoint.longitude, null);
                }

                final OSMWay matchingSegmentWay = segmentSpace.createWay(null, null);
                matchingSegmentWay.appendNode(matchOriginNode);
                matchingSegmentWay.appendNode(matchLastNode);
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, "highway");
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, "railway");
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, OSMEntity.KEY_NAME);
                if(matchingSegment.bestMatch != null) {
                    matchingSegmentWay.setTag("note", "With: " + matchingSegment.bestMatch.mainSegment.getDebugName() + ", DP: " + format.format(matchingSegment.bestMatch.dotProduct) + ", DIST: " + format.format(matchingSegment.bestMatch.orthogonalDistance));
                } else {
                    matchingSegmentWay.setTag("note", "No matches");
                    matchingSegmentWay.setTag("tiger:reviewed", "no");
                }
                matchOriginNode = matchLastNode;
            }
                        /*for(WaySegments otherSegments : comparison.allCandidateSegments.values()) {
                            for(LineSegment otherSegment : otherSegments.segments) {
                                segmentSpace.addEntity(otherSegment.segmentWay, OSMEntitySpace.EntityTagMergeStrategy.keepTags, null);
                            }
                        }*/


        }
        segmentSpace.outputXml("segments" + comparison.mainLine.way.osm_id + ".osm");
    }
    private static void conflateStops(final OSMRelation routeMaster, final OSMEntitySpace importSpace, final Region inRegion, final double conflictDistance) throws InvalidArgumentException {
        //fetch all possible useful ways that intersect the route's combined bounding box
        final OverpassConverter converter = new OverpassConverter();
        try {
            final String query;
            final String routeType = routeMaster.getTag(OSMEntity.KEY_ROUTE_MASTER);
            switch (routeType) {
                case OSMEntity.TAG_BUS: //bus stops are always nodes
                    query = converter.queryForBoundingBox("[\"highway\"=\"bus_stop\"]", inRegion, 0.0, OSMEntity.OSMType.node);
                    break;
                case OSMEntity.TAG_LIGHT_RAIL:
                case OSMEntity.TAG_TRAIN:
                case OSMEntity.TAG_SUBWAY:
                case OSMEntity.TAG_TRAM:
                    query = converter.queryForBoundingBox("[\"railway\"=\"platform\"]", inRegion, 0.0, null);
                    break;
                default:
                    System.out.println("Unsupported route type \"" + routeType + "\"");
                    return;
            }
            converter.fetchFromOverpass(query);
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
            return;
        }

        final OSMEntitySpace existingStopsSpace = converter.getEntitySpace();
        importSpace.mergeWithSpace(existingStopsSpace, OSMEntity.TagMergeStrategy.keepTags, null);
        if(Math.random() < 2) {
         //   return;
        }

        //create a list of the stops in the given route master
        final List<OSMEntity> routeStopsToImport = new ArrayList<>(routeMaster.getMembers().size() * 64);
        for(final OSMRelation.OSMRelationMember routeMasterMember : routeMaster.getMembers()) {
            if(routeMasterMember.member instanceof OSMRelation) {
                final OSMRelation subRoute = (OSMRelation) routeMasterMember.member;
                final List<OSMRelation.OSMRelationMember> stops = subRoute.getMembers("platform");
                for (final OSMRelation.OSMRelationMember stopMember : stops) {
                    routeStopsToImport.add(stopMember.member);
                    importSpace.addEntity(stopMember.member, OSMEntity.TagMergeStrategy.keepTags, null);
                }
            }
        }
        //and another list of stops in the existing OSM data
        final Collection<OSMEntity> existingStopsInArea = existingStopsSpace.allEntities.values();

        System.out.println(existingStopsInArea.size() + " STOPS TO EXIST");

        //and compare them to the existing OSM data
        for(final OSMEntity importStop : routeStopsToImport) {
            final String importGtfsId = importStop.getTag("gtfs:stop_id");
            final String importRefTag = importStop.getTag(OSMEntity.KEY_REF);
            double importRefTagNumeric;
            try {
                assert importRefTag != null;
                importRefTagNumeric = Double.parseDouble(importRefTag);
            } catch(NumberFormatException e) {
                importRefTagNumeric = Double.MAX_VALUE;
            }

            final String gtfsIdTag = "gtfs:stop_id";
            for(final OSMEntity existingStopPlatform : existingStopsInArea) {
                final String existingGtfsId = existingStopPlatform.getTag(gtfsIdTag);

                assert importGtfsId != null;

                //if the GTFS id or ref match, merge the existing stop with the import stop's data
                final Point existingStopLocation = new Point(existingStopPlatform.getCentroid().latitude, existingStopPlatform.getCentroid().longitude);
                OSMEntity mergedStopEntity = null;
                if(importGtfsId.equals(existingGtfsId)) {
                    mergedStopEntity = importSpace.mergeEntities(existingStopPlatform.osm_id, importStop.osm_id);
                    //System.out.println("GTFS id match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(gtfsIdTag) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                } else if(existingStopPlatform.hasTag(OSMEntity.KEY_REF)) { //try matching by ref if no importer id
                    final String existingRefTag = existingStopPlatform.getTag(OSMEntity.KEY_REF);
                    //TODO also match based on import dataset
                    assert existingRefTag != null;
                    if(existingRefTag.trim().equals(importRefTag)) { //string match
                        //System.out.println("Ref string match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                        mergedStopEntity = importSpace.mergeEntities(existingStopPlatform.osm_id, importStop.osm_id);
                    } else if(importRefTagNumeric != Double.MAX_VALUE) { //try doing a basic numeric match if strings don't match (special case for already-imported King County metro data)
                        try {
                            final double existingRefTagNumeric = Double.parseDouble(existingRefTag);
                            if(existingRefTagNumeric == importRefTagNumeric) {
                                //System.out.println("Ref numeric match! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                                mergedStopEntity = importSpace.mergeEntities(existingStopPlatform.osm_id, importStop.osm_id);
                            }
                        } catch(NumberFormatException ignored) {}
                    }
                }

                if(mergedStopEntity == null) { //if no matching stops found, check that the import data doesn't conflict with existing stops
                    final double stopDistance = Point.distance(importStop.getCentroid(), existingStopPlatform.getCentroid());
                    if(stopDistance < conflictDistance) {
                        System.out.println("Within distance! " + existingStopPlatform.osm_id + ": " + existingStopPlatform.getTag(OSMEntity.KEY_REF) + "/" + existingStopPlatform.getTag(OSMEntity.KEY_NAME));
                        importStop.setTag("gtfs:conflict", "yes");
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
