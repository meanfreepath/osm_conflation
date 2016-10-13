package Conflation;

import NewPathFinding.PathTree;
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
    public final OSMWay routePath;
    public final RouteLineWaySegments routeLine;
    public final List<PathTree> routePathTrees;

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
        routePathTrees = new ArrayList<>(stops.size());
    }
    public Route(final Route oldRoute, final OSMEntitySpace newEntitySpace, final List<StopArea> stops) {
        this.wayMatchingOptions = oldRoute.wayMatchingOptions;
        routeRelation = newEntitySpace.createRelation(oldRoute.routeRelation.getTags(), null);
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);
        routePath = oldRoute.routePath; //NOTE: not added to new route's entity space!
        routeLine = new RouteLineWaySegments(routePath, wayMatchingOptions.maxSegmentLength);

        //add the imported route's stops to the new entity space
        this.stops = stops;
        routePathTrees = new ArrayList<>(stops.size());
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

    /**
     * Takes the stop data and uses it to split the RouteLine into route legs, for later path discovery
     */
    public void spliteRouteLineByStops(final OSMEntitySpace workingEntitySpace) {
        final HashMap<String, String> debugTags = new HashMap<>(2);
        debugTags.put(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_STOP_POSITION);
        debugTags.put(OSMEntity.KEY_BUS, OSMEntity.TAG_YES);

        System.out.println("Route " + routeLine.way.osm_id + ": " + stops.size() + " stops");
        //Find the closest segment and point on the routeLine to the stops' positions
        PathTree lastLeg = null;
        for(final StopArea curStop : stops) {
            //create the PathTree starting at the current stop, and cap the previous PathTree, if any
            final PathTree curLeg = new PathTree(routeLine, curStop, lastLeg);
            routePathTrees.add(curLeg);
            if(lastLeg != null) {
                lastLeg.setToStop(curStop);
            }

            //try to match the position of the stop's platform to a segment on the RouteLine
            if(curStop.getStopPosition() != null) {
                final Point stopPoint = curStop.getStopPosition().getCentroid();
                final RouteLineSegment closestSegment = (RouteLineSegment) routeLine.closestSegmentToPoint(stopPoint, StopArea.maxDistanceFromPlatformToWay);

                if(closestSegment != null) {
                    //get the closest point on the closest segment, and use it to split the it at that point
                    final Point closestPointOnRouteLine = closestSegment.closestPointToPoint(stopPoint);
                    final Point closestPointToPlatform;
                    final double nodeTolerance = closestSegment.length / 5.0;

                    //do a quick tolerance check on the LineSegment's existing points (no need to split segment if close enough to an existing point)
                    if(Point.distance(closestPointOnRouteLine, closestSegment.destinationPoint) < nodeTolerance) {
                        closestPointToPlatform = closestSegment.destinationPoint;
                    } else if(Point.distance(closestPointOnRouteLine, closestSegment.originPoint) < nodeTolerance) {
                        closestPointToPlatform = closestSegment.originPoint;
                    } else {
                        closestPointToPlatform = new Point(closestPointOnRouteLine.latitude, closestPointOnRouteLine.longitude);
                        routeLine.insertPoint(closestPointToPlatform, closestSegment, 0.0);
                    }

                    //set the newly-added node as the start/end point of the current/last leg
                    curLeg.setRouteLineStart(closestPointToPlatform);
                    if(lastLeg != null) {
                        lastLeg.setRouteLineEnd(closestPointToPlatform);
                    }
                }
            }
            lastLeg = curLeg;
        }
        //remove the last leg, since it "starts" at the last stop
        if(routePathTrees.size() > 0) {
            routePathTrees.remove(routePathTrees.size() - 1);
        }

        //and do some final processing on the PathTrees to get them ready for the path matching stage
        for(final PathTree routeLeg : routePathTrees) {
            routeLeg.compileRouteLineSegments();
        }
    }
    /**
     * Outputs the segment ways to an OSM XML file
     */
    public void debugOutputSegments(final OSMEntitySpace entitySpace, final Collection<OSMWaySegments> candidateLines) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        final short matchMask = SegmentMatch.matchTypeTravelDirection;//SegmentMatch.matchMaskAll;//SegmentMatch.matchTypeDotProduct | SegmentMatch.matchTypeDistance;//0*SegmentMatch.matchMaskAll;

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
            segmentWay.setTag("segid", Long.toString(mainSegment.id));
            segmentWay.setTag(OSMEntity.KEY_REF, routeLine.way.getTag(OSMEntity.KEY_NAME));
            segmentWay.setTag(OSMEntity.KEY_NAME, String.format("#%d/%d", mainSegment.segmentIndex, mainSegment.nodeIndex));
            segmentWay.setTag(OSMEntity.KEY_DESCRIPTION, String.format("[%.04f, %.04f], nd[%d/%d]: %d matches", mainSegment.midPoint.latitude, mainSegment.midPoint.longitude, mainSegment.originNode != null ? mainSegment.originNode.osm_id : 0, mainSegment.destinationNode != null ? mainSegment.destinationNode.osm_id : 0, mainSegment.bestMatchForLine.size()));
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
        for(final PathTree pathTree: routePathTrees) {
            final HashMap<String, String> relTags = new HashMap<>(2);
            relTags.put(OSMEntity.KEY_NAME, pathTree.fromStop.getPlatform().getTag(OSMEntity.KEY_NAME) + " -> " + pathTree.toStop.getPlatform().getTag(OSMEntity.KEY_NAME));
            relTags.put(OSMEntity.KEY_TYPE, OSMEntity.TAG_ROUTE);
            final OSMRelation pathRelation = segmentSpace.createRelation(relTags, null);

            //add the platform nodes
            pathRelation.addMember(segmentSpace.addEntity(pathTree.fromStop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null), "platform");
            pathRelation.addMember(segmentSpace.addEntity(pathTree.toStop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null), "platform");

            //and the closest nodes on the RouteLine
            final RouteLineSegment fromSegment = pathTree.routeLineSegments.get(0), toSegment = pathTree.routeLineSegments.get(pathTree.routeLineSegments.size() - 1);
            final OSMWay fromSegmentWay = routeLineSegmentWays.get(fromSegment.id), toSegmentWay = routeLineSegmentWays.get(toSegment.id);
            stopTags.put(OSMEntity.KEY_NAME, pathTree.fromStop.getPlatform().getTag(OSMEntity.KEY_NAME));
            fromSegmentWay.getFirstNode().setTags(stopTags);
            pathRelation.addMember(fromSegmentWay.getFirstNode(), "stop");
            stopTags.put(OSMEntity.KEY_NAME, pathTree.toStop.getPlatform().getTag(OSMEntity.KEY_NAME));
            toSegmentWay.getLastNode().setTags(stopTags);
            pathRelation.addMember(toSegmentWay.getLastNode(), "stop");

            //and add the associated RouteLineSegment ways created in the previous loop
            for(final RouteLineSegment segment : pathTree.routeLineSegments) {
                final OSMWay segmentWay = routeLineSegmentWays.get(segment.id);
                if(segmentWay != null) {
                    pathRelation.addMember(segmentWay, "");
                } else {
                    System.out.println("NULL SEG");
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

    /**
     * Checks if the total match counts in the various match indexes are identical
     */
    public void debugCheckMatchIndexIntegrity() {
        //debug: check total match counts
        int routeLineSegmentMatchCount = 0, overallLineMatchCounts = 0, lineMatchByRouteLineCounts = 0, lineMatchByOSMLineCounts = 0;

        //check by LineMatch objects
        for(final LineMatch lineMatch : routeLine.lineMatches.values()) {
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

        System.out.format("%d global, %d RouteLineSegment, %d/%d/%d LineMatch-based SegmentMatch objects", SegmentMatch.totalCount, routeLineSegmentMatchCount, overallLineMatchCounts, lineMatchByRouteLineCounts, lineMatchByOSMLineCounts);
    }
}
