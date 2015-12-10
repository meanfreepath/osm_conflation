package com.company;

import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class Main {

    public static void main(String[] args) {

        final boolean debugEnabled = true;

        final OSMEntitySpace mainSpace = new OSMEntitySpace(1024);
        try {
            mainSpace.loadFromXML("routes.osm");

            /*OSMTaskManagerExporter exporter = new OSMTaskManagerExporter(mainSpace);
            exporter.outputForOSMTaskingManager("boxes", "https://www.meanfreepath.com/kcstops/");
            if(Math.random() < 2) {
                return;
            }*/

            //first create a list of all the route_master relations
            final List<OSMRelation> routeMasterRelations = new ArrayList<>(mainSpace.allRelations.size());
            String relationType;
            for(final OSMRelation relation : mainSpace.allRelations.values()) {
                relationType = relation.getTag(OSMEntity.KEY_TYPE);
                if(relationType != null && relationType.equals(OSMEntity.TAG_ROUTE_MASTER)) {
                    routeMasterRelations.add(relation);
                }
            }

            //loop through the route masters, processing their subroutes in one entity space
            for(final OSMRelation routeMaster : routeMasterRelations) {
                final List<OSMRelation.OSMRelationMember> masterMembers = routeMaster.getMembers();
                if(masterMembers.size() == 0) {
                    System.out.println("No routes for Master " + routeMaster.getTag("name"));
                    continue;
                }
                final List<OSMRelation> routeMasterSubRoutes = new ArrayList<>(masterMembers.size());
                Region combinedBoundingBox = null;
                String memberType;
                for(final OSMRelation.OSMRelationMember member : masterMembers) {
                    memberType = member.member.getTag(OSMEntity.KEY_TYPE);
                    if(memberType != null && memberType.equals(OSMEntity.TAG_ROUTE)) {
                        routeMasterSubRoutes.add((OSMRelation) member.member);
                        if(combinedBoundingBox == null) {
                            combinedBoundingBox = member.member.getBoundingBox().clone();
                        } else {
                            combinedBoundingBox.combinedBoxWithRegion(member.member.getBoundingBox());
                        }
                    }
                }

                final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);

                //fetch all possible useful ways that intersect the route's combined bounding box
                final OverpassConverter converter = new OverpassConverter();
                final String query = converter.queryForBoundingBox("[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", combinedBoundingBox, 0.0004, OSMEntity.OSMType.way);
                converter.fetchFromOverpass(query);
                final OSMEntitySpace existingDataSpace = converter.getEntitySpace();

                for(final OSMRelation relation : routeMasterSubRoutes) {
                    final List<OSMRelation.OSMRelationMember> members = relation.getMembers("");
                    final OSMWay routePath = (OSMWay) members.get(0).member;
                    routePath.setTag("gtfs:ethereal", "yes");
                    if (routePath.osm_id != -464) {
                       // continue;
                    }

                    //Break the candidate ways into LineSegments and match their geometries to the main route line
                    final LineComparison.ComparisonOptions options = new LineComparison.ComparisonOptions();
                    options.boundingBoxSize = 50.0;
                    options.maxSegmentLength = 10.0;
                    options.setMaxSegmentAngle(10.0);
                    options.maxSegmentOrthogonalDistance = 1.0 * options.maxSegmentLength;
                    options.maxSegmentMidPointDistance = 4.0 * options.maxSegmentLength;
                    final LineComparison comparison = new LineComparison(routePath, new ArrayList<>(existingDataSpace.allWays.values()), options, debugEnabled);
                    comparison.matchLines();

                    //also, match the stops in the relation to their nearest matching way
                    final List<StopWayMatch> allStopMatches = matchStopsToWays(relation, comparison.candidateLines.values(), existingDataSpace);

                    //update the primary entity space to determine all connecting ways
                    existingDataSpace.generateWayNodeMapping(false);

                    //now find the optimal path from the first stop to the last stop, using the provided ways
                    final PathTree pathList = new PathTree(relation);
                    pathList.findPaths(allStopMatches, comparison.candidateLines);

                    //TODO: split ways that only partially overlap the main way


                    //finally, add the matched ways to the relation's members
                    //relation.removeMember(routePath);
                    if(pathList.bestPath != null) {
                        for (final PathSegment pathSegment : pathList.bestPath.pathSegments) {
                            relation.addMember(pathSegment.line.way, "");
                        }
                    }

                    List<OSMEntity> conflictingEntities = new ArrayList<>(16);
                    existingDataSpace.addEntity(relation, OSMEntitySpace.EntityMergeStrategy.mergeTags, conflictingEntities);
                    existingDataSpace.outputXml("newresult" + routePath.osm_id + ".osm");
                    relationSpace.addEntity(relation, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);

                    if (debugEnabled) {
                        debugOutputSegments(converter.getEntitySpace(), comparison);
                    }
                }

                relationSpace.outputXml("relation.osm");
            }
            //mainSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }

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
                if(!Region.intersects(matchingLine.way.getBoundingBox(), searchRegion)) {
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
            final OSMNode nearestNode = bestSegment.parentSegments.insertNode(existingEntitySpace.createNode(nearestPointOnSegment.latitude, nearestPointOnSegment.longitude, null), bestSegment, StopWayMatch.stopNodeTolerance);

            //then add a node on the nearest point and add to the relation and the way
            nearestNode.setTag(OSMEntity.KEY_NAME, match.platformNode.getTag(OSMEntity.KEY_NAME));
            nearestNode.setTag(OSMEntity.KEY_REF, match.platformNode.getTag(OSMEntity.KEY_REF));
            nearestNode.setTag("public_transport", "stop_position");
            nearestNode.setTag(routeRelation.getTag(OSMEntity.KEY_ROUTE), OSMEntity.TAG_YES);

            //add to the WaySegments object as well
            bestSegment.parentSegments.matchObject.addStopMatch(match);

            //and add the stop position to the route relation
            match.stopPositionNode = nearestNode;
            routeRelation.addMember(nearestNode, "stop");

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
                    originNode = segmentSpace.cloneNode(mainSegment.originNode);
                } else {
                    originNode = segmentSpace.createNode(mainSegment.originPoint.latitude, mainSegment.originPoint.longitude, null);
                }
            }
            if(mainSegment.destinationNode != null) {
                lastNode = segmentSpace.cloneNode(mainSegment.destinationNode);
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
                        matchOriginNode = segmentSpace.cloneNode(entitySpace.allNodes.get(matchingSegment.originNode.osm_id));
                    } else {
                        matchOriginNode = segmentSpace.createNode(matchingSegment.originPoint.latitude, matchingSegment.originPoint.longitude, null);
                    }
                }
                if(matchingSegment.destinationNode != null && entitySpace.allNodes.containsKey(matchingSegment.destinationNode.osm_id)) {
                    matchLastNode = segmentSpace.cloneNode(entitySpace.allNodes.get(matchingSegment.destinationNode.osm_id));
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
                                segmentSpace.addEntity(otherSegment.segmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                            }
                        }*/


        }
        segmentSpace.outputXml("segments" + comparison.mainLine.way.osm_id + ".osm");
    }

}
