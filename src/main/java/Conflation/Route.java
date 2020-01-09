package Conflation;

import NewPathFinding.PathTree;
import NewPathFinding.RoutePathFinder;
import OSM.*;
import com.company.Config;
import com.company.InvalidArgumentException;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks the data on a particular route
 * Created by nick on 1/27/16.
 */
public class Route {
    public final static int INITIAL_STOPS_CAPACITY = 32;
    public final RouteConflator routeConflator;
    public final String name, ref, tripMarker, tripId;
    public final OSMRelation routeRelation;
    public final RouteConflator.RouteType routeType;
    public final List<StopArea> stops;
    public final RouteLineWaySegments routeLine;
    public final RoutePathFinder routePathFinder;

    protected Route(final OSMRelation routeRelation, final OSMWay routePath, List<StopArea> stops, final RouteConflator routeConflator) {
        this.routeRelation = routeRelation;
        this.routeConflator = routeConflator;
        routeType = RouteConflator.RouteType.fromString(routeRelation.getTag(OSMEntity.KEY_ROUTE));
        name = routeRelation.getTag(OSMEntity.KEY_NAME);
        ref = routeRelation.getTag(OSMEntity.KEY_REF);
        tripMarker = routeRelation.getTag(RouteConflator.GTFS_TRIP_MARKER);
        tripId = routeRelation.getTag(RouteConflator.GTFS_TRIP_ID);

        //get a handle on the provided route path, and remove any *adjacent* duplicated nodes from it (can screw up segmenting/matching)
        routePath.setTag(RouteConflator.GTFS_IGNORE, OSMEntity.TAG_YES);
        final OSMNode[][] duplicateNodes = routePath.identifyDuplicateNodesByPosition(0.1);
        if(duplicateNodes.length > 0) {
            final List<OSMNode> routePathNodes = new ArrayList<>(routePath.getNodes());
            for (final OSMNode[] nodePair : duplicateNodes) {
                //remove any node that is at the same location as its immediate neighbor
                if(routePathNodes.indexOf(nodePair[1]) - routePathNodes.indexOf(nodePair[0]) == 1) {
                    routePath.removeNode(nodePair[1]);
                }
            }
        }

        this.stops = new ArrayList<>(stops);
        routeLine = new RouteLineWaySegments(routePath, routeConflator.wayMatchingOptions);
        routePathFinder = new RoutePathFinder(this);
    }

    /**
     * Copy contructor used for transferring routes between entity spaces
     * @param oldRoute
     * @param newEntitySpace
     */
    protected Route(final Route oldRoute, final List<StopArea> stops, final OSMEntitySpace newEntitySpace) {
        this.routeConflator = oldRoute.routeConflator;
        this.name = oldRoute.name;
        this.ref = oldRoute.ref;
        this.tripMarker = oldRoute.tripMarker;
        this.tripId = oldRoute.tripId;
        this.routeType = oldRoute.routeType;
        routeRelation = newEntitySpace.createRelation(oldRoute.routeRelation.getTags(), null);
        routeLine = new RouteLineWaySegments(oldRoute.routeLine.way, routeConflator.wayMatchingOptions);

        this.stops = new ArrayList<>(stops);

        routePathFinder = new RoutePathFinder(this); //TODO - any special cases?
    }
    protected void syncStopsWithRelation() {
        for(final StopArea stop : stops) {
            if(!routeRelation.containsMember(stop.getPlatform())) {
                routeRelation.addMember(stop.getPlatform(), OSMEntity.MEMBERSHIP_PLATFORM);
            }
            final OSMNode stopPosition = stop.getStopPosition(routeType);
            if(stopPosition != null && !routeRelation.containsMember(stopPosition)) {
                routeRelation.addMember(stopPosition, OSMEntity.MEMBERSHIP_STOP);
            }
        }
    }
    public boolean stopIsFirst(final StopArea stop) {
        return stops.indexOf(stop) == 0;
    }
    public boolean stopIsLast(final StopArea stop) {
        return stops.indexOf(stop) == stops.size()-1;
    }

