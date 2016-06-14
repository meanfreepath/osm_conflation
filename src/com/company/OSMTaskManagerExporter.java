package com.company;

import Conflation.StopArea;
import Conflation.StopConflator;
import OSM.OSMEntity;
import OSM.OSMEntitySpace;
import OSM.OSMNode;
import OSM.Region;
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
        public final static double BOX_SIZE = 0.045;

        public final int id;
        public final Region region;
        public final List<OSMNode> containedNodes = new ArrayList<>(128);
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
        List<StopArea> allStops = new ArrayList<>(entitySpace.allNodes.size());
        for(final OSMNode node : entitySpace.allNodes.values()) {
            if("bus_stop".equals(node.getTag("highway"))) {
                allStops.add(new StopArea(node, null));
            }
        }
        try {
            conflator.conflateStops(20.0, allStops, OSMEntity.KEY_BUS, entitySpace);
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
        final Region boundingBox = entitySpace.getBoundingBox().regionInset(-0.0001, -0.0001);

        //run a rough calculation of the number of boxes needed
        final int horizontalBoxCount = (int) Math.ceil((boundingBox.origin.longitude - boundingBox.origin.longitude) / DividedBox.BOX_SIZE);
        final int verticalBoxCount = (int) Math.ceil((boundingBox.origin.latitude - boundingBox.origin.latitude) / DividedBox.BOX_SIZE);
        final List<DividedBox> subBoxes = new ArrayList<>(horizontalBoxCount * verticalBoxCount);

        //filter out the non-stop nodes from the list
        final List<OSMNode> filteredNodes = new ArrayList<>(8192);
        for(final OSMNode node : entitySpace.allNodes.values()) {
            if(node.hasTag("public_transport")) {
                filteredNodes.add(node);
            }
        }

        //divide the bounding box into multiple smaller boxes
        final double boxWidth = DividedBox.BOX_SIZE / Math.cos(0.5 * (boundingBox.origin.latitude + boundingBox.extent.latitude) * Math.PI / 180.0);
        createSubBoxesInRegion(boundingBox, boxWidth, DividedBox.BOX_SIZE, filteredNodes, subBoxes);

        //now subdivide boxes with a large number of stops into smaller boxes
        final List<DividedBox> boxesToRemove = new ArrayList<>(128);
        final List<DividedBox> boxesToAdd = new ArrayList<>(128);
        for(final DividedBox box : subBoxes) {
            final int stopsInBox = box.containedNodes.size();
            if(stopsInBox > 250) { //break into 9 boxes
                createSubBoxesInRegion(box.region, boxWidth / 3.0, DividedBox.BOX_SIZE / 3.0, filteredNodes, boxesToAdd);
                boxesToRemove.add(box);
            } else if(stopsInBox > 100) { //break into 4 boxes
                createSubBoxesInRegion(box.region, boxWidth / 2.0, DividedBox.BOX_SIZE / 2.0, filteredNodes, boxesToAdd);
                boxesToRemove.add(box);
            }
        }
        subBoxes.removeAll(boxesToRemove);
        subBoxes.addAll(boxesToAdd);


        //now create an OSM file for each box
        for(final DividedBox box : subBoxes) {
            final OSMEntitySpace boxSpace = new OSMEntitySpace(box.containedNodes.size());
            for(final OSMNode node : box.containedNodes) {
                boxSpace.addEntity(node, OSMEntity.TagMergeStrategy.keepTags, null);
            }

            try {
                boxSpace.outputXml(destinationDir + box.osmFileName);
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
            jsonWriter.array().value(box.region.origin.longitude).value(box.region.origin.latitude).endArray();
            jsonWriter.array().value(box.region.extent.longitude).value(box.region.origin.latitude).endArray();
            jsonWriter.array().value(box.region.extent.longitude).value(box.region.extent.latitude).endArray();
            jsonWriter.array().value(box.region.origin.longitude).value(box.region.extent.latitude).endArray();
            jsonWriter.array().value(box.region.origin.longitude).value(box.region.origin.latitude).endArray();
            jsonWriter.endArray().endArray();
            jsonWriter.endObject();

            jsonWriter.endObject();
        }
        jsonWriter.endArray();
        jsonWriter.endObject();

        fileWriter.close();


        final OSMEntitySpace debugEntitySpace = new OSMEntitySpace(filteredNodes.size());
        for(final OSMNode node : filteredNodes) {
            if(node.hasTag("gtfs:conflict")) {
                debugEntitySpace.addEntity(node, OSMEntity.TagMergeStrategy.keepTags, null);
            }
        }
        debugEntitySpace.outputXml(destinationDir + "all_stops.osm");
    }
    private void createSubBoxesInRegion(final Region region, final double boxWidth, final double boxHeight, final List<OSMNode> filteredNodes, final List<DividedBox> boxList) {
        for(double lon=region.origin.longitude;lon<region.extent.longitude;lon += boxWidth) {
            for(double lat=region.origin.latitude;lat<region.extent.latitude;lat += boxHeight) {
                final Region boxRegion = new Region(lat, lon, boxHeight, boxWidth);

                //create a sub-box if the boxRegion contains at least one of the desired node types
                final DividedBox box = new DividedBox(boxRegion);
                for(final OSMNode node : filteredNodes) {
                    if(boxRegion.containsPoint(node.getCentroid())) {
                        box.containedNodes.add(node);
                    }
                }
                if(box.containedNodes.size() > 0) {
                    boxList.add(box);
                }
            }
        }
    }
}
