package com.company;

import OSM.*;
import OSM.Point;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;

public class Main {

    private static class StopWayMatch {
        public enum MatchType {
            none, primaryStreet, crossStreet, proximity
        };
        public final static Comparator<StopWayMatch> comp = new Comparator<StopWayMatch>() {
            @Override
            public int compare(StopWayMatch o1, StopWayMatch o2) {
                return o1.distance < o2.distance ? -1 : 1;
            }
        };

        public final OSMNode platformNode;
        public final LineSegment candidateSegment;
        public final MatchType matchType;
        public final double distance;

        public StopWayMatch(OSMNode platform, LineSegment segment, double distance, MatchType matchType) {
            platformNode = platform;
            candidateSegment = segment;
            this.matchType = matchType;
            this.distance = distance;
        }
    }

    public static void main(String[] args) {

        final boolean debugEnabled = true;

        OSMEntitySpace mainSpace = new OSMEntitySpace(1024);
        try {
            mainSpace.loadFromXML("routes.osm");

            OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
            for(OSMRelation relation : mainSpace.allRelations.values()) {
                OverpassConverter converter = new OverpassConverter();

                if(relation.getTag("type").equals("route")) {
                    List<OSMRelation.OSMRelationMember> members = relation.getMembers("");
                    OSMWay routePath = (OSMWay) members.get(0).member;
                    /*if(routePath.osm_id != -600) {
                        continue;
                    }*/
                    //fetch all possible useful ways that intersect the route's bounding box
                    converter.fetchFromOverpass(routePath, "[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", 0.0004);

                    //Break the candidate ways into LineSegments and match their geometries to the main route line
                    LineComparison.ComparisonOptions options = new LineComparison.ComparisonOptions();
                    options.maxSegmentLength = 10.0;
                    options.setMaxSegmentAngle(10.0);
                    options.maxSegmentOrthogonalDistance = options.maxSegmentLength;
                    options.maxSegmentMidPointDistance = 2.0 * options.maxSegmentLength;
                    LineComparison comparison = new LineComparison(routePath, new ArrayList<>(converter.getEntitySpace().allWays.values()), options, debugEnabled);
                    comparison.matchLines(relation);

                    //DEBUG: add all the matching lines to the relation
                    int segMatchCount;
                    for(final LineComparison.LineMatch match : comparison.matchingLines.values()) {
                        segMatchCount = match.matchingSegments.size();
                        if(segMatchCount > 0) {
                            //System.out.println(match.line.line.osm_id + "/" + match.line.line.getTag("name") + ": " + segMatchCount + " match: " + 0.1 * Math.round(10.0 * avgDistance) + "/" + (0.01 * Math.round(100.0 * avgDotProduct)));
                            if(segMatchCount > 2 && match.getAvgDistance() < 8.0 && match.getAvgDotProduct() > 0.9) {
                                relation.addMember(match.line.line, "");
                            }
                        }
                    }


                    //TODO: run additional checks to ensure the matches are contiguous


                    //TODO: split ways that only partially overlap the main way


                    //also, match the stops in the relation to their nearest matching way
                    List<OSMRelation.OSMRelationMember> routeStops = relation.getMembers("platform");
                    final String stopNameStreetSeparator = " & ";
                    StreetNameMatcher matcher = new StreetNameMatcher(Locale.US);
                    HashMap<Long, List<StopWayMatch>> stopWayMatches = new HashMap<>(routeStops.size());
                    final double maxDistanceFromPlatformToWay = 25.0, stopNodeTolerance = 2.0;
                    final double latitudeDelta = -maxDistanceFromPlatformToWay / Point.DEGREE_DISTANCE_AT_EQUATOR, longitudeDelta = latitudeDelta / Math.cos(Math.PI * comparison.mainWaySegments.segments.get(0).originPoint.latitude / 180.0);

                    for(OSMRelation.OSMRelationMember member : routeStops) {
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

                        //find the nearest OSM ways to the platform
                        final ArrayList<StopWayMatch> stopMatches = new ArrayList<>(16);
                        stopWayMatches.put(stopPlatform.osm_id, stopMatches);
                        Region searchRegion = stopPlatform.getBoundingBox().regionInset(latitudeDelta, longitudeDelta);
                        //for(WaySegments line : comparison.allCandidateSegments.values()) {
                        for(final LineComparison.LineMatch matchingLine : comparison.matchingLines.values()) {
                            //check the line's bounding box intersects
                            if(Region.intersects(matchingLine.line.line.getBoundingBox(), searchRegion)) {
                                //find the nearest segment to the platform on the way
                                LineSegment nearestSegment = null;
                                final double stopX = stopPlatform.getLon(), stopY = stopPlatform.getLat();
                                double minDistance = 9999999999.0;
                                Point closestPoint;
                                for(final SegmentMatch segmentMatch : matchingLine.matchingSegments) {
                                    //System.out.println("Segment " + segmentMatch.matchingSegment.getDebugName() + ": " + segmentMatch.consolidatedMatches);
                                    closestPoint = segmentMatch.matchingSegment.closestPointToPoint(stopPlatform.getCentroid());
                                    final double segmentDistance = Point.distance(closestPoint, stopPlatform.getCentroid());
                                    if(segmentDistance < minDistance) {
                                        nearestSegment = segmentMatch.matchingSegment;
                                        minDistance = segmentDistance;
                                    }
                                }

                                //skip this line if no segment is found (unlikely)
                                if(nearestSegment == null) {
                                    continue;
                                }

                                StopWayMatch matchObject = null;
                                //also try matching based on the stop name and way names, preferring the stop's first name component
                                if(stopName != null) {
                                    final String wayName = matchingLine.line.line.getTag(OSMEntity.KEY_NAME);
                                    if (wayName != null) { //if the name matches use this way
                                        if (stopStreetNames[0].equals(wayName)) {
                                            matchObject = new StopWayMatch(stopPlatform, nearestSegment, minDistance, StopWayMatch.MatchType.primaryStreet);
                                        } else if (stopStreetNames.length > 1 && stopStreetNames[1].equals(wayName)) {
                                            matchObject = new StopWayMatch(stopPlatform, nearestSegment, minDistance, StopWayMatch.MatchType.crossStreet);
                                        }
                                    }
                                }

                                //if matching by name fails, try going by nearest way
                                if(matchObject == null) {
                                    if(minDistance < maxDistanceFromPlatformToWay) {
                                        matchObject = new StopWayMatch(stopPlatform, nearestSegment, minDistance, StopWayMatch.MatchType.proximity);
                                    } else {
                                        matchObject = new StopWayMatch(stopPlatform, nearestSegment, minDistance, StopWayMatch.MatchType.none);
                                    }
                                }
                                stopMatches.add(matchObject);
                            }
                        }
                    }

                    //now pick the best matches for each platform
                    for(Map.Entry<Long, List<StopWayMatch>> matches : stopWayMatches.entrySet()) {
                        if(matches.getValue().size() == 0) {
                            continue;
                        }
                        Collections.sort(matches.getValue(), StopWayMatch.comp);

                        StopWayMatch firstMatch = matches.getValue().get(0);
                        //System.out.println("Platform: " + firstMatch.platformNode.getTag("name") + ":::");

                        StopWayMatch bestMatch = null;
                        for(StopWayMatch otherMatch : matches.getValue()) {
                            if(otherMatch.matchType == StopWayMatch.MatchType.primaryStreet) { //always use the primary street (based off the name) as the best match
                                bestMatch = otherMatch;
                                break;
                            }

                            //the next-best match is the closest way
                            bestMatch = otherMatch;
                        }

                        //if the nearestPoint is reasonably close to the nearest existing node, just assign that node as the stop position
                        final OSMNode nearestNode;
                        final Point nearestPointOnSegment = bestMatch.candidateSegment.closestPointToPoint(bestMatch.platformNode.getCentroid());
                        if(bestMatch.candidateSegment.originNode != null && Point.distance(nearestPointOnSegment, bestMatch.candidateSegment.originNode.getCentroid()) < stopNodeTolerance) {
                            nearestNode = bestMatch.candidateSegment.originNode;
                        } else if(bestMatch.candidateSegment.destinationNode != null && Point.distance(nearestPointOnSegment, bestMatch.candidateSegment.destinationNode.getCentroid()) < stopNodeTolerance) {
                            nearestNode = bestMatch.candidateSegment.destinationNode;
                        } else { //if no existing nearby nodes, create a new node
                            nearestNode = OSMNode.create(nearestPointOnSegment);
                            bestMatch.candidateSegment.parentSegments.insertNode(nearestNode, bestMatch.candidateSegment);
                            converter.getEntitySpace().addEntity(nearestNode, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                        }

                        //and if the name matches, then add a node on the nearest point and add to the relation and the way
                        nearestNode.setTag(OSMEntity.KEY_NAME, bestMatch.platformNode.getTag(OSMEntity.KEY_NAME));
                        nearestNode.setTag(OSMEntity.KEY_REF, bestMatch.platformNode.getTag(OSMEntity.KEY_REF));
                        nearestNode.setTag("public_transport", "stop_position");
                        nearestNode.setTag(relation.getTag(OSMEntity.KEY_ROUTE), OSMEntity.TAG_YES);

                        //and add the stop position to the route relation
                        relation.addMember(nearestNode, "stop");
                    }

                    List<OSMEntity> conflictingEntities = new ArrayList<>(16);
                    converter.getEntitySpace().addEntity(relation, OSMEntitySpace.EntityMergeStrategy.mergeTags, conflictingEntities);
                    converter.getEntitySpace().outputXml("newresult" + routePath.osm_id + ".osm");
                    relationSpace.addEntity(relation, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);

                    //DEBUG: output the segment ways to an OSM XML file
                    if(debugEnabled) {
                        OSMEntitySpace segmentSpace = new OSMEntitySpace(65536);
                        final DecimalFormat format = new DecimalFormat("#.###");

                        //output the route's shape segments
                        OSMNode originNode = null, lastNode;
                        for(final LineSegment mainSegment : comparison.mainWaySegments.segments) {
                            final OSMWay segmentWay = OSMWay.create();
                            if (originNode == null) {
                                originNode = OSMNode.create(mainSegment.originPoint);
                            }
                            lastNode = OSMNode.create(mainSegment.destinationPoint);
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
                        for (final LineComparison.LineMatch matchingLine : comparison.matchingLines.values()) {
                            OSMNode matchOriginNode = null, matchLastNode;
                            for (SegmentMatch matchingSegment : matchingLine.matchingSegments) {
                                final OSMWay matchingSegmentWay = OSMWay.create();
                                if(matchOriginNode == null) {
                                    matchOriginNode = OSMNode.create(matchingSegment.matchingSegment.originPoint);
                                }
                                matchLastNode = OSMNode.create(matchingSegment.matchingSegment.destinationPoint);
                                matchingSegmentWay.appendNode(matchOriginNode);
                                matchingSegmentWay.appendNode(matchLastNode);
                                OSMEntity.copyTag(matchingSegment.matchingSegment.parentSegments.line, matchingSegmentWay, "highway");
                                OSMEntity.copyTag(matchingSegment.matchingSegment.parentSegments.line, matchingSegmentWay, "railway");
                                OSMEntity.copyTag(matchingSegment.matchingSegment.parentSegments.line, matchingSegmentWay, OSMEntity.KEY_NAME);
                                matchingSegmentWay.setTag("note", "With: " + matchingSegment.mainSegment.getDebugName() + ", DP: " + format.format(matchingSegment.dotProduct) + ", DIST: " + format.format(matchingSegment.orthogonalDistance));
                                segmentSpace.addEntity(matchingSegmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                                matchOriginNode = matchLastNode;
                            }
                            /*for(WaySegments otherSegments : comparison.allCandidateSegments.values()) {
                                for(LineSegment otherSegment : otherSegments.segments) {
                                    segmentSpace.addEntity(otherSegment.segmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                                }
                            }*/

                        }
                        segmentSpace.outputXml("segments" + routePath.osm_id + ".osm");
                    }

                }
            }

            relationSpace.outputXml("relation.osm");
            //mainSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        }

/*
        try {
            converter.getEntitySpace().outputXml("result.osm");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        }//*/
    }
}
