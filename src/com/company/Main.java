package com.company;

import Conflation.Route;
import Conflation.RouteConflator;
import Conflation.StopConflator;
import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.*;

public class Main {
    private final static boolean debugEnabled = true;
    public static void main(String[] args) {

        OSMEntitySpace importSpace = new OSMEntitySpace(1024);

        //define the options for the comparison routines
        final RouteConflator.LineComparisonOptions options = new RouteConflator.LineComparisonOptions();
        options.boundingBoxSize = 50.0;
        options.maxSegmentLength = 10.0;
        options.setMaxSegmentAngle(10.0);
        options.maxSegmentOrthogonalDistance = 1.0 * options.maxSegmentLength;
        options.maxSegmentMidPointDistance = 4.0 * options.maxSegmentLength;

        //propagate the debug value as needed
        RouteConflator.debugEnabled = debugEnabled;

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
                final OSMEntitySpace workingEntitySpace = new OSMEntitySpace(32768); //the entity space that all processing will occur on

                //create an object to handle the processing of the data for this route master
                final RouteConflator routeConflator = new RouteConflator(importRouteMaster, options);

                //fetch all ways from OSM that are within the route master's bounding box
                routeConflator.downloadRegionsForImportDataset(workingEntitySpace);

                //fetch all existing stops from OSM in the route's bounding box
                routeConflator.conflateStops(20.0);

                //also, match the stops in the relation to their nearest matching way
                final long timeStartStopMatching = new Date().getTime();
                final StopConflator stopMatcher = new StopConflator(routeConflator);
                stopMatcher.matchStopsToWays();
                stopMatcher.createStopPositionsForPlatforms(workingEntitySpace);
                System.out.println("Matched stops in " + (new Date().getTime() - timeStartStopMatching) + "ms");

                //and match the subroutes' routePath to the downloaded OSM ways
                routeConflator.conflateRoutePaths(stopMatcher);

                /*for(final Route route : routeConflator.getExportRoutes()) {


                    //now find the optimal path from the first stop to the last stop, using the provided ways
                    final long timeStartPathfinding = new Date().getTime();
                    final PathTree pathList = new PathTree(relation);
                    pathList.findPaths(stopMatcher.stopWayMatches, comparison.candidateLines);
                    System.out.println("Found paths in " + (new Date().getTime() - timeStartPathfinding) + "ms");

                    //TODO: split ways that only partially overlap the main way


                    //finally, add the matched ways to the relation's members
                    //relation.removeMember(routePath);
                    if(pathList.bestPath != null) {
                        for (final PathSegment pathSegment : pathList.bestPath.pathSegments) {
                            relation.addMember(pathSegment.line.way, OSMEntity.MEMBERSHIP_DEFAULT);
                        }
                    } else { //debug: if no matched path, output the best candidates instead
                        for (final WaySegments line : comparison.candidateLines.values()) {
                            if(line.matchObject.matchingSegments.size() > 2 && line.matchObject.getAvgDotProduct() >= 0.9) {
                                relation.addMember(line.way, OSMEntity.MEMBERSHIP_DEFAULT);
                            }
                        }
                    }

                    if (debugEnabled) {
                       // debugOutputSegments(workingEntitySpace, routeConflator);
                    }
                    workingEntitySpace.outputXml("newresult" + route.routePath.osm_id + ".osm");
                }*/

                //finally, add the completed relations to their own separate file for review and upload
                final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
                for(final Route route: routeConflator.getExportRoutes()) {
                    relationSpace.addEntity(route.routeRelation, OSMEntity.TagMergeStrategy.keepTags, null);
                }
                relationSpace.addEntity(routeConflator.getExportRouteMaster(), OSMEntity.TagMergeStrategy.keepTags, null);
                relationSpace.outputXml("relation.osm");
            }
            //importSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }

    }

    /**
     * Outputs the segment ways to an OSM XML file
     *
    private static void debugOutputSegments(final OSMEntitySpace entitySpace, final RouteConflator routeConflator) throws IOException, InvalidArgumentException {
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
                matchingSegmentWay.setTag("way_id", Long.toString(matchingSegment.parentSegments.way.osm_id));
                matchingSegmentWay.setTag(OSMEntity.KEY_REF, Integer.toString(matchingSegment.segmentIndex));
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, "highway");
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, "railway");
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, OSMEntity.KEY_NAME);
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
                        }*


        }
        segmentSpace.outputXml("segments" + comparison.mainLine.way.osm_id + ".osm");
    }*/
}
