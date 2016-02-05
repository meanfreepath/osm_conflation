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
        final RouteConflator.LineComparisonOptions options = RouteConflator.wayMatchingOptions;
        options.boundingBoxSize = 50.0;
        options.maxSegmentLength = 10.0;
        options.setMaxSegmentAngle(30.0);
        options.maxSegmentOrthogonalDistance = 3.0 * options.maxSegmentLength;
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
                final RouteConflator routeConflator = new RouteConflator(importRouteMaster);

                //fetch all ways from OSM that are within the route master's bounding box
                boolean dataFetched = routeConflator.downloadRegionsForImportDataset(workingEntitySpace);
                if(!dataFetched) {
                    System.out.println("Unable to fetch data for Route #" + importRouteMaster.getTag(OSMEntity.KEY_REF) + "(\"" + importRouteMaster.getTag(OSMEntity.KEY_NAME) + "\")");
                    continue;
                }

                //fetch all existing stops from OSM in the route's bounding box
                final StopConflator stopConflator = new StopConflator(routeConflator);
                stopConflator.conflateStops(20.0);

                //and match the subroutes' routePath to the downloaded OSM ways.  Also matches the stops in the route to their nearest matching way
                routeConflator.conflateRoutePaths(stopConflator);

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
}