    public void findRoutePaths(final RouteConflator routeConflator) {
        //first generate the pathTrees using the routeLine and this route's stops
        routePathFinder.generatePathTrees();

        //output the segments post-stop insertion, if desired
        if(RouteConflator.debugEnabled) {
            try {
                debugOutputSegments(routeConflator.getWorkingEntitySpace());
            } catch (IOException | InvalidArgumentException e) {
                e.printStackTrace();
            }
        }

        debugCheckMatchIndexIntegrity("PathTrees Generated");

        //and run the pathfinding algorithm
        routePathFinder.findPaths(routeConflator);

        debugCheckMatchIndexIntegrity("Paths found");

        //if the route wasn't fully matched, mark it
        if(routePathFinder.getFailedPaths() > 0) {
            routeConflator.getWorkingEntitySpace().addEntity(routeLine.way, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
            routeRelation.addMember(routeLine.way, "");
            routeRelation.setTag(OSMEntity.KEY_NAME, "**" + routeRelation.getTag(OSMEntity.KEY_NAME));
        }
    }

    /**
     * Outputs the segment ways to an OSM XML file
     */
    public void debugOutputSegments(final RouteDataManager entitySpace) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        final short matchMask = SegmentMatch.matchTypeBoundingBox;//SegmentMatch.matchTypeDotProduct | SegmentMatch.matchTypeDistance;//0*SegmentMatch.matchMaskAll;
        final boolean showBestMatchesOnly = false;

        //output the route's shape segments
        OSMNode originNode = null, lastNode;
        final Map<Long, OSMWay> routeLineSegmentWays = new HashMap<>(routeLine.segments.size());
        for(final LineSegment rlSegment : routeLine.segments) {
            final RouteLineSegment mainSegment = (RouteLineSegment) rlSegment;
            final OSMWay segmentWay = segmentSpace.createWay(null, null);
            if(originNode == null) {
                if(mainSegment.originNode != null) {
                    originNode = (OSMNode) segmentSpace.addEntity(mainSegment.originNode, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                } else {
                    originNode = segmentSpace.createNode(mainSegment.originPoint.x, mainSegment.originPoint.y, null);
                }
            }
            if(mainSegment.destinationNode != null) {
                lastNode = (OSMNode) segmentSpace.addEntity(mainSegment.destinationNode, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
            } else {
                lastNode = segmentSpace.createNode(mainSegment.destinationPoint.x, mainSegment.destinationPoint.y, null);
            }
            segmentWay.appendNode(originNode);
            segmentWay.appendNode(lastNode);
            segmentWay.setTag("segid", Long.toString(mainSegment.id));
            segmentWay.setTag(OSMEntity.KEY_REF, tripId + ":" + tripMarker + ": " + routeLine.way.getTag(OSMEntity.KEY_NAME));
            segmentWay.setTag(OSMEntity.KEY_NAME, String.format("#%d/%d", mainSegment.segmentIndex, mainSegment.nodeIndex));
            segmentWay.setTag(OSMEntity.KEY_DESCRIPTION, String.format("[%.01f, %.01f], nd[%d/%d]: %d matches", mainSegment.midPoint.y, mainSegment.midPoint.x, mainSegment.originNode != null ? mainSegment.originNode.osm_id : 0, mainSegment.destinationNode != null ? mainSegment.destinationNode.osm_id : 0, mainSegment.bestMatchForLine.size()));
            OSMEntity.copyTag(routeLine.way, segmentWay, "highway");
            OSMEntity.copyTag(routeLine.way, segmentWay, "railway");
            segmentWay.setTag("oneway", OSMEntity.TAG_YES);
            segmentWay.setTag("matchcount", Integer.toString(mainSegment.getMatchingSegments(matchMask).size()));
            routeLineSegmentWays.put(mainSegment.id, segmentWay);
            //OSMEntity.copyTag(mai, segmentWay, OSMEntity.KEY_NAME);
            originNode = lastNode;
        }

        //also create relations to track segment paths
        final HashMap<String, String> stopTags = new HashMap<>(3);
        stopTags.put(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_STOP_POSITION);
        stopTags.put(routeRelation.getTag(OSMEntity.KEY_ROUTE), OSMEntity.TAG_YES);
        for(final PathTree pathTree: routePathFinder.routePathTrees) {
            final HashMap<String, String> relTags = new HashMap<>(2);
            relTags.put(OSMEntity.KEY_NAME, pathTree.originStop.getPlatform().getTag(OSMEntity.KEY_NAME) + " -> " + pathTree.destinationStop.getPlatform().getTag(OSMEntity.KEY_NAME));
            relTags.put(OSMEntity.KEY_TYPE, OSMEntity.TAG_ROUTE);
            relTags.put("path_id", Long.toString(pathTree.id));
            final OSMRelation pathRelation = segmentSpace.createRelation(relTags, null);

            //add the platform nodes
            pathRelation.addMember(segmentSpace.addEntity(pathTree.originStop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null, true, 0), "platform");
            pathRelation.addMember(segmentSpace.addEntity(pathTree.destinationStop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null, true, 0), "platform");

            //and the closest nodes on the RouteLine
            if(pathTree.matchStatus == PathTree.matchMaskAll) {
                final RouteLineSegment fromSegment = pathTree.routeLineSegments.get(0);
                final RouteLineSegment toSegment = pathTree.routeLineSegments.get(pathTree.routeLineSegments.size() - 1);
                final OSMWay fromSegmentWay = routeLineSegmentWays.get(fromSegment.id), toSegmentWay = routeLineSegmentWays.get(toSegment.id);
                stopTags.put(OSMEntity.KEY_NAME, pathTree.originStop.getPlatform().getTag(OSMEntity.KEY_NAME));
                fromSegmentWay.getFirstNode().setTags(stopTags);
                pathRelation.addMember(fromSegmentWay.getFirstNode(), "stop");
                stopTags.put(OSMEntity.KEY_NAME, pathTree.destinationStop.getPlatform().getTag(OSMEntity.KEY_NAME));
                toSegmentWay.getLastNode().setTags(stopTags);
                pathRelation.addMember(toSegmentWay.getLastNode(), "stop");

                //and add the associated RouteLineSegment ways created in the previous loop
                for (final RouteLineSegment segment : pathTree.routeLineSegments) {
                    final OSMWay segmentWay = routeLineSegmentWays.get(segment.id);
                    if (segmentWay != null) {
                        pathRelation.addMember(segmentWay, "");
                    } else {
                        System.out.println("NULL SEG");
                    }
                }
            } else {
                System.out.format("NOTICE: pathTree id %d: unable to determine routeLineSegments (status is %d)\n", pathTree.id, pathTree.matchStatus);
            }
        }

        //output the segments matching the main route path
        OSMNode matchOriginNode, matchLastNode;
        final HashMap<String, OSMNode> nodeSpaceMap = new HashMap<>(entitySpace.allNodes.size());
        final HashMap<String, OSMWay> waySpaceMap = new HashMap<>(entitySpace.allWays.size());
        String pointMatchKeyOrigin, pointMatchKeyDestination;
        final String nodeMatchFormat = "%.07f:%.07f", wayMatchFormat = "%d:%d";
        for(final LineSegment lineSegment : routeLine.segments) {
            final RouteLineSegment routeLineSegment = (RouteLineSegment) lineSegment;

            //get the LineMatches associated with the routeLineSegment (i.e. segment->OSMWay matches)
            final Map<Long, LineMatch> lineMatchesForRouteLineSegment = routeLine.lineMatchesByRouteLineSegmentId.get(routeLineSegment.id);
            if (lineMatchesForRouteLineSegment == null) {
                continue;
            }

            //look up the origin node in the segment space, and use it if already present
            if (showBestMatchesOnly) {
                if(routeLineSegment.bestMatchOverall != null) {
                    debugCreateSegmentWay(routeLineSegment.bestMatchOverall, routeLine.lineMatchesByOSMWayId.get(routeLineSegment.bestMatchOverall.matchingSegment.getParent().way.osm_id).getRouteLineMatchesForSegment(routeLineSegment.bestMatchOverall.matchingSegment, matchMask).size(), nodeMatchFormat, wayMatchFormat, nodeSpaceMap, waySpaceMap, entitySpace, segmentSpace);
                }
            } else {
                //and iterate over them
                //System.out.println(lineMatchesForRouteLineSegment.size() + " matches for " + routeLineSegment);
                for (final LineMatch segmentLineMatch : lineMatchesForRouteLineSegment.values()) {
                    //now get the matching OSMLineSegments for the given LineMatch and the current routeLineSegment
                    final List<SegmentMatch> routeLineSegmentMatches = segmentLineMatch.getOSMLineMatchesForSegment(routeLineSegment, matchMask);
                    //and output their OSMLineSegment as a way for display purposes
                    for (final SegmentMatch osmLineMatch : routeLineSegmentMatches) {
                        debugCreateSegmentWay(osmLineMatch, segmentLineMatch.getRouteLineMatchesForSegment(osmLineMatch.matchingSegment, matchMask).size(), nodeMatchFormat, wayMatchFormat, nodeSpaceMap, waySpaceMap, entitySpace, segmentSpace);
                    }
                }
            }
        }

        //output the cells generated to process the area
        for(final Cell cell : Cell.allCells) {
            final Region bbox = cell.boundingBox;
            List<OSMNode> cellNodes = new ArrayList<>(5);
            final OSMNode oNode = segmentSpace.createNode(bbox.origin.x, bbox.origin.y, null);
            cellNodes.add(oNode);
            cellNodes.add(segmentSpace.createNode(bbox.extent.x, bbox.origin.y, null));
            cellNodes.add(segmentSpace.createNode(bbox.extent.x, bbox.extent.y, null));
            cellNodes.add(segmentSpace.createNode(bbox.origin.x, bbox.extent.y, null));
            cellNodes.add(oNode);
            final OSMWay cellWay = segmentSpace.createWay(null, cellNodes);
            cellWay.setTag("landuse", "construction");
            cellWay.setTag("name", cell.toString());
        }

        final String fileName = String.format("%s/debug_segments_%s.osm", Config.sharedInstance.outputDirectory, tripMarker);
        segmentSpace.outputXml(fileName);
        System.out.format("INFO: debug segments file output to %s\n", fileName);
    }
    private static void debugCreateSegmentWay(final SegmentMatch osmSegmentMatch, final int totalMatchCount, final String nodeMatchFormat, final String wayMatchFormat, final Map<String, OSMNode> nodeSpaceMap, final Map<String, OSMWay> waySpaceMap, final OSMEntitySpace entitySpace, final OSMEntitySpace segmentSpace) {
        OSMNode matchOriginNode, matchLastNode;

        //look up the origin node in the segment space, and use it if already present
        final String pointMatchKeyOrigin = String.format(nodeMatchFormat, osmSegmentMatch.matchingSegment.originPoint.y, osmSegmentMatch.matchingSegment.originPoint.x);
        matchOriginNode = nodeSpaceMap.get(pointMatchKeyOrigin);
        if (matchOriginNode == null) {
            if (osmSegmentMatch.matchingSegment.originNode != null && entitySpace.allNodes.containsKey(osmSegmentMatch.matchingSegment.originNode.osm_id)) {
                matchOriginNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(osmSegmentMatch.matchingSegment.originNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                matchOriginNode.setTag("node_id", Long.toString(osmSegmentMatch.matchingSegment.originNode.osm_id));
            } else {
                matchOriginNode = segmentSpace.createNode(osmSegmentMatch.matchingSegment.originPoint.x, osmSegmentMatch.matchingSegment.originPoint.y, null);
            }
            nodeSpaceMap.put(pointMatchKeyOrigin, matchOriginNode);
        }

        //look up the destination node in the segment space, and use it if already present
        final String pointMatchKeyDestination = String.format(nodeMatchFormat, osmSegmentMatch.matchingSegment.destinationPoint.y, osmSegmentMatch.matchingSegment.destinationPoint.x);
        matchLastNode = nodeSpaceMap.get(pointMatchKeyDestination);
        if (matchLastNode == null) {
            if (osmSegmentMatch.matchingSegment.destinationNode != null && entitySpace.allNodes.containsKey(osmSegmentMatch.matchingSegment.destinationNode.osm_id)) {
                matchLastNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(osmSegmentMatch.matchingSegment.destinationNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                matchLastNode.setTag("node_id", Long.toString(osmSegmentMatch.matchingSegment.destinationNode.osm_id));
            } else {
                matchLastNode = segmentSpace.createNode(osmSegmentMatch.matchingSegment.destinationPoint.x, osmSegmentMatch.matchingSegment.destinationPoint.y, null);
            }
            nodeSpaceMap.put(pointMatchKeyDestination, matchLastNode);
        }

        //get a list of the OSMLineSegments associated with the current segment
        final OSMWay segmentWay = osmSegmentMatch.matchingSegment.getParent().way;

        //finally, create the way to represent the segment, after checking for duplicate matches
        final String wayKey = String.format(wayMatchFormat, segmentWay.osm_id, osmSegmentMatch.matchingSegment.id);
        OSMWay matchingSegmentWay = waySpaceMap.get(wayKey);
        if (matchingSegmentWay == null) {
            matchingSegmentWay = segmentSpace.createWay(null, null);
            matchingSegmentWay.appendNode(matchOriginNode);
            matchingSegmentWay.appendNode(matchLastNode);
            matchingSegmentWay.setTag("way_id", Long.toString(segmentWay.osm_id));
            if (segmentWay.hasTag(OSMEntity.KEY_NAME)) {
                matchingSegmentWay.setTag("way_name", segmentWay.getTag(OSMEntity.KEY_NAME));
            } else {
                matchingSegmentWay.setTag("way_name", "[noname]");
            }
            matchingSegmentWay.setTag(OSMEntity.KEY_NAME, osmSegmentMatch.matchingSegment.segmentIndex + "/" + osmSegmentMatch.matchingSegment.nodeIndex);
            matchingSegmentWay.setTag("hashid", Long.toString(osmSegmentMatch.matchingSegment.id));

            //also copy over some relevant tags
            OSMEntity.copyTag(segmentWay, matchingSegmentWay, "highway");
            OSMEntity.copyTag(segmentWay, matchingSegmentWay, "railway");
            if (segmentWay.hasTag("oneway")) {
                OSMEntity.copyTag(segmentWay, matchingSegmentWay, "oneway");
            }
            waySpaceMap.put(wayKey, matchingSegmentWay);
        }
        matchingSegmentWay.setTag("note", String.format("With: %d/%d, DP: %.02f, DIST: %.01f/%.01f", osmSegmentMatch.mainSegment.segmentIndex, osmSegmentMatch.mainSegment.nodeIndex, osmSegmentMatch.dotProduct, osmSegmentMatch.orthogonalDistance, osmSegmentMatch.midPointDistance));
        matchingSegmentWay.setTag("matchcount", Integer.toString(totalMatchCount));
    }

    /**
     * Checks if the total match counts in the various match indexes are identical
     */
    public void debugCheckMatchIndexIntegrity(final String message) {
        if(!RouteConflator.debugEnabled) {
            return;
        }

        //garbage collect to get better SegmentMatch total count
        System.gc();

        //debug: check total match counts
        int routeLineSegmentMatchCount = 0, overallLineMatchCounts = 0, lineMatchByRouteLineCounts = 0, lineMatchByOSMLineCounts = 0, lineMatchCountByOSM = 0, lineMatchCountByRLSeg = 0;

        //check the routeLine's LineMatch index integrity
        lineMatchCountByOSM = routeLine.lineMatchesByOSMWayId.size();
        final List<LineMatch> rlLineMatchIndex = new ArrayList<>(lineMatchCountByOSM);
        for(final Map<Long, LineMatch> rlLineMatches : routeLine.lineMatchesByRouteLineSegmentId.values()) {
            for(final LineMatch lineMatch : rlLineMatches.values()) {
                if (!rlLineMatchIndex.contains(lineMatch)) {
                    lineMatchCountByRLSeg ++;
                    rlLineMatchIndex.add(lineMatch);
                }
            }
        }

        //check by LineMatch objects
        for(final LineMatch lineMatch : routeLine.lineMatchesByOSMWayId.values()) {
            overallLineMatchCounts += lineMatch.matchingSegments.size();

            //check if the matches based on the RouteLineSegments match up
            for(final LineSegment segment : routeLine.segments) {
                final List<SegmentMatch> matchesByRouteLine = lineMatch.getOSMLineMatchesForSegment((RouteLineSegment) segment, SegmentMatch.matchTypeNone);
                lineMatchByRouteLineCounts += matchesByRouteLine.size();
            }

            //and the counts by OSMLineSegment index
            for(final List<SegmentMatch> matchesByOSMLine : lineMatch.matchedSegmentsByOSMLineSegmentId.values()) {
                lineMatchByOSMLineCounts += matchesByOSMLine.size();
            }
        }

        //check the individual RouteLineSegment's match counts
        for(final LineSegment segment : routeLine.segments) {
            final Map<Long, List<SegmentMatch>> routeLineMatchingSegments = ((RouteLineSegment)segment).getMatchingSegments(SegmentMatch.matchTypeNone);
            for(final List<SegmentMatch> matches : routeLineMatchingSegments.values()) {
                routeLineSegmentMatchCount += matches.size();
            }
        }

        System.out.format("INFO: Match Index counts after “%s”:\n\tSegmentMatch: %d global, %d in RouteLineSegments, %d/%d/%d Total/RL/OSM in LineMatches.\n\tLineMatch: %d/%d OSM/RLSeg-based objects\n", message, SegmentMatch.totalCount, routeLineSegmentMatchCount, overallLineMatchCounts, lineMatchByRouteLineCounts, lineMatchByOSMLineCounts, lineMatchCountByOSM, lineMatchCountByRLSeg);
    }
    @Override
    public String toString() {
        return String.format("Route “%s” (ref %s, GTFS Trip Marker %s)", name, ref, tripMarker);
    }
}
