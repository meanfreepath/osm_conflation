package com.company;

import Conflation.*;
import NewPathFinding.PathTree;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMRelation;
import Overpass.OverpassConverter;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.file.FileSystemException;
import java.util.*;

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
        String importFileName = Config.DEFAULT_GTFS_FILE;
        String configPath = Config.DEFAULT_CONFIG_FILE;
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
            matchingOptions.segmentSearchBoxSize = 30.0; //NOTE: higher values (60+ may result in false positives and "short-circuited" loops, depending on the routes)
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
                            workingImportSpace.addEntity(relation, OSMEntity.TagMergeStrategy.copyTags, null, true, 0);

                            //also save the route's data to an OSM file
                            OSMEntitySpace routeImportSpace = new OSMEntitySpace(2048);
                            routeImportSpace.addEntity(relation, OSMEntity.TagMergeStrategy.copyTags, null, true, 0);
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
            try {
                RouteConflator.createConflatorsForRouteMasters(importRouteMasterRelations, routeDataManager, matchingOptions);
            } catch(InvalidArgumentException e) {
                System.out.format("WARNING: %s\n", e.getMessage());
            }

            //bail if no valid routes to process
            if(RouteConflator.allConflators.size() == 0) {
                System.out.format("FATAL: no valid routes to process\n");
                System.exit(1);
            }

            //fetch all existing stops from OSM in the entire route's bounding box, and match them with the route's stops
            final StopConflator stopConflator = new StopConflator();

            //if processing stops only, output them to an OSM XML file and bail
            final List<String> routeIds = RouteConflator.getRouteMasterIds();
            if(processStopsOnly) {
                routeDataManager.conflateStopsWithOSM(RouteConflator.allConflators, overpassCachingEnabled);
                final OSMEntitySpace stopPlatformSpace = new OSMEntitySpace(2048);
                stopConflator.outputStopsForRoutes(RouteConflator.allConflators.get(0).routeType, stopPlatformSpace);
                stopPlatformSpace.setCanUpload(true);
                final String stopsFileName = String.format("%s/routestops_%s.osm", Config.sharedInstance.outputDirectory, String.join("_", routeIds));
                stopPlatformSpace.outputXml(stopsFileName);
                System.out.format("INFO: outputted stops data to %s\n", stopsFileName);
                System.exit(0);
            } else { //otherwise, fetch all ways from OSM that are within the routes' bounding boxes
                routeDataManager.downloadRegionsForImportDataset(RouteConflator.allConflators, matchingOptions, overpassCachingEnabled);
                routeDataManager.outputXml(String.format("%s/routedownload_%s.osm", Config.sharedInstance.outputDirectory, String.join("_", routeIds)));
                routeDataManager.conflateStopsWithOSM(RouteConflator.allConflators, false); //don't cache stop data, to avoid stale data if previously run with processStopsOnly option
            }

            //now run the conflation algorithms on each route_master, adding the conflated path data to an output space
            final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
            relationSpace.name = "routeoutput";
            int successfullyMatchedRouteMasters = 0;
            for(final RouteConflator routeConflator : RouteConflator.allConflators) {
                //and match the subroutes' routePath to the downloaded OSM ways.  Also matches the stops in the route to their nearest matching way
                if(routeConflator.conflateRoutePaths(stopConflator)) {
                    successfullyMatchedRouteMasters++;
                }
            }

            //if all routes fully matched, add the completed route relation to the output file for review and upload
            if(successfullyMatchedRouteMasters == RouteConflator.allConflators.size()) {
                for (final RouteConflator routeConflator : RouteConflator.allConflators) {
                    relationSpace.addEntity(routeConflator.getExportRouteMaster(), OSMEntity.TagMergeStrategy.keepTags, null, true, 0);

                    //also add any other relations that contain any of the route's memberList, to prevent membership conflicts if the user edits the output file.
                    for (final Route route : routeConflator.getExportRoutes()) {
                        for (final OSMRelation.OSMRelationMember routeMember : route.routeRelation.getMembers()) {
                            final Collection<OSMRelation> containingRelations = routeMember.member.getContainingRelations().values();
                            for (final OSMRelation containingRelation : containingRelations) {
                                relationSpace.addEntity(containingRelation, OSMEntity.TagMergeStrategy.keepTags, null, false, 0);
                            }
                        }
                    }
                }

                //now add any entities that were created or modified during the matching process
                for (final OSMEntity entity : routeDataManager.allEntities.values()) {
                    if (entity.getAction() != OSMEntity.ChangeAction.none) {
                        relationSpace.addEntity(entity, OSMEntity.TagMergeStrategy.keepTags, null, false, 0);
                    }
                }

                //and finally output to a .osm file
                relationSpace.setCanUpload(true);
                final String uploadFileName = String.format("%s/relations_%s.osm", Config.sharedInstance.outputDirectory, String.join("_", routeIds));
                relationSpace.outputXml(uploadFileName);
                System.out.format("INFO: SUCCESS! Outputted uploadable OSM file to %s\n", uploadFileName);
            }

            //also output the full working space to a .osm file
            routeDataManager.outputXml(String.format("%s/workingspace_%s.osm", Config.sharedInstance.outputDirectory, String.join("_", routeIds)));
        } catch (IOException | ParserConfigurationException | SAXException | InvalidArgumentException e) {
            e.printStackTrace();
        }
    }
}
