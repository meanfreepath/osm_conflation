package com.company;

import OSM.*;
import Overpass.OverpassConverter;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.SAXException;

import javax.sound.sampled.Line;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {

        OSMEntitySpace mainSpace = new OSMEntitySpace(1024);
        try {
            mainSpace.loadFromXML("routes.osm");

            OSMEntitySpace relationSpace = new OSMEntitySpace(65536);
            for(OSMRelation relation : mainSpace.allRelations.values()) {
                OverpassConverter converter = new OverpassConverter();

                if(relation.getTag("type").equals("route")) {
                    List<OSMRelation.OSMRelationMember> members = relation.getMembers("");
                    OSMWay routePath = (OSMWay) members.get(0).member;
                    /*if(routePath.osm_id != -600) {
                        continue;
                    }*/
                    converter.fetchFromOverpass(routePath, "[\"highway\"~\"motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|unclassified|service|living_street\"]", 0.0004);

                    LineComparison comparison = new LineComparison(routePath, new ArrayList<>(converter.getEntitySpace().allWays.values()), true);
                    LineComparison.ComparisonOptions options = new LineComparison.ComparisonOptions();
                    options.maxSegmentAngle = 10.0;
                    options.maxSegmentDistance = 1.0;
                    comparison.matchLines(options, relation);

                    List<OSMEntity> conflictingEntities = new ArrayList<>(16);
                    converter.getEntitySpace().addEntity(relation, OSMEntitySpace.EntityMergeStrategy.mergeTags, conflictingEntities);
                    converter.getEntitySpace().outputXml("newresult" + routePath.osm_id + ".osm");


                    relationSpace.addEntity(relation, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);

                    OSMEntitySpace segmentSpace = new OSMEntitySpace(65536);
                    for(LineSegment segment : comparison.mainWaySegments.segments) {
                        segmentSpace.addEntity(segment.segmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                        /*for(WaySegments otherSegments : comparison.allCandidateSegments.values()) {
                            for(LineSegment otherSegment : otherSegments.segments) {
                                segmentSpace.addEntity(otherSegment.segmentWay, OSMEntitySpace.EntityMergeStrategy.dontMerge, null);
                            }
                        }*/
                    }
                    segmentSpace.outputXml("segments" + routePath.osm_id + ".osm");
                    //break;
                }
            }

            relationSpace.outputXml("relation.osm");
            //mainSpace.outputXml("newresult.osm");
        } catch (IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        }

/*
        try {
            converter.getEntitySpace().outputXml("result.osm");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InvalidArgumentException e) {
            e.printStackTrace();
        }//*/
    }
}
