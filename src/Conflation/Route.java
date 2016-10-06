package Conflation;

import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

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
    public final OSMWay routePath;
    public final RouteLineWaySegments routeLine;

    public Route(final OSMRelation routeRelation, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        this.routeRelation = routeRelation;
        this.wayMatchingOptions = wayMatchingOptions;
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);

        final List<OSMRelation.OSMRelationMember> members = routeRelation.getMembers("");
        routePath = (OSMWay) members.get(0).member;
        routePath.setTag("gtfs:ignore", "yes");
        routeLine = new RouteLineWaySegments(routePath, wayMatchingOptions.maxSegmentLength);

        final List<OSMRelation.OSMRelationMember> routeStops = routeRelation.getMembers(OSMEntity.TAG_PLATFORM);
        stops = new ArrayList<>(routeStops.size());
        for(final OSMRelation.OSMRelationMember stopMember : routeStops) {
            stops.add(new StopArea(stopMember.member, null));
        }
    }
    public Route(final Route oldRoute, final OSMEntitySpace newEntitySpace, final List<StopArea> stops) {
        this.wayMatchingOptions = oldRoute.wayMatchingOptions;
        routeRelation = newEntitySpace.createRelation(oldRoute.routeRelation.getTags(), null);
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);
        routePath = oldRoute.routePath; //NOTE: not added to new route's entity space!
        routeLine = new RouteLineWaySegments(routePath, wayMatchingOptions.maxSegmentLength);

        //add the imported route's stops to the new entity space
        this.stops = stops;
    }
    public void syncStopsWithRelation() {
        for(final StopArea stop : stops) {
            if(!routeRelation.containsMember(stop.platform)) {
                routeRelation.addMember(stop.platform, OSMEntity.MEMBERSHIP_PLATFORM);
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

    /**
     * Outputs the segment ways to an OSM XML file
     */
    public void debugOutputSegments(final OSMEntitySpace entitySpace, final Collection<OSMWaySegments> candidateLines) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        final short matchMask = SegmentMatch.matchMaskAll;//SegmentMatch.matchTypeDotProduct | SegmentMatch.matchTypeDistance;//0*SegmentMatch.matchMaskAll;

        //output the route's shape segments
        OSMNode originNode = null, lastNode;
        for(final LineSegment rlSegment : routeLine.segments) {
            final RouteLineSegment mainSegment = (RouteLineSegment) rlSegment;
            final OSMWay segmentWay = segmentSpace.createWay(null, null);
            if(originNode == null) {
                if(mainSegment.originNode != null) {
                    originNode = (OSMNode) segmentSpace.addEntity(mainSegment.originNode, OSMEntity.TagMergeStrategy.keepTags, null);
                } else {
                    originNode = segmentSpace.createNode(mainSegment.originPoint.latitude, mainSegment.originPoint.longitude, null);
                }
            }
            if(mainSegment.destinationNode != null) {
                lastNode = (OSMNode) segmentSpace.addEntity(mainSegment.destinationNode, OSMEntity.TagMergeStrategy.keepTags, null);
            } else {
                lastNode = segmentSpace.createNode(mainSegment.destinationPoint.latitude, mainSegment.destinationPoint.longitude, null);
            }
            segmentWay.appendNode(originNode);
            segmentWay.appendNode(lastNode);
            segmentWay.setTag(OSMEntity.KEY_REF, routeLine.way.getTag(OSMEntity.KEY_NAME));
            segmentWay.setTag(OSMEntity.KEY_NAME, String.format("#%d/%d", mainSegment.segmentIndex, mainSegment.nodeIndex));
            segmentWay.setTag(OSMEntity.KEY_DESCRIPTION, String.format("[%.04f, %.04f], nd[%d/%d]: %d matches", mainSegment.midPoint.latitude, mainSegment.midPoint.longitude, mainSegment.originNode != null ? mainSegment.originNode.osm_id : 0, mainSegment.destinationNode != null ? mainSegment.destinationNode.osm_id : 0, mainSegment.bestMatchForLine.size()));
            OSMEntity.copyTag(routeLine.way, segmentWay, "highway");
            OSMEntity.copyTag(routeLine.way, segmentWay, "railway");
            segmentWay.setTag("oneway", OSMEntity.TAG_YES);
            segmentWay.setTag("matchcount", Integer.toString(mainSegment.getMatchingSegments(matchMask).size()));
            //OSMEntity.copyTag(mai, segmentWay, OSMEntity.KEY_NAME);
            originNode = lastNode;
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
            final List<LineMatch> lineMatchesForRouteLineSegment = routeLine.lineMatchesBySegmentId.get(routeLineSegment.id);
            if (lineMatchesForRouteLineSegment == null) {
                continue;
            }

            //and iterate over them
            //System.out.println(lineMatchesForRouteLineSegment.size() + " matches for " + routeLineSegment);
            for (final LineMatch segmentLineMatch : lineMatchesForRouteLineSegment) {
                //now get the matching OSMLineSegments for the given LineMatch and the current routeLineSegment
                final List<SegmentMatch> routeLineSegmentMatches = segmentLineMatch.getOSMLineMatchesForSegment(routeLineSegment, matchMask);
                //System.out.println(routeLineSegmentMatches.size() + " matches for linematch + " + routeLineSegment);

                //and output their OSMLineSegment as a way for display purposes
                for (final SegmentMatch osmLineMatch : routeLineSegmentMatches) {

                    //look up the origin node in the segment space, and use it if already present
                    pointMatchKeyOrigin = String.format(nodeMatchFormat, osmLineMatch.matchingSegment.originPoint.latitude, osmLineMatch.matchingSegment.originPoint.longitude);
                    matchOriginNode = nodeSpaceMap.get(pointMatchKeyOrigin);
                    if (matchOriginNode == null) {
                        if (osmLineMatch.matchingSegment.originNode != null && entitySpace.allNodes.containsKey(osmLineMatch.matchingSegment.originNode.osm_id)) {
                            matchOriginNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(osmLineMatch.matchingSegment.originNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
                            matchOriginNode.setTag("node_id", Long.toString(osmLineMatch.matchingSegment.originNode.osm_id));
                        } else {
                            matchOriginNode = segmentSpace.createNode(osmLineMatch.matchingSegment.originPoint.latitude, osmLineMatch.matchingSegment.originPoint.longitude, null);
                        }
                        nodeSpaceMap.put(pointMatchKeyOrigin, matchOriginNode);
                    }

                    //look up the destination node in the segment space, and use it if already present
                    pointMatchKeyDestination = String.format(nodeMatchFormat, osmLineMatch.matchingSegment.destinationPoint.latitude, osmLineMatch.matchingSegment.destinationPoint.longitude);
                    matchLastNode = nodeSpaceMap.get(pointMatchKeyDestination);
                    if (matchLastNode == null) {
                        if (osmLineMatch.matchingSegment.destinationNode != null && entitySpace.allNodes.containsKey(osmLineMatch.matchingSegment.destinationNode.osm_id)) {
                            matchLastNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(osmLineMatch.matchingSegment.destinationNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
                            matchLastNode.setTag("node_id", Long.toString(osmLineMatch.matchingSegment.destinationNode.osm_id));
                        } else {
                            matchLastNode = segmentSpace.createNode(osmLineMatch.matchingSegment.destinationPoint.latitude, osmLineMatch.matchingSegment.destinationPoint.longitude, null);
                        }
                        nodeSpaceMap.put(pointMatchKeyDestination, matchLastNode);
                    }

                    //get a list of the OSMLineSegments associated with the current segment
                    final OSMWay segmentWay = segmentLineMatch.osmLine.way;

                    //finally, create the way to represent the segment, after checking for duplicate matches
                    final String wayKey = String.format(wayMatchFormat, segmentWay.osm_id, osmLineMatch.matchingSegment.id);
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
                        matchingSegmentWay.setTag(OSMEntity.KEY_NAME, osmLineMatch.matchingSegment.segmentIndex + "/" + osmLineMatch.matchingSegment.nodeIndex);
                        matchingSegmentWay.setTag("hashid", Long.toString(osmLineMatch.matchingSegment.id));

                        //also copy over some relevant tags
                        OSMEntity.copyTag(segmentWay, matchingSegmentWay, "highway");
                        OSMEntity.copyTag(segmentWay, matchingSegmentWay, "railway");
                        if (segmentWay.hasTag("oneway")) {
                            OSMEntity.copyTag(segmentWay, matchingSegmentWay, "oneway");
                        }
                        waySpaceMap.put(wayKey, matchingSegmentWay);
                    }
                    matchingSegmentWay.setTag("note", "With: " + osmLineMatch.mainSegment.segmentIndex + "/" + osmLineMatch.mainSegment.nodeIndex + ", DP: " + format.format(osmLineMatch.dotProduct) + ", DIST: " + format.format(osmLineMatch.orthogonalDistance) + "/" + format.format(osmLineMatch.midPointDistance));
                    matchingSegmentWay.setTag("matchcount", Integer.toString(segmentLineMatch.getRouteLineMatchesForSegment(osmLineMatch.matchingSegment, matchMask).size()));
                }
            }
        }

        //output the cells generated to process the area
        for(RouteConflator.Cell cell : RouteConflator.Cell.allCells.values()) {
            final Region bbox = cell.boundingBox;
            List<OSMNode> cellNodes = new ArrayList<>(5);
            final OSMNode oNode = segmentSpace.createNode(bbox.origin.latitude, bbox.origin.longitude, null);
            cellNodes.add(oNode);
            cellNodes.add(segmentSpace.createNode(bbox.origin.latitude, bbox.extent.longitude, null));
            cellNodes.add(segmentSpace.createNode(bbox.extent.latitude, bbox.extent.longitude, null));
            cellNodes.add(segmentSpace.createNode(bbox.extent.latitude, bbox.origin.longitude, null));
            cellNodes.add(oNode);
            final OSMWay cellWay = segmentSpace.createWay(null, cellNodes);
            cellWay.setTag("landuse", "construction");
            cellWay.setTag("name", cell.toString());
        }

        segmentSpace.outputXml("segments" + routeRelation.osm_id + ".osm");
    }
}
