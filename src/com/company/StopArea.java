package com.company;

import OSM.OSMEntity;
import OSM.OSMNode;

/**
 * Class that associates stop platforms with their respective node on the appropriate way
 * Created by nick on 1/27/16.
 */
public class StopArea {
    public OSMEntity platform; //can be a node or way
    private OSMNode stopPosition = null;
    public final StopWayMatch wayMatches;

    public StopArea(final OSMEntity platform, final OSMNode stopPosition) {
        this.platform = platform;
        this.stopPosition = stopPosition;

        wayMatches = new StopWayMatch(this);
    }
    public OSMNode getStopPosition() {
        return stopPosition;
    }
    public void setStopPosition(final OSMNode stopNode) {
        stopPosition = stopNode;
    }
}
