package OSM;

import java.util.*;

/**
 * Created by nick on 10/15/15.
 */
public class OSMWay extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <way id=\"%d\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <way id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <way id=\"%d\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <way id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\">\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </way>\n",
            BASE_XML_TAG_FORMAT_MEMBER_NODE = "  <nd ref=\"%d\"/>\n";
    private final static OSMType type = OSMType.way;
    private final static int INITIAL_CAPACITY_NODE = 32;

    private final List<OSMNode> nodes = new ArrayList<>(INITIAL_CAPACITY_NODE);
    private OSMNode firstNode = null, lastNode = null;

    public static OSMWay create() {
        return new OSMWay(acquire_new_id());
    }
    public OSMWay(long id) {
        super(id);
    }

    /**
     * Inserts a node at the given index
     * @param node
     * @param index
     */
    public void insertNode(final OSMNode node, final int index) {
        if(index == 0) {
            firstNode = node;
        }
        if(index == nodes.size() - 1) {
            lastNode = node;
        }
        nodes.add(index, node);

        boundingBox = null; //invalidate the bounding box
    }
    /**
     * Appends a node to the end of the way
     * @param node
     */
    public void appendNode(final OSMNode node) {
        if(nodes.size() == 0) {
            firstNode = node;
        }
        lastNode = node;
        nodes.add(node);
        boundingBox = null; //invalidate the bounding box
    }
    public List<OSMNode> getNodes() {
        return nodes;
    }
    public OSMNode getFirstNode() {
        return firstNode;
    }
    public OSMNode getLastNode() {
        return lastNode;
    }

    public void reverseNodes() {
        final OSMNode lastLastNode = lastNode;
        Collections.reverse(nodes);
        firstNode = lastNode;
        lastNode = lastLastNode;
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

        if(boundingBox != null) {
            return boundingBox;
        }

        Region node0BoundingBox = nodes.get(0).getBoundingBox();
        Region boundingBox = new Region(node0BoundingBox.origin, node0BoundingBox.extent);
        for(OSMNode node: nodes) {
            boundingBox.combinedBoxWithRegion(node.getBoundingBox());
        }
        return boundingBox;
    }

    @Override
    public Point getCentroid() {
        Point[] wayPoints = new Point[nodes.size()];
        int i = 0;
        for(OSMNode node: nodes) {
            wayPoints[i++] = new Point(node.getLat(), node.getLon());
        }
        return Region.computeCentroid(wayPoints);
    }

    @Override
    public String toString() {
        int tagCount = tags != null ? tags.size() : 0, nodeCount = nodes.size();
        if(tagCount + nodeCount > 0) {
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user));
            } else {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id, String.valueOf(visible));
            }
            final StringBuilder xml = new StringBuilder(tagCount * 64 + nodeCount * 24 + openTag.length() + BASE_XML_TAG_FORMAT_CLOSE.length());
            xml.append(openTag);

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
            if(version > 0) {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user));
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, String.valueOf(visible));
            }
        }
    }
}
