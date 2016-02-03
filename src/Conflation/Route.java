package Conflation;

import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Tracks the data on a particular route
 * Created by nick on 1/27/16.
 */
public class Route {
    public String name, ref;
    public final OSMRelation routeRelation;
    public final String routeType;
    public final List<StopArea> stops;
    public final OSMWay routePath;
    public final WaySegments routeLine;

    public Route(final OSMRelation routeRelation, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        this.routeRelation = routeRelation;
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);

        final List<OSMRelation.OSMRelationMember> members = routeRelation.getMembers("");
        routePath = (OSMWay) members.get(0).member;
        routePath.setTag("gtfs:ignore", "yes");
        routeLine = new WaySegments(routePath, wayMatchingOptions.maxSegmentLength);

        final List<OSMRelation.OSMRelationMember> routeStops = routeRelation.getMembers(OSMEntity.TAG_PLATFORM);
        stops = new ArrayList<>(routeStops.size());
        for(final OSMRelation.OSMRelationMember stopMember : routeStops) {
            stops.add(new StopArea(stopMember.member, null));
        }
    }
    public Route(final Route oldRoute, final OSMEntitySpace newEntitySpace, final List<StopArea> stops, final RouteConflator.LineComparisonOptions wayMatchingOptions) {
        routeRelation = newEntitySpace.createRelation(oldRoute.routeRelation.getTags(), null);
        routeType = routeRelation.getTag(OSMEntity.KEY_TYPE);
        routePath = oldRoute.routePath; //NOTE: not added to new route's entity space!
        routeLine = new WaySegments(routePath, wayMatchingOptions.maxSegmentLength);

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
    public void debugOutputSegments(final OSMEntitySpace entitySpace, final Collection<WaySegments> candidateLines) throws IOException, InvalidArgumentException {
        final OSMEntitySpace segmentSpace = new OSMEntitySpace(entitySpace.allWays.size() + entitySpace.allNodes.size());
        final DecimalFormat format = new DecimalFormat("#.###");

        //output the route's shape segments
        OSMNode originNode = null, lastNode;
        for(final LineSegment mainSegment : routeLine.segments) {
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
            segmentWay.setTag(OSMEntity.KEY_NAME, mainSegment.toString());
            OSMEntity.copyTag(routeLine.way, segmentWay, "highway");
            OSMEntity.copyTag(routeLine.way, segmentWay, "railway");
            //OSMEntity.copyTag(mai, segmentWay, OSMEntity.KEY_NAME);
            originNode = lastNode;
        }

        //output the segments matching the main route path
        for (final WaySegments matchingLine : candidateLines) {
            OSMNode matchOriginNode = null, matchLastNode;
            for(final LineSegment matchingSegment : matchingLine.segments) {
                if(matchingSegment.bestMatch == null) { //skip non-matching segments
                    continue;
                }

                if(matchOriginNode == null) { //i.e. first node on line
                    if(matchingSegment.originNode != null && entitySpace.allNodes.containsKey(matchingSegment.originNode.osm_id)) {
                        matchOriginNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(matchingSegment.originNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
                    } else {
                        matchOriginNode = segmentSpace.createNode(matchingSegment.originPoint.latitude, matchingSegment.originPoint.longitude, null);
                    }
                }
                if(matchingSegment.destinationNode != null && entitySpace.allNodes.containsKey(matchingSegment.destinationNode.osm_id)) {
                    matchLastNode = (OSMNode) segmentSpace.addEntity(entitySpace.allNodes.get(matchingSegment.destinationNode.osm_id), OSMEntity.TagMergeStrategy.keepTags, null);
                } else {
                    matchLastNode = segmentSpace.createNode(matchingSegment.destinationPoint.latitude, matchingSegment.destinationPoint.longitude, null);
                }

                final OSMWay matchingSegmentWay = segmentSpace.createWay(null, null);
                matchingSegmentWay.appendNode(matchOriginNode);
                matchingSegmentWay.appendNode(matchLastNode);
                matchingSegmentWay.setTag("way_id", Long.toString(matchingSegment.parentSegments.way.osm_id));
                matchingSegmentWay.setTag(OSMEntity.KEY_REF, Integer.toString(matchingSegment.segmentIndex));
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, "highway");
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, "railway");
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, OSMEntity.KEY_NAME);
                OSMEntity.copyTag(matchingSegment.parentSegments.way, matchingSegmentWay, OSMEntity.KEY_NAME);
                if(matchingSegment.bestMatch != null) {
                    matchingSegmentWay.setTag("note", "With: " + matchingSegment.bestMatch.mainSegment + ", DP: " + format.format(matchingSegment.bestMatch.dotProduct) + ", DIST: " + format.format(matchingSegment.bestMatch.orthogonalDistance) + "/" + format.format(matchingSegment.bestMatch.midPointDistance));
                } else {
                    matchingSegmentWay.setTag("note", "No matches");
                    matchingSegmentWay.setTag("tiger:reviewed", "no");
                }
                matchOriginNode = matchLastNode;
            }
            /*for(WaySegments otherSegments : comparison.allCandidateSegments.values()) {
                for(LineSegment otherSegment : otherSegments.segments) {
                    segmentSpace.addEntity(otherSegment.segmentWay, OSMEntitySpace.EntityTagMergeStrategy.keepTags, null);
                }
            }*/
        }
        segmentSpace.outputXml("segments" + routeLine.way.osm_id + ".osm");
    }
}
