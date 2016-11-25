package Conflation;

import OSM.*;
import com.company.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages the association of stops/waypoints with their respective OSM ways
 * Created by nick on 1/27/16.
 */
public class StopConflator {
    private final static String stopNameStreetSeparator = " & ";
    private final static double MAX_LEVENSHTEIN_DISTANCE_RATIO = 0.20;
    public static boolean debugEnabled = false;

    /**
     * Match the routeConflator's stops to the best possible OSM way, based on its name, position,
     * way's proximity to routeLineSegment, etc
     */
    protected void matchStopsToWays(final RouteConflator routeConflator) {
        final int stopCount = routeConflator.getAllRouteStops().size();
        if(stopCount == 0) {
            System.out.format("ERROR: No stops found for route_master %s [ref %s]\n", routeConflator.getExportRouteMaster().getTag(OSMEntity.KEY_NAME), routeConflator.getExportRouteMaster().getTag(OSMEntity.KEY_REF));
            return;
        }

        //run a comparison based on proximity to the nearest OSM line for each subroute
        for(final Route route : routeConflator.getExportRoutes()) {
            for(final StopArea routeStop : route.stops) {
                //skip stops that already have a stop position assigned (the stop's way(s) are associated with the stop position)
                if(routeStop.getStopPosition(routeConflator.routeType) != null) {
                    continue;
                }

                //check the routeLine's SegmentMatches for the best matching way for this platform
                final Region osmSegmentSearchRegion = routeStop.getNearbyWaySearchRegion();
                RouteLineSegment routeLineSegment;
                for(final LineSegment lineSegment : route.routeLine.segments) {
                    routeLineSegment = (RouteLineSegment) lineSegment;
                    if(routeLineSegment.bestMatchOverall != null && Region.intersects(lineSegment.boundingBox, osmSegmentSearchRegion)) {
                        routeStop.addProximityMatch(routeLineSegment.bestMatchOverall);
                    }
                }
            }
        }

        //loop through all of the route's stops, assigning name matches as needed
        final StreetNameMatcher matcher = new StreetNameMatcher(Locale.US);
        for(final StopArea stop : routeConflator.getAllRouteStops()) {
            //skip stops that already have a stop position assigned (the stop's way(s) are associated with the stop position)
            if(stop.getStopPosition(routeConflator.routeType) != null) {
                continue;
            }

            //get the stop's name components, to see if we can use the name to match to a nearby way
            final String stopName = stop.getPlatform().getTag(OSMEntity.KEY_NAME);
            final List<StreetNameMatcher.StreetNameComponents> nameComponents;
            if(stopName != null) {
                final String[] stopStreetNames = stopName.split(stopNameStreetSeparator);
                nameComponents = new ArrayList<>(stopStreetNames.length);
                for(final String s : stopStreetNames) {
                    nameComponents.add(matcher.createComponents(s));
                }
            } else {
                nameComponents = null;
            }

            //update the way matches to include matches based on the street and platform names
            for(final Map<Long, StopArea.StopWayMatch> wayMatchesForRoute: stop.wayMatches.values()) { //list of all OSM way matches for a routeLine
                for (final StopArea.StopWayMatch stopWayMatch : wayMatchesForRoute.values()) { //and the individual OSM way matches
                    //Check if the way can be matched by name
                    final String wayName = stopWayMatch.osmLine.way.getTag(OSMEntity.KEY_NAME);
                    if (nameComponents != null && wayName != null) {
                        final StreetNameMatcher.StreetNameComponents wayNameComponents = matcher.createComponents(wayName);
                        final String wayBaseName = String.join(" ", wayNameComponents.baseComponents);
                        final StreetNameMatcher.StreetNameComponents primaryStreetComponents = nameComponents.get(0);
                        final StreetNameMatcher.StreetNameComponents secondaryStreetComponents = nameComponents.size() > 1 ? nameComponents.get(1) : null;

                        final String primaryStreetBaseName = String.join(" ", primaryStreetComponents.baseComponents);
                        final double primaryStringDistance = StreetNameMatcher.damerauLevenshteinDistance(primaryStreetBaseName, wayBaseName, 128);
                        if (debugEnabled) {
                            System.out.println("CHECK PLATFORM PRINAME: " + stop + ": vs " + wayName + "(" + primaryStreetBaseName + "/" + wayBaseName + "): " + primaryStringDistance + "/" + primaryStreetBaseName.length() + ", ratio " + (primaryStringDistance / primaryStreetBaseName.length()));
                        }
                        if (primaryStringDistance / primaryStreetBaseName.length() < MAX_LEVENSHTEIN_DISTANCE_RATIO) {
                            stopWayMatch.setNameMatch(StopArea.WayMatchType.primaryStreet);
                        } else if (secondaryStreetComponents != null) {
                            final String secondaryStreetBaseName = String.join(" ", secondaryStreetComponents.baseComponents);
                            final double secondaryStringDistance = StreetNameMatcher.damerauLevenshteinDistance(secondaryStreetBaseName, wayBaseName, 128);
                            if (debugEnabled) {
                                System.out.println("CHECK PLATFORM SECNAME: " + stop + ": vs " + wayName + "(" + primaryStreetBaseName + "/" + wayBaseName + "): " + secondaryStringDistance + "/" + secondaryStreetBaseName.length() + ", ratio " + (secondaryStringDistance / secondaryStreetBaseName.length()));
                            }
                            if (secondaryStringDistance / secondaryStreetBaseName.length() < MAX_LEVENSHTEIN_DISTANCE_RATIO) {
                                stopWayMatch.setNameMatch(StopArea.WayMatchType.crossStreet);
                            }
                        }
                    }
                }
            }

            //with all the matches checked, choose the best one
            stop.chooseBestWayMatch();
        }
    }

