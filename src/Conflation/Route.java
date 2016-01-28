package Conflation;

import OSM.*;

import java.util.ArrayList;
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
}
