package OSM;

import com.sun.istack.internal.NotNull;

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
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <way id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"%s/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <way id=\"%d\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <way id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"%s>\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </way>\n",
            BASE_XML_TAG_FORMAT_MEMBER_NODE = "  <nd ref=\"%d\"/>\n";
    private final static OSMType type = OSMType.way;
    private final static int INITIAL_CAPACITY_NODE = 32;

    private final List<OSMNode> nodes = new ArrayList<>(INITIAL_CAPACITY_NODE);
    private OSMNode firstNode = null, lastNode = null;
    private int completedNodeCount = 0;

    public OSMWay(final long id) {
        super(id);
    }

    /**
     * Copy constructor
     * @param wayToCopy
     */
    public OSMWay(final OSMWay wayToCopy, final Long idOverride) {
        super(wayToCopy, idOverride);
    }
    @Override
    protected void downgradeToIncompleteEntity() {
        super.downgradeToIncompleteEntity();
        final List<OSMNode> nodesToRemove = new ArrayList<>(nodes);
        for(final OSMNode node : nodesToRemove) {
            removeNode(node);
        }
        completedNodeCount = 0;
    }

    /**
     * Adds the given nodes to our way
     * @param nodesToCopy
     */
    protected void copyNodes(final List<OSMNode> nodesToCopy) {
        //add the nodes if complete
        if(complete) {
            nodes.addAll(nodesToCopy);
            for (final OSMNode addedNode : nodes) {
                addedNode.didAddToWay(this);
                if(addedNode.isComplete()) {
                    completedNodeCount++;
                }
            }
            updateFirstAndLastNodes();
        }
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
        if(node.isComplete()) {
            completedNodeCount++;
        }
        node.didAddToWay(this);
        updateFirstAndLastNodes();
        boundingBox = null; //invalidate the bounding box

        markAsModified();
    }
    /**
     * Appends a node to the end of the way
     * @param node
     */
    public void appendNode(final OSMNode node) {
        nodes.add(node);
        if(node.isComplete()) {
            completedNodeCount++;
        }
        node.didAddToWay(this);
        updateFirstAndLastNodes();
        boundingBox = null; //invalidate the bounding box
        markAsModified();
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
    public boolean replaceNode(final OSMNode oldNode, @NotNull final OSMNode newNode) {
        final int nodeIndex = nodes.indexOf(oldNode);
        if(nodeIndex >= 0) {
            if(newNode != null) {
                nodes.set(nodeIndex, newNode);
                if(newNode.isComplete()) {
                    completedNodeCount++;
                }
                newNode.didAddToWay(this);
            } else {
                nodes.remove(nodeIndex);
                if(oldNode.isComplete()) {
                    completedNodeCount--;
                }
            }
            oldNode.didRemoveFromWay(this);
            updateFirstAndLastNodes();

            boundingBox = null; //invalidate the bounding box
            markAsModified();
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
    protected void nodeWasMadeComplete(final OSMNode node) {
        completedNodeCount++;
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
        markAsModified();

        //TODO: check relations, tags that need to be modified to reflect the change
    }
    public boolean areAllNodesComplete() {
        return nodes.size() == completedNodeCount;
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

        Region boundingBox = null;
        for(final OSMNode node: nodes) {
            if(node.isComplete()) {
                if(boundingBox != null) {
                    boundingBox.combinedBoxWithRegion(node.getBoundingBox());
                } else {
                    boundingBox = node.getBoundingBox();
                }
            }
        }
        return boundingBox;
    }

    @Override
    public Point getCentroid() {
        Point[] wayPoints = new Point[nodes.size()];
        int i = 0;
        for(final OSMNode node: nodes) {
            wayPoints[i++] = new Point(node.getLat(), node.getLon());
        }
        return Region.computeCentroid(wayPoints);
    }

    @Override
    public String toOSMXML() {
        if(debugEnabled) {
            setTag("rcount", Short.toString(containingRelationCount));
            if(osm_id < 0) {
                setTag("origid", Long.toString(osm_id));
            }
        }

        int tagCount = tags != null ? tags.size() : 0, nodeCount = nodes.size();
        if(tagCount + nodeCount > 0) {
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), actionTagAttribute(action));
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
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), actionTagAttribute(action));
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, String.valueOf(visible));
            }
        }
    }
    public String toString() {
        final List<String> nodeIds = new ArrayList<>(nodes.size());
        for(final OSMNode node : nodes) {
            nodeIds.add(Long.toString(node.osm_id));
        }
        return String.format("way@%d (id %d): %d/%d nodes [%s] (%s)", hashCode(), osm_id, completedNodeCount, nodes.size(), String.join(",", nodeIds), complete ? getTag(OSMEntity.KEY_NAME) : "incomplete");
    }
}
