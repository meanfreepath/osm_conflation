package Conflation;

import OSM.OSMNode;
import OSM.Point;
import OSM.Region;
import OSM.SphericalMercator;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.util.ArrayList;
import java.util.List;

/**
 * Class used for indexing OSMWays in the downloaded region into subregions, for more efficient
 * bounding box checks
 */
class Cell implements WaySegmentsObserver {
    public final static List<Cell> allCells = new ArrayList<>(128);
    public final static double cellSizeInMeters = 500.0;
    private static double cellSize;
    private static double searchBuffer = 0.0;

    protected static void initCellsForBounds(final Region bounds, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        //wipe any existing cells (i.e. from a previous run)
        for(final Cell cell : allCells) {
            cell.clear();
        }
        allCells.clear();

        //and prepare the region, including a buffer zone equal to the greatest of the various search/bounding box dimensions
        cellSize = SphericalMercator.metersToCoordDelta(cellSizeInMeters, bounds.getCentroid().y);
        searchBuffer = -SphericalMercator.metersToCoordDelta(Math.max(wayMatchingOptions.segmentSearchBoxSize, Math.max(StopArea.duplicateStopPlatformBoundingBoxSize, StopArea.waySearchAreaBoundingBoxSize)), bounds.getCentroid().y);

        //generate the cells needed to fill the entire bounds (plus the searchBuffer)
        final Region baseCellRegion = bounds.regionInset(searchBuffer, searchBuffer);
        for(double y = baseCellRegion.origin.y; y <= baseCellRegion.extent.y; y += Cell.cellSize) {
            for(double x = baseCellRegion.origin.x; x <= baseCellRegion.extent.x; x += Cell.cellSize) {
                createCellForPoint(new Point(x, y));
            }
        }
    }
    private static Cell createCellForPoint(final Point point) {
        Cell cell = new Cell(point);
        allCells.add(cell);
        return cell;
    }

    public final Region boundingBox, expandedBoundingBox;
    public final List<OSMWaySegments> containedLines = new ArrayList<>(1024); //contains OSM ways only

    private Cell(final Point origin) {
        boundingBox = new Region(origin, new Point(origin.x + cellSize, origin.y + cellSize));
        expandedBoundingBox = boundingBox.regionInset(searchBuffer, searchBuffer);
    }
    protected void addWay(final OSMWaySegments entity) {
        if(!containedLines.contains(entity)) {
            containedLines.add(entity);
            entity.addObserver(this);
        }
    }
    protected boolean removeWay(final OSMWaySegments entity) {
        if(containedLines.contains(entity)) {
            entity.removeObserver(this);
            return containedLines.remove(entity);
        }
        return false;
    }
    private void clear() {
        for(final OSMWaySegments containedLine : containedLines) {
            containedLine.removeObserver(this);
        }
        containedLines.clear();
    }
    @Override
    public String toString() {
        return String.format("Cell %s (%d ways)", boundingBox, containedLines.size());
    }

    @Override
    public void waySegmentsWasSplit(WaySegments originalWaySegments, OSMNode[] splitNodes, WaySegments[] splitWaySegments) throws InvalidArgumentException {
        for(final WaySegments splitLine : splitWaySegments) {
            if(splitLine == originalWaySegments) { //remove the original line if it no longer intersects this Cell
                if(!Region.intersects(boundingBox, splitLine.way.getBoundingBox())) {
                    removeWay((OSMWaySegments) splitLine);
                }
            } else { //add the new way if it intersects this Cell
                if(Region.intersects(boundingBox, splitLine.way.getBoundingBox())) {
                    addWay((OSMWaySegments) splitLine);
                }
            }
        }
    }

    @Override
    public void waySegmentsWasDeleted(WaySegments waySegments) throws InvalidArgumentException {
        removeWay((OSMWaySegments) waySegments);
    }

    @Override
    public void waySegmentsAddedSegment(WaySegments waySegments, LineSegment oldSegment, LineSegment[] newSegments) {
        //No updates needed for this action
    }
}
