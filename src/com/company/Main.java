package com.company;

import Conflation.*;
import OSM.*;
import PathFinding.PathTree;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

public class Main {
    private final static boolean debugEnabled = false;
    public static void main(String[] args) {
        if(args.length == 0) {
            System.err.println("Missing file name argument!");
            return;
        }

        OSMEntitySpace importSpace = new OSMEntitySpace(262144);

        //define the options for the comparison routines
        final RouteConflator.LineComparisonOptions matchingOptions = new RouteConflator.LineComparisonOptions();
        matchingOptions.segmentSearchBoxSize = 65.0;
        matchingOptions.maxSegmentLength = 10.0;
        matchingOptions.setMaxSegmentAngle(50.0);
        matchingOptions.setMaxFutureVectorAngle(75.0);
        matchingOptions.maxSegmentOrthogonalDistance = 15.0;
        matchingOptions.maxSegmentMidPointDistance = Math.sqrt(matchingOptions.maxSegmentOrthogonalDistance * matchingOptions.maxSegmentOrthogonalDistance + 4.0 * matchingOptions.maxSegmentLength * matchingOptions.maxSegmentLength);

        //propagate the debug value as needed
        RouteDataManager.debugEnabled = debugEnabled;
        RouteConflator.debugEnabled = debugEnabled;
        StopConflator.debugEnabled = debugEnabled;
        StopArea.debugEnabled = debugEnabled;
        OSMEntity.debugEnabled = debugEnabled;
        PathTree.debugEnabled = debugEnabled;

        final String importFileName = args[0];

        final ArrayList<String> selectedRoutes = new ArrayList<>();
        //selectedRoutes.add("100224");
        //selectedRoutes.add("100173"); //Route 3: multiple issues (looping, etc)
        //selectedRoutes.add("100221");
        //selectedRoutes.add("100062"); //errors splitting
        //selectedRoutes.add("102581"); //D-Line: not preferring matching trunk_link near NB stop "15th Ave NW & NW Leary Way"
        //selectedRoutes.add("102615"); // E-Line
        //selectedRoutes.add("102576"); //C-Line: oneway busway issue at Seneca St (northbound), detour issue on Alaskan Way southbound
        //selectedRoutes.add("100512"); //A-Line
        //selectedRoutes.add("102548"); //B-Line
        //selectedRoutes.add("102619"); //F-Line
        //selectedRoutes.add("102623"); //894: Mercer Island loopy route
        //selectedRoutes.add("100184"); //31
        //selectedRoutes.add("100221"); //41
        selectedRoutes.add("100214"); //372

        try {
            importSpace.loadFromXML(importFileName);

            //create the working entity space for all data
            final RouteDataManager routeDataManager = new RouteDataManager(65536);

            //output all the stops in a format that works with the OSM Task Manager
            /*final OSMTaskManagerExporter exporter = new OSMTaskManagerExporter(importSpace, routeDataManager, RouteConflator.RouteType.bus);
            exporter.conflateStops(routeDataManager);
            exporter.outputForOSMTaskingManager("boxes", "https://www.meanfreepath.com/kcstops/");
            if(Math.random() < 2) {
                return;
            }//*/

            //create a list of all the route_master relations in the import dataset
            final List<OSMRelation> importRouteMasterRelations = new ArrayList<>(importSpace.allRelations.size());
            String relationType;
            for(final OSMRelation relation : importSpace.allRelations.values()) {
                relationType = relation.getTag(OSMEntity.KEY_TYPE);
                if(relationType != null && relationType.equals(OSMEntity.TAG_ROUTE_MASTER)) {
                    importRouteMasterRelations.add(relation);
                }
            }
            importSpace = null; //import data no longer needed

            //loop through the route masters, processing their subroutes in one entity space
            final List<RouteConflator> routeConflators = new ArrayList<>(importRouteMasterRelations.size());
            try {
                for (final OSMRelation importRouteMaster : importRouteMasterRelations) {
                    if (!selectedRoutes.contains(importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID))) {
                        continue;
                    }

                    //output an OSM XML file with only the current route data
                    System.out.format("Processing route %s (ref %s, GTFS id %s), %d trips…\n", importRouteMaster.getTag(OSMEntity.KEY_NAME), importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID), importRouteMaster.getMembers().size());
                    if (debugEnabled) {
                        OSMEntitySpace originalRouteSpace = new OSMEntitySpace(2048);
                        originalRouteSpace.addEntity(importRouteMaster, OSMEntity.TagMergeStrategy.keepTags, null);
                        originalRouteSpace.outputXml("gtfsroute_" + importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID) + ".osm");
                        originalRouteSpace = null;
                    }

                    //create an object to handle the processing of the data for this route_master
                    routeConflators.add(new RouteConflator(importRouteMaster, routeDataManager, matchingOptions));
                }
            } catch(InvalidArgumentException e) {
                e.printStackTrace();
                System.exit(1);
            }

            //fetch all ways from OSM that are within the routes' bounding boxes
            routeDataManager.downloadRegionsForImportDataset(routeConflators, matchingOptions);

            //fetch all existing stops from OSM in the entire route's bounding box, and match them with the route's stops
            final StopConflator stopConflator = new StopConflator();
            routeDataManager.conflateStopsWithOSM(routeConflators);

            //now run the conflation algorithms on each route_master
            for(final RouteConflator routeConflator : routeConflators) {
                //and match the subroutes' routePath to the downloaded OSM ways.  Also matches the stops in the route to their nearest matching way
                routeConflator.conflateRoutePaths(stopConflator);

                //finally, add the completed relations to their own separate file for review and upload
                final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
                for(final Route route: routeConflator.getExportRoutes()) {
                    relationSpace.addEntity(route.routeRelation, OSMEntity.TagMergeStrategy.keepTags, null);
                }
                //also include any entities that were changed during the process
                for(final OSMEntity changedEntity : routeDataManager.allEntities.values()) {
                    if(changedEntity.getAction() == OSMEntity.ChangeAction.modify) {
                        relationSpace.addEntity(changedEntity, OSMEntity.TagMergeStrategy.keepTags, null);
                    }
                }
                relationSpace.addEntity(routeConflator.getExportRouteMaster(), OSMEntity.TagMergeStrategy.keepTags, null);
                relationSpace.outputXml("relation_" + routeConflator.getExportRouteMaster().getTag(OSMEntity.KEY_REF) + ".osm");
            }
            //importSpace.outputXml("newresult.osm");
        } catch (FileNotFoundException e) {
            System.err.println("Cannot find file \"" + importFileName + "\", exiting...");
            System.exit(1);
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }
    }
}
