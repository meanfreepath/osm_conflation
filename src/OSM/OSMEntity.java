package OSM;

import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.FileWriter;
import java.io.IOException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 10/15/15.
 */
public abstract class OSMEntity {
    public final static String KEY_LATITUDE = "lat", KEY_LONGITUDE = "lon", KEY_OSMID = "osm_id", KEY_FROM = "from", KEY_VIA = "via", KEY_TO = "to", KEY_OPERATOR = "operator", KEY_ROUTE = "route", KEY_ROUTE_MASTER = "route_master", KEY_NAME = "name", KEY_REF = "ref", KEY_LOCAL_REF = "local_ref", KEY_DESCRIPTION = "description", KEY_WEBSITE = "website", KEY_TYPE = "type", KEY_PUBLIC_TRANSPORT_VERSION = "public_transport:version", KEY_COLOUR = "colour", KEY_AMENITY = "amenity", KEY_WHEELCHAIR = "wheelchair";
    public final static String TAG_ROUTE = "route", TAG_ROUTE_MASTER = "route_master", TAG_BUS = "bus", TAG_LIGHT_RAIL = "light_rail", TAG_TRAM = "tram", TAG_SUBWAY = "subway", TAG_TRAIN = "train", TAG_FERRY = "ferry", TAG_AERIALWAY = "aerialway", TAG_YES = "yes", TAG_NO = "no";

    protected final static String
            XML_DOCUMENT_OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" upload=\"false\" generator=\"KCMetroImporter\">\n",
            XML_BOUNDING_BOX = "<bounds minlat=\"%.07f\" minlon=\"%.07f\" maxlat=\"%.07f\" maxlon=\"%.07f\"/>\n",
            XML_DOCUMENT_CLOSE = "</osm>\n";

    public enum OSMType {
        node, way, relation
    }

    protected final static String BASE_XML_TAG_FORMAT_TAG = "  <tag k=\"%s\" v=\"%s\"/>\n";
    protected static long new_id_sequence = 0;
    public long osm_id;
    protected HashMap<String,String> tags;

    protected static long acquire_new_id() {
        return --new_id_sequence;
    }
    public abstract OSMType getType();
    public abstract Region getBoundingBox();
    public abstract Point getCentroid();

    public abstract String toString();
    public void addTag(String name, String value) throws InvalidArgumentException {
        if(tags == null) {
            tags = new HashMap<>();
        }

        if(tags.containsKey(name)) {
            String[] msg = {"Tag \"" + name + "\" already set!"};
            throw new InvalidArgumentException(msg);
        }
        tags.put(name, value.trim());
    }
    public void setTag(String name, String value) {
        if(tags == null) {
            tags = new HashMap<>();
        }
        tags.put(name, value.trim());
    }
    public final String getTag(String name) {
        if(tags == null) {
            return null;
        }
        return tags.get(name);
    }
    public static String escapeForXML(String str){
        final StringBuilder result = new StringBuilder();
        final StringCharacterIterator iterator = new StringCharacterIterator(str);
        char character =  iterator.current();
        while (character != CharacterIterator.DONE ){
            if (character == '<') {
                result.append("&lt;");
            }
            else if (character == '>') {
                result.append("&gt;");
            }
            else if (character == '\"') {
                result.append("&quot;");
            }
            else if (character == '\'') {
                result.append("&#039;");
            }
            else if (character == '&') {
                result.append("&amp;");
            }
            else {
                //the char is not a special one
                //add it to the result as is
                result.append(character);
            }
            character = iterator.next();
        }
        return result.toString();
    }

    private static void processChildren(final List<? extends OSMEntity> entities, final HashMap<Long, OSMNode> allNodes, final HashMap<Long, OSMWay> allWays, final HashMap<Long, OSMRelation> allRelations) throws InvalidArgumentException {
        OSMWay curWay;
        OSMRelation curRelation;
        for(OSMEntity entity:entities) {
            if(entity instanceof OSMNode) {
                allNodes.put(entity.osm_id, (OSMNode) entity);
            } else if(entity instanceof OSMWay) {
                curWay = (OSMWay) entity;
                allWays.put(entity.osm_id, curWay);
                processChildren(curWay.nodes, allNodes, allWays, allRelations);
            } else if(entity instanceof OSMRelation) {
                curRelation = (OSMRelation) entity;
                allRelations.put(curRelation.osm_id, curRelation);
                List<OSMEntity> relationMembers = new ArrayList<>(curRelation.members.size());
                for(OSMRelation.OSMRelationMember member: curRelation.members) {
                    relationMembers.add(member.member);
                }
                processChildren(relationMembers, allNodes, allWays, allRelations);
            } else {
                String[] errMsg = {"Entity " + entity.osm_id + " is not a Node, Way, or Relation subclass!"};
                throw new InvalidArgumentException(errMsg);
            }
        }
    }

    public static void outputXml(List<? extends OSMEntity> entities, String fileName) throws IOException, InvalidArgumentException {
        //produce an empty XMl file if no entities
        if(entities.size() == 0) {
            FileWriter writer = new FileWriter(fileName);
            writer.write(XML_DOCUMENT_OPEN);
            writer.write(XML_DOCUMENT_CLOSE);
            writer.close();
            return;
        }

        //first compile the list of nodes, ways, and relations, so they can be added
        final HashMap<Long, OSMNode> allNodes = new HashMap<>(entities.size());
        final HashMap<Long, OSMWay> allWays = new HashMap<>(entities.size());
        final HashMap<Long, OSMRelation> allRelations = new HashMap<>(entities.size());

        //generate the database of all nodes, ways, and relations in the entities (and their children)
        processChildren(entities, allNodes, allWays, allRelations);

        //generate the bounding box for the file
        Region fileBoundingBox = entities.get(0).getBoundingBox();
        for (OSMEntity entity : entities) {
            fileBoundingBox.combinedBoxWithRegion(entity.getBoundingBox());
        }

        FileWriter writer = new FileWriter(fileName);
        writer.write(XML_DOCUMENT_OPEN);
        writer.write(String.format(XML_BOUNDING_BOX, fileBoundingBox.origin.latitude, fileBoundingBox.origin.longitude, fileBoundingBox.extent.latitude, fileBoundingBox.extent.longitude));
        for(OSMNode node: allNodes.values()) {
            writer.write(node.toString());
        }
        for(OSMWay way: allWays.values()) {
            writer.write(way.toString());
        }
        for(OSMRelation relation: allRelations.values()) {
            writer.write(relation.toString());
        }
        writer.write(XML_DOCUMENT_CLOSE);

        writer.close();
    }
}
