package Conflation;

import NewPathFinding.PathTree;
import NewPathFinding.RoutePathFinder;
import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Tracks the data on a particular route
 * Created by nick on 1/27/16.
 */
public class Route {
    public final RouteConflator.LineComparisonOptions wayMatchingOptions;
    public String name, ref;
    public final OSMRelation routeRelation;
    public final String routeType;
    public final List<StopArea> stops;
    public final RouteLineWaySegments routeLine;
    public final RoutePathFinder routePathFinder;

    public Route(final OSMRelation routeRelation, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        this.routeRelation = routeRelation;
        this.wayMatchingOptions = wayMatchingOptions;
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);

        //get a handle on the provided route path, and remove any duplicated nodes from it (can screw up segmenting/matching)
        final List<OSMRelation.OSMRelationMember> members = routeRelation.getMembers("");
        final OSMWay routePath = (OSMWay) members.get(0).member;
        routePath.setTag("gtfs:ignore", "yes");
        final OSMNode[] duplicateNodes = routePath.identifyDuplicateNodesByPosition(0.1);
        for(final OSMNode dupeNode : duplicateNodes) {
            routePath.removeNode(dupeNode);
        }

        routeLine = new RouteLineWaySegments(routePath, wayMatchingOptions.maxSegmentLength);

        final List<OSMRelation.OSMRelationMember> routeStops = routeRelation.getMembers(OSMEntity.TAG_PLATFORM);
        stops = new ArrayList<>(routeStops.size());
        for(final OSMRelation.OSMRelationMember stopMember : routeStops) {
            stops.add(new StopArea(stopMember.member, null));
        }

