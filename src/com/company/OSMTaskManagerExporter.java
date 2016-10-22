package com.company;

import Conflation.StopArea;
import Conflation.StopConflator;
import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.json.JSONWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Class for exporting the GTFS stops data as a GeoJSON file for an OSM Task Manager project
 * See http://wiki.openstreetmap.org/wiki/OSM_Tasking_Manager for details
 * Created by nick on 12/3/15.
 */
public class OSMTaskManagerExporter {
    private final OSMEntitySpace entitySpace;

    private static class DividedBox {
        private static int idGenerator = 0;
        private final static String BOX_FILE_FORMAT = "box_%d.osm";
        public final static double BOX_SIZE = 5000.0; //standard box size, in meters

        public final int id;
        public final Region region;
        public final List<OSMEntity> containedEntities = new ArrayList<>(128);
        public final String osmFileName;

        public DividedBox(final Region boxRegion) {
            id = ++idGenerator;
            region = boxRegion;

            osmFileName = String.format(BOX_FILE_FORMAT, id);
        }

    }

    public OSMTaskManagerExporter(final OSMEntitySpace space) {
        entitySpace = space;
    }

    public void conflateStops() {
        final StopConflator conflator = new StopConflator(null);
        List<StopArea> allStops = new ArrayList<>(entitySpace.allEntities.size());
        for(final OSMEntity entity : entitySpace.allNodes.values()) {
            if("bus_stop".equals(entity.getTag("highway")) || "platform".equals(entity.getTag("public_transport"))) {
                allStops.add(new StopArea(entity, null));
            }
        }
        try {
            conflator.conflateStopsWithOSM(allStops, OSMEntity.KEY_BUS, entitySpace);
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        }
    }

