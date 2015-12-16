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
            BASE_XML_TAG_FORMAT_EMPTY = " <way id=\"%d\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <way id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\" action=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <way id=\"%d\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <way id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\" action=\"%s\">\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </way>\n",
            BASE_XML_TAG_FORMAT_MEMBER_NODE = "  <nd ref=\"%d\"/>\n";
    private final static OSMType type = OSMType.way;
    private final static int INITIAL_CAPACITY_NODE = 32;

    private final List<OSMNode> nodes = new ArrayList<>(INITIAL_CAPACITY_NODE);
    private OSMNode firstNode = null, lastNode = null;

    public OSMWay(final long id) {
        super(id);
    }

    /**
     * Copy constructor
     * @param wayToCopy
     * @param nodeCopyStrategy
     */
    public OSMWay(final OSMWay wayToCopy, final Long idOverride, final MemberCopyStrategy nodeCopyStrategy) {
        super(wayToCopy, idOverride);

        //add the nodes
        switch (nodeCopyStrategy) {
            case deep: //if deep copying, individually copy the nodes as well
                for(final OSMNode node : wayToCopy.nodes) {
                    appendNode(new OSMNode(node, null));
                }
                break;
            case shallow:
                nodes.addAll(wayToCopy.nodes);
                for(final OSMNode addedNode : nodes) {
                    addedNode.didAddToWay(this);
                }
                break;
            case none:
                break;
        }

        updateFirstAndLastNodes();
    }
    private void updateFirstAndLastNodes() {
        if(nodes.size() > 0) {
            firstNode = nodes.get(0);
            lastNode = nodes.get(nodes.size() - 1);
        }
    }

    /**
     * Inserts a node at the given index
     * @param node
     * @param index
     */
    public void insertNode(final OSMNode node, final int index) {
        nodes.add(index, node);
        node.didAddToWay(this);
        updateFirstAndLastNodes();

        boundingBox = null; //invalidate the bounding box
    }
    /**
     * Appends a node to the end of the way
     * @param node
     */
    public void appendNode(final OSMNode node) {
        nodes.add(node);
        node.didAddToWay(this);
        updateFirstAndLastNodes();
        boundingBox = null; //invalidate the bounding box
    }
    public boolean removeNode(final OSMNode node) {
        return replaceNode(node, null);
    }
    /**
     * Replace the old node with the new node
     * @param oldNode
     * @param newNode
     * @return TRUE if the node was found and replaced
     */
    public boolean replaceNode(final OSMNode oldNode, final OSMNode newNode) {
        final int nodeIndex = nodes.indexOf(oldNode);
        if(nodeIndex >= 0) {
            if(newNode != null) {
                nodes.set(nodeIndex, newNode);
                newNode.didAddToWay(this);
            } else {
                nodes.remove(nodeIndex);
            }
            oldNode.didRemoveFromWay(this);
            updateFirstAndLastNodes();
            boundingBox = null; //invalidate the bounding box
            return true;
        }
        return false;
    }

    /**
     * Checks whether the given node is a member of this way
     * @param node
     * @return
     */
    public int indexOfNode(final OSMNode node) {
        return nodes.indexOf(node);
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
    /**
     * Returns the closest node (within the tolerance distance) to the given point
     * @param point the point to test
     * @param tolerance maximum distance, in meters
     * @return the closest node, or null if none within the tolerance distance
     */
    public OSMNode nearestNodeAtPoint(final Point point, final double tolerance) {
        double closestNodeDistance = tolerance, curDistance;
        OSMNode closestNode = null;

        for(final OSMNode existingNode : nodes) {
            curDistance = Point.distance(point, existingNode.getCentroid());
            if(curDistance <= closestNodeDistance) {
                closestNodeDistance = curDistance;
                closestNode = existingNode;
            }
        }
        return closestNode;
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

        final Region node0BoundingBox = firstNode.getBoundingBox();
        final Region boundingBox = new Region(node0BoundingBox.origin, node0BoundingBox.extent);
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
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), action != ChangeAction.none ? action.name() : "");
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
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), action != ChangeAction.none ? action.name() : "");
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, String.valueOf(visible));
            }
        }
    }
}