        routePathFinder = new RoutePathFinder(this);
    }

    /**
     * Copy contructor used for transferring routes between entity spaces
     * @param oldRoute
     * @param newEntitySpace
     * @param stops
     */
    protected Route(final Route oldRoute, final OSMEntitySpace newEntitySpace, final List<StopArea> stops) {
        this.wayMatchingOptions = oldRoute.wayMatchingOptions;
        routeRelation = newEntitySpace.createRelation(oldRoute.routeRelation.getTags(), null);
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);
        routeLine = new RouteLineWaySegments(oldRoute.routeLine.way, wayMatchingOptions.maxSegmentLength);

        //add the imported route's stops to the new entity space
        this.stops = new ArrayList<>(stops); //TODO - add to entity space?

        routePathFinder = new RoutePathFinder(this); //TODO - any special cases?
    }
    public void syncStopsWithRelation() {
        for(final StopArea stop : stops) {
            if(!routeRelation.containsMember(stop.getPlatform())) {
                routeRelation.addMember(stop.getPlatform(), OSMEntity.MEMBERSHIP_PLATFORM);
            }
            final OSMNode stopPosition = stop.getStopPosition();
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
        routePathFinder.splitRouteLineByStops();

        routePathFinder.findPaths(routeConflator);

        //if the route wasn't fully matched, mark it
        if(routePathFinder.getFailedPaths() > 0) {
            routeConflator.getWorkingEntitySpace().addEntity(routeLine.way, OSMEntity.TagMergeStrategy.keepTags, null);
            routeRelation.addMember(routeLine.way, "");
            routeRelation.setTag(OSMEntity.KEY_NAME, "**" + routeRelation.getTag(OSMEntity.KEY_NAME));
        }
    }

    /**
     * Outputs the segment ways to an OSM XML file
     */
    public void debugOutputSegments(final OSMEntitySpace entitySpace, final Collection<OSMWaySegments> candidateLines) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        final short matchMask = SegmentMatch.matchMaskAll;//SegmentMatch.matchTypeDotProduct | SegmentMatch.matchTypeDistance;//0*SegmentMatch.matchMaskAll;
        final boolean showBestMatchesOnly = true;

        //output the route's shape segments
        OSMNode originNode = null, lastNode;
        final Map<Long, OSMWay> routeLineSegmentWays = new HashMap<>(routeLine.segments.size());
        for(final LineSegment rlSegment : routeLine.segments) {
            final RouteLineSegment mainSegment = (RouteLineSegment) rlSegment;
            final OSMWay segmentWay = segmentSpace.createWay(null, null);
            if(originNode == null) {
                if(mainSegment.originNode != null) {
                    originNode = (OSMNode) segmentSpace.addEntity(mainSegment.originNode, OSMEntity.TagMergeStrategy.keepTags, null);
                } else {
                    originNode = segmentSpace.createNode(mainSegment.originPoint.x, mainSegment.originPoint.y, null);
                }
            }
            if(mainSegment.destinationNode != null) {
                lastNode = (OSMNode) segmentSpace.addEntity(mainSegment.destinationNode, OSMEntity.TagMergeStrategy.keepTags, null);
            } else {
                lastNode = segmentSpace.createNode(mainSegment.destinationPoint.x, mainSegment.destinationPoint.y, null);
            }
            segmentWay.appendNode(originNode);
            segmentWay.appendNode(lastNode);
            segmentWay.setTag("segid", Long.toString(mainSegment.id));
            segmentWay.setTag(OSMEntity.KEY_REF, routeLine.way.getTag(OSMEntity.KEY_NAME));
            segmentWay.setTag(OSMEntity.KEY_NAME, String.format("#%d/%d", mainSegment.segmentIndex, mainSegment.nodeIndex));
            segmentWay.setTag(OSMEntity.KEY_DESCRIPTION, String.format("[%.04f, %.04f], nd[%d/%d]: %d matches", mainSegment.midPoint.y, mainSegment.midPoint.x, mainSegment.originNode != null ? mainSegment.originNode.osm_id : 0, mainSegment.destinationNode != null ? mainSegment.destinationNode.osm_id : 0, mainSegment.bestMatchForLine.size()));
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
            pathRelation.addMember(segmentSpace.addEntity(pathTree.originStop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null), "platform");
            pathRelation.addMember(segmentSpace.addEntity(pathTree.destinationStop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null), "platform");

            //and the closest nodes on the RouteLine
            if(pathTree.routeLineSegments != null) {
                final RouteLineSegment fromSegment = pathTree.routeLineSegments.get(0), toSegment = pathTree.routeLineSegments.get(pathTree.routeLineSegments.size() - 1);
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
        for(RouteConflator.Cell cell : RouteConflator.Cell.allCells) {
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

        segmentSpace.outputXml("segments" + routeRelation.osm_id + ".osm");
    }
    private static void debugCreateSegmentWay(final SegmentMatch osmSegmentMatch, final int totalMatchCount, final String nodeMatchFormat, final String wayMatchFormat, final Map<String, OSMNode> nodeSpaceMap, final Map<String, OSMWay> waySpaceMap, final OSMEntitySpace entitySpace, final OSMEntitySpace segmentSpace) {
        OSMNode matchOriginNode, matchLastNode;

        //look up the origin node in the segment space, and use it if already present
        final String pointMatchKeyOrigin = String.format(nodeMatchFormat, osmSegmentMatch.matchingSegment.originPoint.y, osmSegmentMatch.matchingSegment.originPoint.x);
        matchOriginNode = nodeSpaceMap.get(pointMatchKeyOrigin);
        if (matchOriginNode == null) {
            if (osmSegmentMatch.matchingSegment.originNode != null && entitySpace.allNodes.containsKey(osmSegmentMatch.matchingSegment.originNode.osm_id)) {
                matchOriginNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(osmSegmentMatch.matchingSegment.originNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
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
                matchLastNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(osmSegmentMatch.matchingSegment.destinationNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
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
    public void debugCheckMatchIndexIntegrity() {
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

        System.out.format("INDEX COUNTS:\n\tSegmentMatch: %d global, %d in RouteLineSegments, %d/%d/%d Total/RL/OSM in LineMatches.\n\tLineMatch: %d/%d OSM/RLSeg-based objects\n", SegmentMatch.totalCount, routeLineSegmentMatchCount, overallLineMatchCounts, lineMatchByRouteLineCounts, lineMatchByOSMLineCounts, lineMatchCountByOSM, lineMatchCountByRLSeg);
    }
}
