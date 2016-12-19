package com.company;

import Conflation.RouteConflator;
import Conflation.RouteDataManager;
import Conflation.StopArea;
import OSM.*;
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
    private final OSMEntitySpace importEntitySpace, exportEntitySpace;
    private final RouteConflator.RouteType routeType;
    private final List<StopArea> allStops;

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

    public OSMTaskManagerExporter(final OSMEntitySpace importSpace, final OSMEntitySpace exportSpace, RouteConflator.RouteType routeType) {
        importEntitySpace = importSpace;
        exportEntitySpace = exportSpace;
        this.routeType = routeType;
        allStops = new ArrayList<>(importEntitySpace.allEntities.size());
    }

    public void conflateStops(final RouteDataManager routeDataManager, final boolean cachingEnabled) throws InvalidArgumentException {
        allStops.clear();
        if(routeType == RouteConflator.RouteType.bus) {
            for (final OSMEntity entity : importEntitySpace.allNodes.values()) {
                if (OSMEntity.TAG_LEGACY_BUS_STOP.equals(entity.getTag(OSMEntity.KEY_HIGHWAY)) || OSMEntity.TAG_PLATFORM.equals(entity.getTag(OSMEntity.KEY_PUBLIC_TRANSPORT))) {
                    allStops.add(new StopArea(entity));
                }
            }
        } //TODO: implement other route types

        routeDataManager.conflateStopsWithOSM(allStops, routeType, cachingEnabled);
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
        final double coordFactor = SphericalMercator.metersToCoordDelta(1.0, exportEntitySpace.getBoundingBox().getCentroid().y);
        final double boundingBoxBuffer = -10.0 * coordFactor;
        final Region boundingBox = exportEntitySpace.getBoundingBox().regionInset(boundingBoxBuffer, boundingBoxBuffer);

        //run a rough calculation of the number of boxes needed
        final double boxSizeInCoords = DividedBox.BOX_SIZE * coordFactor;
        final int horizontalBoxCount = (int) Math.ceil((boundingBox.origin.x - boundingBox.origin.x) / boxSizeInCoords);
        final int verticalBoxCount = (int) Math.ceil((boundingBox.origin.y - boundingBox.origin.y) / boxSizeInCoords);
        final List<DividedBox> subBoxes = new ArrayList<>(horizontalBoxCount * verticalBoxCount);

        //divide the bounding box into multiple smaller boxes
        createSubBoxesInRegion(boundingBox, boxSizeInCoords, boxSizeInCoords, allStops, subBoxes);

        //now subdivide boxes with a large number of stops into smaller boxes
        final List<DividedBox> boxesToRemove = new ArrayList<>(128);
        final List<DividedBox> boxesToAdd = new ArrayList<>(128);
        for(final DividedBox box : subBoxes) {
            final int stopsInBox = box.containedEntities.size();
            if(stopsInBox > 250) { //break into 9 boxes
                createSubBoxesInRegion(box.region, boxSizeInCoords / 3.0, boxSizeInCoords / 3.0, allStops, boxesToAdd);
                boxesToRemove.add(box);
            } else if(stopsInBox > 100) { //break into 4 boxes
                createSubBoxesInRegion(box.region, boxSizeInCoords / 2.0, boxSizeInCoords / 2.0, allStops, boxesToAdd);
                boxesToRemove.add(box);
            }
        }
        subBoxes.removeAll(boxesToRemove);
        subBoxes.addAll(boxesToAdd);


        //now create an OSM file for each box
        for(final DividedBox box : subBoxes) {
            final OSMEntitySpace boxSpace = new OSMEntitySpace(box.containedEntities.size());
            for(final OSMEntity entity : box.containedEntities) {
                boxSpace.addEntity(entity, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
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


        final OSMEntitySpace debugEntitySpace = new OSMEntitySpace(allStops.size());
        for(final StopArea entity : allStops) {
            if(entity.getPlatform().hasTag(StopArea.KEY_GTFS_CONFLICT)) {
                debugEntitySpace.addEntity(entity.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
            }
        }
        debugEntitySpace.outputXml(destinationDir + "all_stops.osm");
    }
    private static void createSubBoxesInRegion(final Region region, final double boxWidth, final double boxHeight, final List<StopArea> filteredEntities, final List<DividedBox> boxList) {
        final double boxWidthTolerance = boxWidth * 0.01, boxHeightTolerance = boxHeight * 0.01;
        for(double lon = region.origin.x; region.extent.x - lon > boxWidthTolerance; lon += boxWidth) {
            for(double lat = region.origin.y; region.extent.y - lat > boxHeightTolerance; lat += boxHeight) {
                final Region boxRegion = new Region(lon, lat, boxWidth, boxHeight);

                //create a sub-box if the boxRegion contains at least one of the desired node types
                final DividedBox box = new DividedBox(boxRegion);
                for(final StopArea entity : filteredEntities) {
                    if(Region.intersects(boxRegion, entity.getPlatform().getBoundingBox())) {
                        box.containedEntities.add(entity.getPlatform());
                    }
                }
                if(box.containedEntities.size() > 0) {
                    boxList.add(box);
                }
            }
        }
    }
}
