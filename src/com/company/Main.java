package com.company;

import Conflation.*;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMRelation;
import Overpass.OverpassConverter;
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
        boolean outputStopsToTaskingManager = false, processStopsOnly = false, overpassCachingEnabled = true;

        List<String> argList = new ArrayList<>(args.length);
        Collections.addAll(argList, args);
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
                case "-n":
                case "--nocache":
                    overpassCachingEnabled = false;
                    break;
                case "-s":
                case "--stopsonly":
                    processStopsOnly = true;
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
        OverpassConverter.debugEnabled = debugEnabled;

        try {
            OSMEntitySpace allGTFSRoutesSpace = new OSMEntitySpace(262144), workingImportSpace = new OSMEntitySpace(65536);
            allGTFSRoutesSpace.loadFromXML(importFileName);

            /*check if there is a separate GTFS route .osm file for the routes, and if so merge their data into the main import space.
              Allows the user to make fixes or adjustments to the GTFS data to improve matching.
             */
            for(final String selectedGTFSRouteId : selectedRoutes) {
                final String routeFileName = RouteConflator.gtfsFileNameForRoute(selectedGTFSRouteId);
                final File routeFile = new File(routeFileName);
                if (routeFile.exists()) { //if the file exists, merge its data into the main import space
                    System.out.format("INFO: GTFS .osm file exists for route %s: using it instead of main import file.\n", selectedGTFSRouteId);
                    OSMEntitySpace routeImportSpace = new OSMEntitySpace(2048);
                    routeImportSpace.loadFromXML(routeFileName);
                    workingImportSpace.mergeWithSpace(routeImportSpace, OSMEntity.TagMergeStrategy.copyTags, null);
                } else { //otherwise, extract the route's data from the global import space into the augmented space
                    System.out.format("INFO: No GTFS .osm file exists for route %s: creating new file.  This file can be edited to improve route matching.\n", selectedGTFSRouteId);
                    String relationType, gtfsRouteId;
                    for (final OSMRelation relation : allGTFSRoutesSpace.allRelations.values()) {
                        relationType = relation.getTag(OSMEntity.KEY_TYPE);
                        gtfsRouteId = relation.getTag(RouteConflator.GTFS_ROUTE_ID);

                        //add to the working import space
                        if (relationType != null && relationType.equals(OSMEntity.TAG_ROUTE_MASTER) && selectedGTFSRouteId.equals(gtfsRouteId)) {
                            workingImportSpace.addEntity(relation, OSMEntity.TagMergeStrategy.copyTags, null);

                            //also save the route's data to an OSM file
                            OSMEntitySpace routeImportSpace = new OSMEntitySpace(2048);
                            routeImportSpace.addEntity(relation, OSMEntity.TagMergeStrategy.copyTags, null);
                            routeImportSpace.outputXml(routeFileName);
                        }
                    }
                }
            }

            //create the working entity space for all data
            final RouteDataManager routeDataManager = new RouteDataManager(65536);

            //output all the stops in a format that works with the OSM Task Manager
            if(outputStopsToTaskingManager) {
                System.out.println("Processing all stops from GTFS import file…");
                final OSMTaskManagerExporter exporter = new OSMTaskManagerExporter(allGTFSRoutesSpace, routeDataManager, RouteConflator.RouteType.bus);
                exporter.conflateStops(routeDataManager, overpassCachingEnabled);
                exporter.outputForOSMTaskingManager(Config.sharedInstance.outputDirectory, Config.sharedInstance.taskingManagerBaseUrl);
                System.exit(0);
            }

            //create a list of all the route_master relations in the import dataset
            final List<OSMRelation> importRouteMasterRelations = new ArrayList<>(workingImportSpace.allRelations.size());
            String relationType;
            for(final OSMRelation relation : workingImportSpace.allRelations.values()) {
                relationType = relation.getTag(OSMEntity.KEY_TYPE);
                final String gtfsRouteId = relation.getTag(RouteConflator.GTFS_ROUTE_ID);
                if(relationType != null && relationType.equals(OSMEntity.TAG_ROUTE_MASTER) && selectedRoutes.contains(gtfsRouteId)) {
                    selectedRoutes.remove(gtfsRouteId);
                    importRouteMasterRelations.add(relation);
                }
            }
            allGTFSRoutesSpace = workingImportSpace = null; //import data no longer needed

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
                    System.out.format("Processing route “%s” (ref %s, GTFS id %s), %d trips…\n", importRouteMaster.getTag(OSMEntity.KEY_NAME), importRouteMaster.getTag(OSMEntity.KEY_REF), importRouteMaster.getTag(RouteConflator.GTFS_ROUTE_ID), importRouteMaster.getMembers().size());
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

            //fetch all existing stops from OSM in the entire route's bounding box, and match them with the route's stops
            final StopConflator stopConflator = new StopConflator();

            //if processing stops only, output them to an OSM XML file and bail
            if(processStopsOnly) {
                for(final RouteConflator routeConflator : routeConflators) {
                    routeConflator.generateStopAreas();
                }
                routeDataManager.conflateStopsWithOSM(routeConflators, overpassCachingEnabled);
                stopConflator.outputStopsForRoutes(routeConflators);
                System.exit(0);
            } else { //otherwise, fetch all ways from OSM that are within the routes' bounding boxes
                routeDataManager.downloadRegionsForImportDataset(routeConflators, matchingOptions, overpassCachingEnabled);
                routeDataManager.conflateStopsWithOSM(routeConflators, overpassCachingEnabled);
            }

            //now run the conflation algorithms on each route_master, adding the conflated path data to an output space
            final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
            final List<String> routeIds = new ArrayList<>(routeConflators.size());
            int successfullyMatchedRouteMasters = 0;
            for(final RouteConflator routeConflator : routeConflators) {
                routeIds.add(routeConflator.gtfsRouteId);

                //and match the subroutes' routePath to the downloaded OSM ways.  Also matches the stops in the route to their nearest matching way
                if(routeConflator.conflateRoutePaths(stopConflator)) {
                    successfullyMatchedRouteMasters++;
                }

                //finally, add the completed relations to the output file for review and upload
                for(final Route route: routeConflator.getExportRoutes()) {
                    relationSpace.addEntity(route.routeRelation, OSMEntity.TagMergeStrategy.keepTags, null);
                }
                relationSpace.addEntity(routeConflator.getExportRouteMaster(), OSMEntity.TagMergeStrategy.keepTags, null);
            }

            //also include any entities that were changed during the process
            for(final OSMEntity changedEntity : routeDataManager.allEntities.values()) {
                if(changedEntity.getAction() == OSMEntity.ChangeAction.modify) {
                    if(changedEntity instanceof OSMRelation) {
                        System.out.println("ADD MOD" + changedEntity);
                    }
                    relationSpace.addEntity(changedEntity, OSMEntity.TagMergeStrategy.keepTags, null);
                }
            }

            relationSpace.setCanUpload(successfullyMatchedRouteMasters == routeConflators.size());
            relationSpace.outputXml(String.format("%s/relations_%s.osm", Config.sharedInstance.outputDirectory, String.join("_", routeIds)));
            //allGTFSRoutesSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }
    }
}