    /**
     * Adds the best match for each stop platform to their best-matched nearby way
     * @param routeConflator The routeConflator whose stops will get stop positions
     */
    protected void createStopPositionsForPlatforms(final RouteConflator routeConflator) {
        for(final StopArea stopArea : routeConflator.getAllRouteStops()) {
            if(stopArea.bestWayMatch == null) {
                continue;
            }

            //check if there's an existing stop_position node that can be associated with the StopArea, and if so there's no need to set now
            if(stopArea.getStopPosition(routeConflator.routeType) != null) {
                continue;
            }

            //get a handle on the best-matching way to the StopArea, and the nearest point to it
            final LineSegment bestSegment = stopArea.bestWayMatch.getClosestSegmentToStopPlatform();
            if(bestSegment == null) {
                System.out.println("WARNING: " + stopArea + " has no nearby matching segment");
                for (final StopArea.SummarizedMatch otherMatch : stopArea.bestWayMatches) {
                    if (otherMatch == stopArea.bestWayMatch) {
                        System.out.println("\tBEST MATCH: " + otherMatch);
                    } else {
                        System.out.println("\t\tALTMATCH: " + otherMatch);
                    }
                }

                continue;
            }
            final Point nearestPointOnSegment = bestSegment.closestPointToPoint(stopArea.getPlatform().getCentroid());

            //Create (or update an existing node) to serve as the stop position node for the platform
            OSMNode nearestNodeOnWay = bestSegment.getParent().way.nearestNodeAtPoint(nearestPointOnSegment, StopArea.stopNodeTolerance);

            //double check the nearest node doesn't have already have a stop_position tag - may belong to another nearby stop!
            if(nearestNodeOnWay != null && OSMEntity.TAG_STOP_POSITION.equals(nearestNodeOnWay.getTag(OSMEntity.KEY_PUBLIC_TRANSPORT))) {
                final String stopAreaRef = stopArea.getPlatform().getTag(OSMEntity.KEY_REF);
                final String stopAreaGtfsId = stopArea.getPlatform().getTag(StopArea.KEY_GTFS_STOP_ID);
                final String nearestNodeRef = nearestNodeOnWay.getTag(OSMEntity.KEY_REF);
                final String nearestNodeGtfsId = nearestNodeOnWay.getTag(StopArea.KEY_GTFS_STOP_ID);

                //if the node has a ref or gtfs:stop_id tag, don't use it if they do not match the StopArea's values
                if(stopAreaRef != null && nearestNodeRef != null && !stopAreaRef.equals(nearestNodeRef)) {
                    nearestNodeOnWay = null;
                } else if(stopAreaGtfsId != null && nearestNodeGtfsId != null && !stopAreaGtfsId.equals(nearestNodeGtfsId)) {
                    nearestNodeOnWay = null;
                }
            }

            //and create a new node if a good candidate can't be found on the way
            if(nearestNodeOnWay == null) {
                nearestNodeOnWay = bestSegment.getParent().insertNode(routeConflator.getWorkingEntitySpace().createNode(nearestPointOnSegment.x, nearestPointOnSegment.y, null), bestSegment);
            }

            //and add the stop position to the stop area
            stopArea.setStopPosition(nearestNodeOnWay, routeConflator.routeType);

            //clear the way matches for the stop, since they're no longer needed once the stop position is set
            if(stopArea.getStopPosition(routeConflator.routeType) != null) {
                stopArea.clearAllWayMatches();
            }

            //warn if no decent match is found
            /*if(stopArea.bestWayMatch.line. == null) {
                System.out.println("No sufficient match found for stop " + stopArea);
            }*/
        }
    }

    /**
     * Outputs the stop platforms/positions for the given route masters into an OSM XML file
     * @param routeConflators
     * @throws IOException
     */
    public void outputStopsForRoutes(final List<RouteConflator> routeConflators) throws IOException {
        for(final RouteConflator routeConflator : routeConflators) {
            final OSMEntitySpace stopPlatformSpace = new OSMEntitySpace(2048);
            for (final StopArea stop : routeConflator.getAllRouteStops()) {
                stopPlatformSpace.addEntity(stop.getPlatform(), OSMEntity.TagMergeStrategy.keepTags, null);
                final OSMNode stopPosition = stop.getStopPosition(routeConflator.routeType);
                if (stopPosition != null) {
                    stopPlatformSpace.addEntity(stopPosition, OSMEntity.TagMergeStrategy.keepTags, null);
                }
            }
            stopPlatformSpace.outputXml(String.format("%s/route_stops_%s.osm", Config.sharedInstance.outputDirectory, routeConflator.gtfsRouteId));
        }
    }
}
