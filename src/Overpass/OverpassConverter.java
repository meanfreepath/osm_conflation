package Overpass;

import OSM.*;
import com.sun.javaws.exceptions.InvalidArgumentException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;

/**
 * Created by nick on 11/5/15.
 */
public class OverpassConverter {
    private final static String NODE_QUERY_FORMAT = "(node%s(%.04f,%.04f,%.04f,%.04f));";
    private final static String WAY_QUERY_FORMAT = "(way%s(%.04f,%.04f,%.04f,%.04f);>;<);";
    private final static String ALL_QUERY_FORMAT = "(node%s(%.04f,%.04f,%.04f,%.04f);way%s(%.04f,%.04f,%.04f,%.04f);relation%s(%.04f,%.04f,%.04f,%.04f););(._;>;);";
    private OSMEntitySpace entitySpace;

    public OSMEntitySpace getEntitySpace() {
        return entitySpace;
    }
    public String queryForBoundingBox(final String tagQuery, final Region boundingBox, final double boundingBoxPadding, final OSMEntity.OSMType entityType) {
        final Region expandedBoundingBox = boundingBox.regionInset(-boundingBoxPadding, -boundingBoxPadding);
        switch (entityType) {
            case node:
                return String.format(NODE_QUERY_FORMAT, tagQuery, expandedBoundingBox.origin.latitude, expandedBoundingBox.origin.longitude, expandedBoundingBox.extent.latitude, expandedBoundingBox.extent.longitude);
            case way:
                return String.format(WAY_QUERY_FORMAT, tagQuery, expandedBoundingBox.origin.latitude, expandedBoundingBox.origin.longitude, expandedBoundingBox.extent.latitude, expandedBoundingBox.extent.longitude);
            case relation:
                return null;
            default:
                return String.format(ALL_QUERY_FORMAT, tagQuery, expandedBoundingBox.origin.latitude, expandedBoundingBox.origin.longitude, expandedBoundingBox.extent.latitude, expandedBoundingBox.extent.longitude, tagQuery, expandedBoundingBox.origin.latitude, expandedBoundingBox.origin.longitude, expandedBoundingBox.extent.latitude, expandedBoundingBox.extent.longitude, tagQuery, expandedBoundingBox.origin.latitude, expandedBoundingBox.origin.longitude, expandedBoundingBox.extent.latitude, expandedBoundingBox.extent.longitude);
        }
    }
    public void fetchFromOverpass(final String query) throws InvalidArgumentException {
        HashMap<String, String> apiConfig = new HashMap<>(1);
        apiConfig.put("debug", "1");
        final ApiClient overpassClient = new ApiClient(null, apiConfig);
        try {

            final JSONArray elements = overpassClient.get(query, false);
            final int elementLength = elements.length();

            //init the entity space to contain the fetched OSM data
            entitySpace = new OSMEntitySpace(elementLength);

            //transmute the JSON data into OSM objects
            final String keyTags = "tags", keyId = "id", keyVersion = "version";
            JSONObject curElement;
            for(int elemIdx=0;elemIdx<elementLength;elemIdx++) {
                curElement = elements.getJSONObject(elemIdx);
                final String elementType = curElement.getString("type");
                if(elementType.equals(OSMEntity.OSMType.node.name())) {
                    final OSMNode node = new OSMNode(curElement.getLong(keyId));
                    node.setComplete(true);
                    node.setCoordinate(curElement.getDouble("lat"), curElement.getDouble("lon"));

                    if(curElement.has(keyTags)) {
                        addTags(curElement, node);
                    }
                    if(curElement.has(keyVersion)) {
                        addMetadata(curElement,node);
                    }
                    entitySpace.addEntity(node, OSMEntity.TagMergeStrategy.keepTags, null);
                } else if(elementType.equals(OSMEntity.OSMType.way.name())) {
                    OSMWay way = new OSMWay(curElement.getLong(keyId));
                    way.setComplete(true);
                    if(curElement.has(keyTags)) {
                        addTags(curElement, way);
                    }
                    if(curElement.has(keyVersion)) {
                        addMetadata(curElement, way);
                    }
                    way = (OSMWay) entitySpace.addEntity(way, OSMEntity.TagMergeStrategy.keepTags, null);
                    JSONArray wayNodes = curElement.getJSONArray("nodes");
                    OSMNode curNode;
                    for(int nodeIdx=0;nodeIdx<wayNodes.length();nodeIdx++) {
                        long nodeId = wayNodes.getLong(nodeIdx);
                        curNode = entitySpace.allNodes.get(nodeId);
                        if(curNode == null) { //create an incomplete (undownloaded) node if not found
                            curNode = entitySpace.addIncompleteNode(nodeId);
                        }
                        way.appendNode(curNode);
                    }
                } else if(elementType.equals(OSMEntity.OSMType.relation.name())) {
                    OSMRelation relation = new OSMRelation(curElement.getLong(keyId));
                    relation.setComplete(true);
                    if(curElement.has(keyTags)) {
                        addTags(curElement, relation);
                    }
                    if(curElement.has(keyVersion)) {
                        addMetadata(curElement, relation);
                    }
                    relation = (OSMRelation) entitySpace.addEntity(relation, OSMEntity.TagMergeStrategy.keepTags, null);
                    if(curElement.has("members")) { //NOTE: relations are last in the Overpass
                        JSONArray members = curElement.getJSONArray("members");
                        for(int memberIdx=0;memberIdx<members.length();memberIdx++) {
                            JSONObject curMember = members.getJSONObject(memberIdx);
                            final long memberId = curMember.getLong("ref");
                            final String memberRole = curMember.getString("role");
                            final String memberType = curMember.getString("type");

                            //check if the member exists
                            OSMEntity memberEntity = null;
                            switch(memberType) {
                                case "node":
                                    memberEntity = entitySpace.allNodes.get(memberId);
                                    if(memberEntity == null) {
                                        memberEntity = entitySpace.addIncompleteNode(memberId);
                                    }
                                    break;
                                case "way":
                                    memberEntity = entitySpace.allWays.get(memberId);
                                    if(memberEntity == null) {
                                        memberEntity = entitySpace.addIncompleteWay(memberId);
                                    }
                                    break;
                                case "relation":
                                    memberEntity = entitySpace.allRelations.get(memberId);
                                    if(memberEntity == null) {
                                        memberEntity = entitySpace.addIncompleteRelation(memberId);
                                    }
                                    break;
                            }
                            if(memberEntity != null) {
                                relation.addMember(memberEntity, memberRole);
                            }
                        }
                    }
                }
            }
        } catch (Exceptions.UnknownOverpassError unknownOverpassError) {
            unknownOverpassError.printStackTrace();
        }
    }
    private static void addTags(JSONObject element, OSMEntity entity) {
        JSONObject tags = element.getJSONObject("tags");
        Iterator<String> iterator = tags.keys();
        while (iterator.hasNext()) {
            final String key = iterator.next();
            entity.setTag(key, tags.getString(key));
        }
    }
    private static void addMetadata(JSONObject element, OSMEntity entity) {
        entity.uid = element.getInt("uid");
        entity.user = element.getString("user");
        entity.changeset = element.getInt("changeset");
        entity.version = element.getInt("version");
        entity.timestamp = element.getString("timestamp");
    }
}
