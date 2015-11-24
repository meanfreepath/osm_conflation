package com.company;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by nick on 11/23/15.
 */
public class Path {
    public List<PathSegment> pathSegments = new ArrayList<>(512);
    public int pathCount = 0;
    public double score = 0.0;

    public void prependSegment(final PathSegment segment) {
        pathSegments.add(0, segment);
        score += segment.line.matchObject.getScore();
    }
    public void addSegment(final PathSegment segment) {
        pathSegments.add(segment);
        score += segment.line.matchObject.getScore();
    }
    public void calculateScore() {
        score = 0.0;
        for(final PathSegment segment : pathSegments) {
            score += segment.calculateScore();
        }
    }
    public String toString() {
        final StringBuilder pathStr = new StringBuilder(256);
        for(final PathSegment pathSegment : pathSegments) {
            pathStr.append(pathSegment.line.line.getTag("name"));
            pathStr.append(":");
            pathStr.append(pathSegment.line.line.osm_id);
            pathStr.append("->");
        }
        pathStr.replace(pathStr.length() - 2, pathStr.length(), "\n");
        return pathStr.toString();
    }
}