    /**
     * Output the stops data
     * @param destinationDir: the local directory to output the GeoJSON and related OSM XML files to
     * @param baseImportUrl: the publicly-accessible base URL for the OSM XML files
     * @throws IOException
     */
    public void outputForOSMTaskingManager(String destinationDir, final String baseImportUrl) throws IOException {
        if(!destinationDir.endsWith("/")) {
            destinationDir += "/";
        }

        //get the bounding box of all the data (plus a little buffer to ensure everything's contained
        final double coordFactor = SphericalMercator.metersToCoordDelta(1.0, entitySpace.getBoundingBox().getCentroid().y);
        final double boundingBoxBuffer = -10.0 * coordFactor;
        final Region boundingBox = entitySpace.getBoundingBox().regionInset(boundingBoxBuffer, boundingBoxBuffer);

        //run a rough calculation of the number of boxes needed
        final double boxSizeInCoords = DividedBox.BOX_SIZE * coordFactor;
        final int horizontalBoxCount = (int) Math.ceil((boundingBox.origin.x - boundingBox.origin.x) / boxSizeInCoords);
        final int verticalBoxCount = (int) Math.ceil((boundingBox.origin.y - boundingBox.origin.y) / boxSizeInCoords);
        final List<DividedBox> subBoxes = new ArrayList<>(horizontalBoxCount * verticalBoxCount);

        //include all GTFS stops, and any existing stops that conflict with them
        final List<OSMEntity> filteredEntities = new ArrayList<>(8192);
        for(final OSMEntity entity: entitySpace.allEntities.values()) {
            if(entity.hasTag("gtfs:conflict") || entity.hasTag("gtfs:stop_id")) {
                filteredEntities.add(entity);
            }
        }

        //divide the bounding box into multiple smaller boxes
        createSubBoxesInRegion(boundingBox, boxSizeInCoords, boxSizeInCoords, filteredEntities, subBoxes);

        //now subdivide boxes with a large number of stops into smaller boxes
        final List<DividedBox> boxesToRemove = new ArrayList<>(128);
        final List<DividedBox> boxesToAdd = new ArrayList<>(128);
        for(final DividedBox box : subBoxes) {
            final int stopsInBox = box.containedEntities.size();
            if(stopsInBox > 250) { //break into 9 boxes
                createSubBoxesInRegion(box.region, boxSizeInCoords / 3.0, boxSizeInCoords / 3.0, filteredEntities, boxesToAdd);
                boxesToRemove.add(box);
            } else if(stopsInBox > 100) { //break into 4 boxes
                createSubBoxesInRegion(box.region, boxSizeInCoords / 2.0, boxSizeInCoords / 2.0, filteredEntities, boxesToAdd);
                boxesToRemove.add(box);
            }
        }
        subBoxes.removeAll(boxesToRemove);
        subBoxes.addAll(boxesToAdd);


        //now create an OSM file for each box
        for(final DividedBox box : subBoxes) {
            final OSMEntitySpace boxSpace = new OSMEntitySpace(box.containedEntities.size());
            for(final OSMEntity entity : box.containedEntities) {
                boxSpace.addEntity(entity, OSMEntity.TagMergeStrategy.keepTags, null);
            }

            try {
                boxSpace.setCanUpload(true);
                boxSpace.outputXml(destinationDir + box.osmFileName, box.region);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        //create the GeoJSON file with the subdivided boxes, for the OSM Task Manager
        final FileWriter fileWriter = new FileWriter(destinationDir + "import_region.geojson");
        final JSONWriter jsonWriter = new JSONWriter(fileWriter);

        jsonWriter.object();
        jsonWriter.key("type").value("FeatureCollection");
        jsonWriter.key("crs").object();
        jsonWriter.key("type").value("name");
        jsonWriter.key("properties").object();
        jsonWriter.key("name").value("urn:ogc:def:crs:OGC:1.3:CRS84");
        jsonWriter.endObject();
        jsonWriter.endObject();

        //features array: output the boxes and their geometries
        jsonWriter.key("features").array();
        for(final DividedBox box : subBoxes) {
            final LatLonRegion boxRegionLL = SphericalMercator.mercatorToLatLon(box.region);
            jsonWriter.object();
            jsonWriter.key("type").value("Feature");

            //various property names/values
            jsonWriter.key("properties").object();
            jsonWriter.key("id").value(box.id);
            jsonWriter.key("name").value("Region #" + box.id);
            jsonWriter.key("import_url").value(baseImportUrl + box.osmFileName);
            jsonWriter.endObject();

            //the box's geometry
            jsonWriter.key("geometry").object();
            jsonWriter.key("type").value("Polygon");
            jsonWriter.key("coordinates").array().array();
            jsonWriter.array().value(boxRegionLL.origin.longitude).value(boxRegionLL.origin.latitude).endArray();
            jsonWriter.array().value(boxRegionLL.extent.longitude).value(boxRegionLL.origin.latitude).endArray();
            jsonWriter.array().value(boxRegionLL.extent.longitude).value(boxRegionLL.extent.latitude).endArray();
            jsonWriter.array().value(boxRegionLL.origin.longitude).value(boxRegionLL.extent.latitude).endArray();
            jsonWriter.array().value(boxRegionLL.origin.longitude).value(boxRegionLL.origin.latitude).endArray();
            jsonWriter.endArray().endArray();
            jsonWriter.endObject();

            jsonWriter.endObject();
        }
        jsonWriter.endArray();
        jsonWriter.endObject();

        fileWriter.close();


        final OSMEntitySpace debugEntitySpace = new OSMEntitySpace(filteredEntities.size());
        for(final OSMEntity entity : filteredEntities) {
            if(entity.hasTag("gtfs:conflict")) {
                debugEntitySpace.addEntity(entity, OSMEntity.TagMergeStrategy.keepTags, null);
            }
        }
        debugEntitySpace.outputXml(destinationDir + "all_stops.osm");
    }
    private static void createSubBoxesInRegion(final Region region, final double boxWidth, final double boxHeight, final List<OSMEntity> filteredEntities, final List<DividedBox> boxList) {
        final double boxWidthTolerance = boxWidth * 0.01, boxHeightTolerance = boxHeight * 0.01;
        for(double lon = region.origin.x; region.extent.x - lon > boxWidthTolerance; lon += boxWidth) {
            for(double lat = region.origin.y; region.extent.y - lat > boxHeightTolerance; lat += boxHeight) {
                final Region boxRegion = new Region(lon, lat, boxWidth, boxHeight);

                //create a sub-box if the boxRegion contains at least one of the desired node types
                final DividedBox box = new DividedBox(boxRegion);
                for(final OSMEntity entity : filteredEntities) {
                    if(Region.intersects(boxRegion, entity.getBoundingBox())) {
                        box.containedEntities.add(entity);
                    }
                }
                if(box.containedEntities.size() > 0) {
                    boxList.add(box);
                }
            }
        }
    }
}
