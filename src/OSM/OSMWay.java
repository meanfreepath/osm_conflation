package OSM;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 10/15/15.
 */
public class OSMWay extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <way id=\"%d\" version=\"1\"/>\n",// user=\"$osmuser\" uid=\"$osmid\" visible=\"true\" version=\"1\">\n"];
            BASE_XML_TAG_FORMAT_OPEN = " <way id=\"%d\" version=\"1\">\n"/* user=\"$osmuser\" uid=\"$osmid\" visible=\"true\" version=\"1\">\n"];*/,
            BASE_XML_TAG_FORMAT_CLOSE = " </way>\n",
            BASE_XML_TAG_FORMAT_MEMBER_NODE = "  <nd ref=\"%d\"/>\n";
    private final static OSMType type = OSMType.way;
    private final static int INITIAL_CAPACITY_NODE = 32;

    public final List<OSMNode> nodes = new ArrayList<>(INITIAL_CAPACITY_NODE);

    public static OSMWay create() {
        return new OSMWay(acquire_new_id());
    }
    public OSMWay(long id) {
        osm_id = id;
    }


    public void addNode(OSMNode node) {
        nodes.add(node);
    }

    public void reverseNodes() {
        Collections.reverse(nodes);
    }

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        if(nodes.size() == 0) {
            return null;
        }

        Region node0BoundingBox = nodes.get(0).getBoundingBox();
        Region combinedBoundingBox = new Region(node0BoundingBox.origin, node0BoundingBox.extent);
        for(OSMNode node: nodes) {
            combinedBoundingBox.combinedBoxWithRegion(node.getBoundingBox());
        }
        return combinedBoundingBox;
    }

    @Override
    public Point getCentroid() {
        Point[] wayPoints = new Point[nodes.size()];
        int i = 0;
        for(OSMNode node: nodes) {
            wayPoints[i++] = new Point(node.lat, node.lon);
        }
        return Region.computeCentroid(wayPoints);
    }

    @Override
    public String toString() {
        int tagCount = tags != null ? tags.size() : 0, nodeCount = nodes.size();
        if(tagCount + nodeCount > 0) {
            StringBuilder xml = new StringBuilder(tagCount * 16 + nodeCount * 24);
            xml.append(String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id));

            //add the way's nodes
            for (OSMNode node : nodes) {
                xml.append(String.format(BASE_XML_TAG_FORMAT_MEMBER_NODE, node.osm_id));
            }

            //and the way's tags
            if(tagCount > 0) {
                for (HashMap.Entry<String, String> entry : tags.entrySet()) {
                    xml.append(String.format(BASE_XML_TAG_FORMAT_TAG, escapeForXML(entry.getKey()), escapeForXML(entry.getValue())));
                }
            }
            xml.append(BASE_XML_TAG_FORMAT_CLOSE);
            return xml.toString();
        } else {
            return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id);
        }
    }
}
