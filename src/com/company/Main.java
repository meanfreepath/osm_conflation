package com.company;

import Conflation.Route;
import Conflation.RouteConflator;
import Conflation.StopArea;
import Conflation.StopConflator;
import OSM.*;
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
        StopConflator.debugEnabled = debugEnabled;
        StopArea.debugEnabled = debugEnabled;
        OSMEntity.debugEnabled = debugEnabled;

        final String importFileName = args[0];

        try {
            importSpace.loadFromXML(importFileName);

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

                //finally, add the completed relations to their own separate file for review and upload
                final OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
                for(final Route route: routeConflator.getExportRoutes()) {
                    relationSpace.addEntity(route.routeRelation, OSMEntity.TagMergeStrategy.keepTags, null);
                }
                //also include any entities that were changed during the process
                for(final OSMEntity changedEntity : workingEntitySpace.allEntities.values()) {
                    if(changedEntity instanceof OSMRelation || changedEntity.getAction() == OSMEntity.ChangeAction.modify) {
                        relationSpace.addEntity(changedEntity, OSMEntity.TagMergeStrategy.keepTags, null);
                    }
                }
                relationSpace.addEntity(routeConflator.getExportRouteMaster(), OSMEntity.TagMergeStrategy.keepTags, null);
                relationSpace.outputXml("relation.osm");
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
