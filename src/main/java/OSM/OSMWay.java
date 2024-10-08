package OSM;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

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

    private @NotNull final List<OSMNode> nodes;
    private @Nullable OSMNode firstNode = null, lastNode = null;

    public OSMWay(final long id) {
        super(id);
        nodes = new ArrayList<>(INITIAL_CAPACITY_NODE);
    }

    /**
     * Copy constructor
     * @param wayToCopy
     * @param idOverride
     */
    protected OSMWay(final @NotNull OSMWay wayToCopy, final @Nullable Long idOverride) {
        super(wayToCopy, idOverride);

        nodes = new ArrayList<>(wayToCopy.nodes.size());
        //copyNodes(wayToCopy.nodes); //note: this is handled in the entity space

    }
    @Override
    protected void downgradeToIncompleteEntity() {
        super.downgradeToIncompleteEntity();
        //now remove all nodes
        flushNodes();
    }
    private void flushNodes() {
        final ListIterator<OSMNode> nodeListIterator = nodes.listIterator();
        while(nodeListIterator.hasNext()) {
            final OSMNode node = nodeListIterator.next();
            nodeListIterator.remove();
            node.didRemoveFromEntity(this, false);
        }
        firstNode = lastNode = null;

        boundingBox = null; //invalidate the bounding box
    }

    /**
     * Adds the given nodes to our way
     * @param nodesToCopy the nodes to copy in
     */
    public void setNodes(final @NotNull List<OSMNode> nodesToCopy) {
        //add the nodes if complete
        if(complete != CompletionStatus.incomplete) {
            final List<OSMNode> oldNodes = new ArrayList<>(nodes);
            nodes.clear();
            nodes.addAll(nodesToCopy);

            //notify any added or removed nodes
            for(final OSMNode newNode : nodesToCopy) {
                if(!oldNodes.contains(newNode)) {
                    newNode.didAddToEntity(this);
                }
            }
            for(final OSMNode oldNode : oldNodes) {
                if(!nodes.contains(oldNode)) {
                    oldNode.didRemoveFromEntity(this, false);
                }
            }

            updateFirstAndLastNodes();
            boundingBox = null; //invalidate the bounding box
            markAsModified();
            updateCompletionStatus();
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
     * @param node the OSMNode to insert
     * @param index the index to insert it at
     * @throws IndexOutOfBoundsException if index isn't in bounds
     */
    public void insertNode(final @NotNull OSMNode node, final int index) {
        nodes.add(index, node);
        node.didAddToEntity(this);
        updateFirstAndLastNodes();
        boundingBox = null; //invalidate the bounding box

        markAsModified();
        updateCompletionStatus();
    }
    /**
     * Appends a node to the end of the way
     * @param node the node to append
     */
    public void appendNode(final @NotNull OSMNode node) {
        nodes.add(node);
        node.didAddToEntity(this);
        updateFirstAndLastNodes();
        boundingBox = null; //invalidate the bounding box
        markAsModified();
        updateCompletionStatus();
    }
    /**
     * Removes the node at the given index
     * @param nodeIndex the index of the node to remove
     * @return TRUE if the node was found and replaced
     */
    public boolean removeNodeAtIndex(final int nodeIndex) {
        return replaceNodeAtIndex(nodeIndex, null);
    }
    public boolean removeNode(final @NotNull OSMNode node) {
        if(osm_id == 30108041L) {
            System.out.println("\tREMOVE NODE " + node);
        }
        return replaceNode(node, null);
    }
    /**
     * Replace the old node with the new node
     * @param oldNode the old node to replace
     * @param newNode the new node.  If null, then old node will be removed
     * @return TRUE if the node was found and replaced
     */
    public boolean replaceNode(final @NotNull OSMNode oldNode, final @Nullable OSMNode newNode) {
        int nodeIndex = nodes.indexOf(oldNode);
        boolean replaced = replaceNodeAtIndex(nodeIndex, newNode);

        //if this is a closed way, we also need to replace the last node if it's the same as oldNode (previous 2 lines only replaced the first instance)
        if(isClosed() && oldNode == getLastNode()) {
            nodeIndex = nodes.indexOf(oldNode);
            replaceNodeAtIndex(nodeIndex, newNode);
        }
        return replaced;
    }

    /**
     *
     * @param nodeIndex the index of the node to remove
     * @param newNode the new node.  If null, then the node at the index will be removed
     * @return TRUE if the node was found and replaced
     */
    private boolean replaceNodeAtIndex(final int nodeIndex, final @Nullable OSMNode newNode) {
        if(nodeIndex >= 0) {
            final OSMNode oldNode = nodes.get(nodeIndex);
            if(newNode != null) {
                nodes.set(nodeIndex, newNode);
                newNode.didAddToEntity(this);
            } else {
                nodes.remove(nodeIndex);
            }
            oldNode.didRemoveFromEntity(this, false);
            updateFirstAndLastNodes();

            boundingBox = null; //invalidate the bounding box
            markAsModified();
            updateCompletionStatus();
            return true;
        }
        return false;
    }

    /**
     * Checks whether the given node is a member of this way
     * @param node the node to look up
     * @return the index it's at, if present
     */
    public int indexOfNode(final @Nullable OSMNode node) {
        return nodes.indexOf(node);
    }
    @NotNull
    public List<OSMNode> getNodes() {
        return nodes;
    }
    @Nullable
    public OSMNode getFirstNode() {
        return firstNode;
    }
    @Nullable
    public OSMNode getLastNode() {
        return lastNode;
    }
    protected void nodeWasMadeComplete(final @NotNull OSMNode node) {
        updateCompletionStatus();
    }
    private void updateCompletionStatus() {
        complete = areAllNodesComplete() ? CompletionStatus.membersComplete : CompletionStatus.memberList;
    }
    /**
     * Returns the closest node (within the tolerance distance) to the given point
     * @param point the point to test
     * @param tolerance maximum distance, in meters
     * @return the closest node, or null if none within the tolerance distance
     */
    @Nullable
    public OSMNode nearestNodeAtPoint(final @NotNull Point point, final double tolerance) {
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
    /**
     * Gets the number of fully-complete nodes on this way
     * @return the number of completely-downloaded nodes
     */
    public int getCompletedNodeCount() {
        int completedNodeCount = 0;
        for(final OSMNode node : nodes) {
            if(node.complete != CompletionStatus.incomplete) {
                completedNodeCount++;
            }
        }
        return completedNodeCount;
    }
    /**
     * Whether all the nodes on this way are fully downloaded
     * @return TRUE if all nodes are fully downloaded, or no nodes present, FALSE if this way is incomplete or 1 or more nodes is incomplete
     */
    public boolean areAllNodesComplete() {
        return complete.compareTo(CompletionStatus.memberList) >= 0 && nodes.size() == getCompletedNodeCount();
    }

    /**
     * Returns a rough total length of the way, in meters.
     * @return the length
     */
    public double length() {
        OSMNode lastNode = null;
        double length = 0.0;
        for(final OSMNode curNode : nodes) {
            if(lastNode != null) {
                length += Point.distance(lastNode.getCentroid(), curNode.getCentroid());
            }
            lastNode = curNode;
        }
        return length;
    }

    @NotNull
    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    @Nullable
    public Region getBoundingBox() {
        if(nodes.size() == 0) {
            return null;
        }

        if(boundingBox != null) {
            return boundingBox;
        }

        Region boundingBox = null;
        for(final OSMNode node: nodes) {
            if(node.complete != CompletionStatus.incomplete) {
                if(boundingBox != null) {
                    boundingBox.combinedBoxWithRegion(node.getBoundingBox());
                } else {
                    Region nodeBoundingBox = node.getBoundingBox();
                    if(nodeBoundingBox != null) {
                        boundingBox = new Region(nodeBoundingBox);
                    }
                }
            }
        }
        return boundingBox;
    }

    @Override
    public Point getCentroid() {
        final int vertexCount = isClosed() ? nodes.size() - 1 : nodes.size(); //don't include the last node in closed ways
        final Point[] vertices = new Point[vertexCount];
        int i = 0;
        for(final OSMNode node: nodes) {
            vertices[i++] = node.getCentroid();
            if(i == vertexCount) {
                break;
            }
        }
        return Region.computeCentroid2(vertices);
    }

    @NotNull
    @Override
    public String toOSMXML() {
        if(debugEnabled) {
            setTag("rcount", Short.toString(getContainingRelationCount()));
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

    @Override
    public void didAddToEntity(@NotNull OSMEntity entity) {
        if(entity instanceof OSMRelation) {
            addContainingRelation((OSMRelation) entity);
        }
    }
    @Override
    public void didRemoveFromEntity(@NotNull OSMEntity entity, boolean entityWasDeleted) {
        if(entity instanceof OSMRelation) {
            removeContainingRelation((OSMRelation) entity);
        }
    }
    @Override
    public void containedEntityWasDeleted(@NotNull OSMEntity entity) {
        final OSMNode containedNode = (OSMNode) entity;
        removeNode(containedNode);
    }
    @Override
    public boolean didDelete(@NotNull OSMEntitySpace fromSpace) {
        final List<OSMNode> containedNodes = new ArrayList<>(nodes);
        for(final OSMNode containedNode : containedNodes) {
            removeNode(containedNode);
            containedNode.didRemoveFromEntity(this, true);

            //also delete any nodes that are untagged, and aren't a member of any other ways or relations
            if((containedNode.getTags() == null || containedNode.getTags().isEmpty()) &&
                    containedNode.getContainingWays().isEmpty() && containedNode.getContainingRelations().isEmpty()) {
                fromSpace.deleteEntity(containedNode);
            }
        }
        return super.didDelete(fromSpace);
    }

    /**
     * Whether this way is closed (i.e. first node is same as last node)
     * @return
     */
    public boolean isClosed() {
        final int nodeCount = nodes.size();
        if(nodeCount > 1) {
            return nodes.get(0) == nodes.get(nodeCount - 1);
        }
        return false;
    }
    /**
     * Check for nodes that occupy the same position on this way
     * @param tolerance The distance (in meters) to use to identify nodes as being in the same location
     * @return Array of duplicate node pairs
     */
    @Nullable
    public OSMNode[][] identifyDuplicateNodesByPosition(final double tolerance) {
        if(nodes.size() < 2) {
            return null;
        }
        ListIterator<OSMNode> outsideIterator = nodes.listIterator(), insideIterator;
        OSMNode outsideNode, insideNode;
        final List<OSMNode[]> duplicateNodes = new ArrayList<>(nodes.size());
        while (outsideIterator.hasNext()) {
            outsideNode = outsideIterator.next();
            insideIterator = nodes.listIterator(outsideIterator.nextIndex());
            while (insideIterator.hasNext()) {
                insideNode = insideIterator.next();
                if(insideNode != outsideNode && Point.distance(outsideNode.getCentroid(), insideNode.getCentroid()) <= tolerance) {
                    final OSMNode[] nodePair = {outsideNode, insideNode};
                    duplicateNodes.add(nodePair);
                }
            }
        }
        return duplicateNodes.toArray(new OSMNode[duplicateNodes.size()][]);
    }
    @Override
    public String toString() {
        if(complete != CompletionStatus.incomplete) {
            final List<String> nodeIds = new ArrayList<>(nodes.size());
            for(final OSMNode node : nodes) {
                nodeIds.add(node.osm_id + (node.complete != CompletionStatus.incomplete ? "" : "*"));
            }
            return String.format("way@%d (id %d): %d/%d nodes [%s] (%s) [%s/%s]", hashCode(), osm_id, getCompletedNodeCount(), nodes.size(), String.join(",", nodeIds), getTag(OSMEntity.KEY_NAME), complete, action.toString().toUpperCase());
        } else {
            return String.format("way@%d (id %d): incomplete", hashCode(), osm_id);
        }
    }
}
