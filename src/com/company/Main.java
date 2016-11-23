package com.company;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMRelation;
import PathFinding.PathTree;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;

public class Main {
    private static boolean debugEnabled = false;
    private static String getHelpText() {
        Main m = new Main();
        InputStream f = m.getClass().getResourceAsStream("/cli_help.txt");
        BufferedReader r = new BufferedReader(new InputStreamReader(f));
        StringBuilder helpBuffer = new StringBuilder(1024);
        try {
            for(int c; (c = r.read()) != -1;) {
                helpBuffer.append((char) c);
            }
            r.close();
            f.close();
        } catch (IOException e) {
            e.printStackTrace();
            return "Error loading help text!\n";
        }
        return helpBuffer.toString();
    }
    public static void main(String[] args) {
        //check command line args
        String importFileName = "routes.osm";
        String configPath = "./config.txt";
        List<String> selectedRoutes = null;
        boolean outputStopsToTaskingManager = false;

        List<String> argList = new ArrayList<>(args.length);
        Collections.addAll(argList, args);
       // argList.add("-h");
        ListIterator<String> argIterator = argList.listIterator();
        while(argIterator.hasNext()) {
            switch (argIterator.next()) {
                case "f": //path to the processed GTFS file
                case "-gtfs":
                    importFileName = argIterator.next();
                    break;
                case "-c": //config file path
                case "--config":
                    configPath = argIterator.next();
                    break;
                case "-r": //requested routes
                case "--routes":
                    String gtfsRoutes[] = argIterator.next().split(",");
                    selectedRoutes = new ArrayList<>(gtfsRoutes.length);
                    Collections.addAll(selectedRoutes, gtfsRoutes);
                    break;
                case "-h": //help
                case "--help":
                    System.out.println(getHelpText());
                    System.exit(0);
                    break;
                case "-t":
                case "--taskmgr":
                    outputStopsToTaskingManager = true;
                    break;
                case "-d":
                case "--debug":
                    debugEnabled = true;
                    break;
            }
        }

        if(selectedRoutes == null) {
            System.out.format("FATAL: Please include at least 1 GTFS route id, using the -r option\n%s", getHelpText());
            System.exit(1);
        }
        File importFile = new File(importFileName);
        if(!importFile.exists()) {
            System.out.format("FATAL: Unable to open import file “%s”\n", importFileName);
            System.exit(1);
        }

        //load config, which also creates the working directories if needed
        final RouteConflator.LineComparisonOptions matchingOptions = new RouteConflator.LineComparisonOptions();
        try {
            Config.initWithConfigFile(configPath);
            //define the options for the comparison routines TODO load from config
            matchingOptions.segmentSearchBoxSize = 65.0;
            matchingOptions.maxSegmentLength = 10.0;
            matchingOptions.setMaxSegmentAngle(50.0);
            matchingOptions.setMaxFutureVectorAngle(75.0);
            matchingOptions.maxSegmentOrthogonalDistance = 15.0;
            matchingOptions.maxSegmentMidPointDistance = Math.sqrt(matchingOptions.maxSegmentOrthogonalDistance * matchingOptions.maxSegmentOrthogonalDistance + 4.0 * matchingOptions.maxSegmentLength * matchingOptions.maxSegmentLength);
        } catch (FileNotFoundException | FileSystemException e) {
            e.printStackTrace();
            System.exit(1);
        }

        //propagate the debug value as needed
        RouteDataManager.debugEnabled = debugEnabled;
        RouteConflator.debugEnabled = debugEnabled;
        StopConflator.debugEnabled = debugEnabled;
        StopArea.debugEnabled = debugEnabled;
        OSMEntity.debugEnabled = debugEnabled;
        PathTree.debugEnabled = debugEnabled;

        try {
            OSMEntitySpace importSpace = new OSMEntitySpace(262144);
            importSpace.loadFromXML(importFileName);

            //create the working entity space for all data
            final RouteDataManager routeDataManager = new RouteDataManager(65536);

            //output all the stops in a format that works with the OSM Task Manager
            if(outputStopsToTaskingManager) {
                System.out.println("Processing all stops from GTFS import file…");
                final OSMTaskManagerExporter exporter = new OSMTaskManagerExporter(importSpace, routeDataManager, RouteConflator.RouteType.bus);
                exporter.conflateStops(routeDataManager);
                exporter.outputForOSMTaskingManager(Config.sharedInstance.outputDirectory, Config.sharedInstance.taskingManagerBaseUrl);
                System.exit(0);
            }

            //create a list of all the route_master relations in the import dataset
            final List<OSMRelation> importRouteMasterRelations = new ArrayList<>(importSpace.allRelations.size());
            String relationType;
            for(final OSMRelation relation : importSpace.allRelations.values()) {
                relationType = relation.getTag(OSMEntity.KEY_TYPE);
                final String gtfsRouteId = relation.getTag(RouteConflator.GTFS_ROUTE_ID);
                if(relationType != null && relationType.equals(OSMEntity.TAG_ROUTE_MASTER) && selectedRoutes.contains(gtfsRouteId)) {
                    selectedRoutes.remove(gtfsRouteId);
                    importRouteMasterRelations.add(relation);
                }
            }
            importSpace = null; //import data no longer needed

            if(selectedRoutes.size() > 0) {
                System.out.format("WARNING: No data found for routes: " + String.join(", ", selectedRoutes));
                if(importRouteMasterRelations.size() == 0) { //bail if no routes to process
                    System.out.format("FATAL: No valid routes to process\n");
                    System.exit(1);
                }
            }

            //loop through the route masters, processing their subroutes in one entity space
            final List<RouteConflator> routeConflators = new ArrayList<>(importRouteMasterRelations.size());
            try {
                for (final OSMRelation importRouteMaster : importRouteMasterRelations) {

                    //output an OSM XML file with only the current route data
                    System.out.format("Processing route %s (ref %s, GTFS id %s), %d trips…\n", importRouteMaster.getTag(OSMEntity.KEY_NAME), importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID), importRouteMaster.getMembers().size());
                    if (debugEnabled) {
                        OSMEntitySpace originalRouteSpace = new OSMEntitySpace(2048);
                        originalRouteSpace.addEntity(importRouteMaster, OSMEntity.TagMergeStrategy.keepTags, null);
                        originalRouteSpace.outputXml(Config.sharedInstance.outputDirectory + "/gtfsroute_" + importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID) + ".osm");
                        originalRouteSpace = null;
                    }

                    //create an object to handle the processing of the data for this route_master
                    routeConflators.add(new RouteConflator(importRouteMaster, routeDataManager, matchingOptions));
                }
            } catch(InvalidArgumentException e) {
                System.out.format("WARNING: %s\n", e.getMessage());
            }

            //bail if no valid routes to process
            if(routeConflators.size() == 0) {
                System.out.format("FATAL: no valid routes to process\n");
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
                relationSpace.outputXml(Config.sharedInstance.outputDirectory + "relation_" + routeConflator.getExportRouteMaster().getTag(OSMEntity.KEY_REF) + ".osm");
            }
            //importSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }
    }
}
