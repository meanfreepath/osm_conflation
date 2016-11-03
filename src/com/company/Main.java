package com.company;

import Conflation.Route;
import Conflation.RouteConflator;
import Conflation.StopArea;
import Conflation.StopConflator;
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
        final RouteConflator.LineComparisonOptions options = RouteConflator.wayMatchingOptions;
        options.segmentSearchBoxSize = 65.0;
        options.maxSegmentLength = 10.0;
        options.setMaxSegmentAngle(50.0);
        options.maxSegmentOrthogonalDistance = 15.0;
        options.maxSegmentMidPointDistance = Math.sqrt(options.maxSegmentOrthogonalDistance * options.maxSegmentOrthogonalDistance + 4.0 * options.maxSegmentLength * options.maxSegmentLength);

        //propagate the debug value as needed
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
        selectedRoutes.add("102615"); // E-Line
        //selectedRoutes.add("102576"); //C-Line: oneway busway issue at Seneca St (northbound), detour issue on Alaskan Way southbound
        //selectedRoutes.add("102623"); //894: Mercer Island loopy route
        //selectedRoutes.add("100184"); //31

        try {
            importSpace.loadFromXML(importFileName);

            /*final OSMTaskManagerExporter exporter = new OSMTaskManagerExporter(importSpace);
            exporter.conflateStops();
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
            importSpace = null; //data no longer needed

            //loop through the route masters, processing their subroutes in one entity space
            for(final OSMRelation importRouteMaster : importRouteMasterRelations) {
                if(!selectedRoutes.contains(importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID))) {
                    continue;
                }

                //output an OSM XML file with only the current route data
                System.out.format("Processing route %s (ref %s, GTFS id %s), %d trips…\n", importRouteMaster.getTag(OSMEntity.KEY_NAME), importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID), importRouteMaster.getMembers().size());
                if(debugEnabled) {
                    OSMEntitySpace originalRouteSpace = new OSMEntitySpace(2048);
                    originalRouteSpace.addEntity(importRouteMaster, OSMEntity.TagMergeStrategy.keepTags, null);
                    originalRouteSpace.outputXml("gtfsroute_" + importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID) + ".osm");
                    originalRouteSpace = null;
                }

                final OSMEntitySpace workingEntitySpace = new OSMEntitySpace(65536); //the entity space that all processing will occur on
                workingEntitySpace.name = "Working space";

                //create an object to handle the processing of the data for this route master
                final RouteConflator routeConflator = new RouteConflator(importRouteMaster);

                //fetch all ways from OSM that are within the route master's bounding box
                boolean dataFetched = routeConflator.downloadRegionsForImportDataset(workingEntitySpace);
                if(!dataFetched) {
                    System.out.println("Unable to fetch data for Route #" + importRouteMaster.getTag(OSMEntity.KEY_REF) + "(\"" + importRouteMaster.getTag(OSMEntity.KEY_NAME) + "\")");
                    continue;
                }

                //fetch all existing stops from OSM in the entire route's bounding box, and match them with the route's stops
                final StopConflator stopConflator = new StopConflator(routeConflator);
                stopConflator.conflateStopsWithOSM();

                //and match the subroutes' routePath to the downloaded OSM ways.  Also matches the stops in the route to their nearest matching way
                routeConflator.conflateRoutePaths(stopConflator);

                //finally, add the completed relations to their own separate file for review and upload
                final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
                for(final Route route: routeConflator.getExportRoutes()) {
                    relationSpace.addEntity(route.routeRelation, OSMEntity.TagMergeStrategy.keepTags, null);
                }
                //also include any entities that were changed during the process
                for(final OSMEntity changedEntity : workingEntitySpace.allEntities.values()) {
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
