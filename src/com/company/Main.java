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

        OSMEntitySpace mainSpace = new OSMEntitySpace(1024);
        try {
            mainSpace.loadFromXML("routes.osm");

            //first create a list of all the route_master relations
            final List<OSMRelation> routeMasterRelations = new ArrayList<>(mainSpace.allRelations.size());
            for(final OSMRelation relation : mainSpace.allRelations.values()) {
                if(relation.hasTag(OSMEntity.KEY_TYPE) && relation.getTag(OSMEntity.KEY_TYPE).equals(OSMEntity.TAG_ROUTE_MASTER)) {
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
                for(final OSMRelation.OSMRelationMember member : masterMembers) {
                    if(member.member.hasTag(OSMEntity.KEY_TYPE) && member.member.getTag(OSMEntity.KEY_TYPE).equals(OSMEntity.TAG_ROUTE)) {
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
                converter.fetchFromOverpass(combinedBoundingBox, "[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", 0.0004);

                for(final OSMRelation relation : routeMasterSubRoutes) {
                    final List<OSMRelation.OSMRelationMember> members = relation.getMembers("");
                    final OSMWay routePath = (OSMWay) members.get(0).member;
                    if (routePath.osm_id != -600) {
                        //continue;
                    }

                    //Break the candidate ways into LineSegments and match their geometries to the main route line
                    final LineComparison.ComparisonOptions options = new LineComparison.ComparisonOptions();
                    options.maxSegmentLength = 10.0;
                    options.setMaxSegmentAngle(10.0);
                    options.maxSegmentOrthogonalDistance = options.maxSegmentLength;
                    options.maxSegmentMidPointDistance = 2.0 * options.maxSegmentLength;
                    LineComparison comparison = new LineComparison(routePath, new ArrayList<>(converter.getEntitySpace().allWays.values()), options, debugEnabled);
                    comparison.matchLines(relation);

                    //DEBUG: add all the matching lines to the relation
                    /*int segMatchCount;
                    for(final WaySegments line : comparison.matchingLines.values()) {
                        segMatchCount = line.matchObject.matchingSegments.size();
                        if(segMatchCount > 0) {
                            //System.out.println(match.line.line.osm_id + "/" + match.line.line.getTag("name") + ": " + segMatchCount + " match: " + 0.1 * Math.round(10.0 * avgDistance) + "/" + (0.01 * Math.round(100.0 * avgDotProduct)));
                            if(segMatchCount > 1 && line.matchObject.getAvgDistance() < 8.0 && Math.abs(line.matchObject.getAvgDotProduct()) > 0.9) {
                                relation.addMember(line.line, "");
                            }
                        }
                    }*/


                    //also, match the stops in the relation to their nearest matching way
                    final List<StopWayMatch> allStopMatches = matchStopsToWays(relation, comparison.matchingLines.values(), converter.getEntitySpace());

                    //update the primary entity space to determine all connecting ways
                    converter.getEntitySpace().generateWayNodeMapping(false);

                    //now find the optimal path from the first stop to the last stop, using the provided ways
                    final PathTree pathList = new PathTree();
                    pathList.findPaths(allStopMatches, comparison.matchingLines);

                    //TODO: split ways that only partially overlap the main way


                    //finally, add the matched ways to the relation's members
                    relation.removeMember(routePath);
                    for (final PathSegment pathSegment : pathList.bestPath.pathSegments) {
                        relation.addMember(pathSegment.line.line, "");
                    }


                    List<OSMEntity> conflictingEntities = new ArrayList<>(16);
                    converter.getEntitySpace().addEntity(relation, OSMEntitySpace.EntityMergeStrategy.mergeTags, conflictingEntities);
                    converter.getEntitySpace().outputXml("newresult" + routePath.osm_id + ".osm");
                    relationSpace.addEntity(relation, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);

                    if (debugEnabled) {
                        debugOutputSegments(converter.getEntitySpace(), comparison);
                    }

                }

                relationSpace.outputXml("relation.osm");
            }
            //mainSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        }

    }

    private static List<StopWayMatch> matchStopsToWays(final OSMRelation routeRelation, final Collection<WaySegments> candidateLines, final OSMEntitySpace entitySpace) {
        final List<OSMRelation.OSMRelationMember> routeStops = routeRelation.getMembers("platform");
        final String stopNameStreetSeparator = " & ";
        final StreetNameMatcher matcher = new StreetNameMatcher(Locale.US);
        final List<StopWayMatch> stopWayMatches = new ArrayList<>(routeStops.size());
        final double latitudeDelta = -StopWayMatch.maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * routeStops.get(0).member.getCentroid().latitude / 180.0);

        //loop through the route's stops, determining the best-matching way for each one
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
            final StopWayMatch wayMatch = new StopWayMatch(stopPlatform);
            stopWayMatches.add(wayMatch);

            //find the nearest OSM ways to the platform
            final Region searchRegion = stopPlatform.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
            final Point platformCentroid = stopPlatform.getCentroid();
            for(final WaySegments matchingLine : candidateLines) {
                //check the line's bounding box intersects
                if(Region.intersects(matchingLine.line.getBoundingBox(), searchRegion)) {
                    //find the nearest segment to the platform on the way
                    LineSegment nearestSegment = null;

                    double minDistance = Double.MAX_VALUE;
                    Point closestPoint;
                    for(final SegmentMatch segmentMatch : matchingLine.matchObject.matchingSegments) {
                        //System.out.println("Segment " + segmentMatch.matchingSegment.getDebugName() + ": " + segmentMatch.consolidatedMatches);
                        closestPoint = segmentMatch.matchingSegment.closestPointToPoint(platformCentroid);
                        final double segmentDistance = Point.distance(closestPoint, platformCentroid);
                        if(segmentDistance < minDistance) {
                            nearestSegment = segmentMatch.matchingSegment;
                            minDistance = segmentDistance;
                        }
                    }

                    //skip this line if no segment is found (unlikely)
                    if(nearestSegment == null) {
                        continue;
                    }

                    boolean matchedByName = false;
                    //also try matching based on the stop name and way names, preferring the stop's first name component
                    if(stopName != null) {
                        final String wayName = matchingLine.line.getTag(OSMEntity.KEY_NAME);
                        if (wayName != null) { //if the name matches use this way
                            if (stopStreetNames[0].equals(wayName)) {
                                wayMatch.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.primaryStreet);
                                matchedByName = true;
                            } else if (stopStreetNames.length > 1 && stopStreetNames[1].equals(wayName)) {
                                wayMatch.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.crossStreet);
                                matchedByName = true;
                            }
                        }
                    }

                    //if matching by name fails, try going by nearest way
                    if(!matchedByName) {
                        if(minDistance < StopWayMatch.maxDistanceFromPlatformToWay) {
                            wayMatch.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.proximity);
                        } else {
                            wayMatch.addWayMatch(nearestSegment, minDistance, StopWayMatch.MatchType.none);
                        }
                    }
                }
            }
        }

        //now pick the best matches for each platform
        for(final StopWayMatch match : stopWayMatches) {
            if(match.matches.size() == 0) {
                continue;
            }
            //determine the best-matching way for the stop
            match.chooseBestMatch();

            //Create (or update an existing node) to serve as the stop position node for the platform
            final Point nearestPointOnSegment = match.bestMatch.candidateSegment.closestPointToPoint(match.platformNode.getCentroid());
            final OSMNode nearestNode = match.bestMatch.candidateSegment.parentSegments.insertNode(OSMNode.create(nearestPointOnSegment), match.bestMatch.candidateSegment, StopWayMatch.stopNodeTolerance);
            entitySpace.addEntity(nearestNode, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);

            //then add a node on the nearest point and add to the relation and the way
            nearestNode.setTag(OSMEntity.KEY_NAME, match.platformNode.getTag(OSMEntity.KEY_NAME));
            nearestNode.setTag(OSMEntity.KEY_REF, match.platformNode.getTag(OSMEntity.KEY_REF));
            nearestNode.setTag("public_transport", "stop_position");
            nearestNode.setTag(routeRelation.getTag(OSMEntity.KEY_ROUTE), OSMEntity.TAG_YES);

            //add to the WaySegments object as well
            match.bestMatch.candidateSegment.parentSegments.matchObject.stopMatches.add(match.bestMatch);

            //and add the stop position to the route relation
            match.stopPositionNode = nearestNode;
            routeRelation.addMember(nearestNode, "stop");
        }

        return stopWayMatches;
    }

    /**
     * Outputs the segment ways to an OSM XML file
     */
    private static void debugOutputSegments(final OSMEntitySpace entitySpace, final LineComparison comparison) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allEntities.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        //output the route's shape segments
        OSMNode originNode = null, lastNode;
        for(final LineSegment mainSegment : comparison.mainWaySegments.segments) {
            final OSMWay segmentWay = OSMWay.create();
            if(originNode == null) {
                if(mainSegment.originNode != null) {
                    originNode = mainSegment.originNode;
                } else {
                    originNode = OSMNode.create(mainSegment.originPoint);
                }
            }
            if(mainSegment.destinationNode != null) {
                lastNode = mainSegment.destinationNode;
            } else {
                lastNode = OSMNode.create(mainSegment.destinationPoint);
            }
            segmentWay.appendNode(originNode);
            segmentWay.appendNode(lastNode);
            segmentWay.setTag(OSMEntity.KEY_REF, comparison.mainWaySegments.line.getTag(OSMEntity.KEY_NAME));
            segmentWay.setTag(OSMEntity.KEY_NAME, mainSegment.getDebugName());
            OSMEntity.copyTag(comparison.mainWaySegments.line, segmentWay, "highway");
            OSMEntity.copyTag(comparison.mainWaySegments.line, segmentWay, "railway");
            //OSMEntity.copyTag(mai, segmentWay, OSMEntity.KEY_NAME);
            segmentSpace.addEntity(segmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
            originNode = lastNode;
        }

        //output the segments matching the main route path
        for (final WaySegments matchingLine : comparison.matchingLines.values()) {
            OSMNode matchOriginNode = null, matchLastNode;
            for(final LineSegment matchingSegment : matchingLine.segments) {
                if(matchingSegment.bestMatch == null) { //skip non-matching segments
                    continue;
                }

                if(matchOriginNode == null) { //i.e. first node on line
                    if(matchingSegment.originNode != null && entitySpace.allNodes.containsKey(matchingSegment.originNode.osm_id)) {
                        matchOriginNode = entitySpace.allNodes.get(matchingSegment.originNode.osm_id);
                    } else {
                        matchOriginNode = OSMNode.create(matchingSegment.originPoint);
                    }
                }
                if(matchingSegment.destinationNode != null && entitySpace.allNodes.containsKey(matchingSegment.destinationNode.osm_id)) {
                    matchLastNode = entitySpace.allNodes.get(matchingSegment.destinationNode.osm_id);
                } else {
                    matchLastNode = OSMNode.create(matchingSegment.destinationPoint);
                }

                final OSMWay matchingSegmentWay = OSMWay.create();
                matchingSegmentWay.appendNode(matchOriginNode);
                matchingSegmentWay.appendNode(matchLastNode);
                OSMEntity.copyTag(matchingSegment.parentSegments.line, matchingSegmentWay, "highway");
                OSMEntity.copyTag(matchingSegment.parentSegments.line, matchingSegmentWay, "railway");
                OSMEntity.copyTag(matchingSegment.parentSegments.line, matchingSegmentWay, OSMEntity.KEY_NAME);
                if(matchingSegment.bestMatch != null) {
                    matchingSegmentWay.setTag("note", "With: " + matchingSegment.bestMatch.mainSegment.getDebugName() + ", DP: " + format.format(matchingSegment.bestMatch.dotProduct) + ", DIST: " + format.format(matchingSegment.bestMatch.orthogonalDistance));
                } else {
                    matchingSegmentWay.setTag("note", "No matches");
                    matchingSegmentWay.setTag("tiger:reviewed", "no");
                }
                segmentSpace.addEntity(matchingSegmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                matchOriginNode = matchLastNode;
            }
                        /*for(WaySegments otherSegments : comparison.allCandidateSegments.values()) {
                            for(LineSegment otherSegment : otherSegments.segments) {
                                segmentSpace.addEntity(otherSegment.segmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                            }
                        }*/


        }
        segmentSpace.outputXml("segments" + comparison.mainWaySegments.line.osm_id + ".osm");
    }
}
