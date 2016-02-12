package OSM;

import com.sun.javaws.exceptions.InvalidArgumentException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Container for OSM entities
 * Created by nick on 11/4/15.
 */
public class OSMEntitySpace {
    private final static String
            XML_DOCUMENT_OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" upload=\"false\" generator=\"KCMetroImporter\">\n",
            XML_BOUNDING_BOX = " <bounds minlat=\"%.07f\" minlon=\"%.07f\" maxlat=\"%.07f\" maxlon=\"%.07f\"/>\n",
            XML_DOCUMENT_CLOSE = "</osm>\n";

    /**
     * Id sequence for new OSM entities
     */
    private static long osmIdSequence = 0;

    private final static Comparator<NodeIndexer> nodeIndexComparator = new Comparator<NodeIndexer>() {
        @Override
        public int compare(NodeIndexer o1, NodeIndexer o2) {
            return o1.nodeIndex > o2.nodeIndex ? 1 : -1;
        }
    };
    private class NodeIndexer {
        public final OSMNode node;
        public final int nodeIndex;
        public NodeIndexer(final OSMNode node, final int nodeIndex) {
            this.node = node;
            this.nodeIndex = nodeIndex;
        }
    }

    public final HashMap<Long, OSMEntity> allEntities;
    public final HashMap<Long, OSMNode> allNodes;
    public final HashMap<Long, OSMWay> allWays;
    public final HashMap<Long, OSMRelation> allRelations;
    public String name;

    private ArrayList<Long> debugEntityIds = new ArrayList<>();

    private static void setIdSequence(long sequence) {
        osmIdSequence = sequence;
    }

    /**
     * Create a space with the given initial capacity
     * @param capacity
     */
    public OSMEntitySpace(final int capacity) {
        initDebug();
        allEntities = new HashMap<>(capacity);
        allNodes = new HashMap<>(capacity);
        allWays = new HashMap<>(capacity);
        allRelations = new HashMap<>(capacity);

        name = String.valueOf(Math.round(1000 * Math.random()));
    }
    public OSMEntitySpace(final OSMEntitySpace spaceToDuplicate, final int additionalCapacity) {
        initDebug();
        name = spaceToDuplicate.name;

        final int capacity = spaceToDuplicate.allEntities.size() + additionalCapacity;
        allEntities = new HashMap<>(capacity);
        allNodes = new HashMap<>(capacity);
        allWays = new HashMap<>(capacity);
        allRelations = new HashMap<>(capacity);
        mergeWithSpace(spaceToDuplicate, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    private void initDebug() {
        debugEntityIds.add(368829104L);
        debugEntityIds.add(2706253436L);
    }

    /**
     * Create a new Node in this space
     * @param latitude
     * @param longitude
     * @param withTags tags to add to the node, if any
     * @return the new node
     */
    public OSMNode createNode(final double latitude, final double longitude, final Map<String, String> withTags) {
        final OSMNode newNode = new OSMNode(--osmIdSequence);
        newNode.setCoordinate(latitude, longitude);
        newNode.setComplete(true);

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newNode.setTag(tag.getKey(), tag.getValue());
            }
        }
        if(debugEntityIds.contains(newNode.osm_id)) {
            System.out.println(name + " CREATE COMPLETE NODE " + newNode);
        }
        //newNode.setTag("origid", Long.toString(newNode.osm_id));
        return (OSMNode) addEntity(newNode, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    /**
     * Create an incomplete (i.e. not fully-downloaded) node in this space
     * @param osm_id
     * @return
     */
    public OSMNode createIncompleteNode(final long osm_id) {
        final OSMNode newNode = new OSMNode(osm_id);
        if(debugEntityIds.contains(newNode.osm_id)) {
            System.out.println(name + " CREATE **INCOMPLETE** NODE " + newNode);
        }
        return (OSMNode) addEntity(newNode, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    /**
     * Create a new Way in this space
     * @param withTags tags to add to the way, if any
     * @param withNodes nodes to add to the way, if any
     * @return the new way
     */
    public OSMWay createWay(final Map<String, String> withTags, final List<OSMNode> withNodes) {
        final OSMWay newWay = new OSMWay(--osmIdSequence);
        newWay.setComplete(true);
        if(debugEntityIds.contains(newWay.osm_id)) {
            System.out.println(name + " CREATE COMPLETE WAY" + newWay);
        }

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newWay.setTag(tag.getKey(), tag.getValue());
            }
        }
        if(withNodes != null) {
            for(final OSMNode node : withNodes) {
                newWay.appendNode(node);
            }
        }
        //newWay.setTag("origid", Long.toString(newWay.osm_id));
        return (OSMWay) addEntity(newWay, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    /**
     * Create an incomplete (i.e. not fully-downloaded) way in this space
     * @param osm_id
     * @return
     */
    public OSMWay createIncompleteWay(final long osm_id) {
        final OSMWay newWay = new OSMWay(osm_id);
        if(debugEntityIds.contains(newWay.osm_id)) {
            System.out.println(name + " CREATE **INCOMPLETE** WAY " + newWay);
        }
        return (OSMWay) addEntity(newWay, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    /**
     * Create a new Relation in this space
     * @param withTags tags to add to the relation, if any
     * @param withMembers members to add to the relation, if any
     * @return the new relation
     */
    public OSMRelation createRelation(final Map<String, String> withTags, final List<OSMRelation.OSMRelationMember> withMembers) {
        final OSMRelation newRelation = new OSMRelation(--osmIdSequence);
        newRelation.setComplete(true);

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newRelation.setTag(tag.getKey(), tag.getValue());
            }
        }
        if(withMembers != null) {
            for(final OSMRelation.OSMRelationMember member: withMembers) {
                newRelation.addMember(member.member, member.role);
            }
        }
        //newRelation.setTag("origid", Long.toString(newRelation.osm_id));
        return (OSMRelation) addEntity(newRelation, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    /**
     * Create an incomplete (i.e. not fully-downloaded) relation in this space
     * @param osm_id
     * @return
     */
    public OSMRelation createIncompleteRelation(final long osm_id) {
        final OSMRelation newRelation = new OSMRelation(osm_id);
        return (OSMRelation) addEntity(newRelation, OSMEntity.TagMergeStrategy.keepTags, null);
    }

    /**
     *
     * @param entity
     * @param existingEntity
     * @param mergeStrategy
     * @param entityList
     * @param conflictingEntities
     * @return TRUE if a new entity should be created, FALSE if it has been merged
     */
    private void handleMerge(final OSMEntity entity, final OSMEntity existingEntity, final OSMEntity.TagMergeStrategy mergeStrategy, final HashMap<Long, ? extends OSMEntity> entityList, List<OSMEntity> conflictingEntities) {
        if(existingEntity.isComplete()) { //existingEntity is complete: just update tags
            switch (mergeStrategy) {
                case keepTags:
                case replaceTags:
                case copyTags:
                case copyNonexistentTags:
                    existingEntity.copyTagsFrom(entity, mergeStrategy);
                    break;
                case mergeTags:
                    final Map<String, String> conflictingTags = existingEntity.copyTagsFrom(entity, mergeStrategy);
                    if (conflictingTags != null) { //add the conflict to the list for processing
                        conflictingEntities.add(existingEntity);
                    }
                    break;
            }
        } else if(entity.isComplete()) { //if existing entity is not complete, we need to "upgrade" it without altering the base object pointer (which would screw up a lot of dependencies)
            existingEntity.upgradeToCompleteEntity(entity, this);
            if(debugEntityIds.contains(entity.osm_id)) {
                System.out.println(name + " UPGRADE ENTITY " + existingEntity);
            }
        }
    }
    /**
     * Add the given OSM entity to the space, as well as any entities it contains
     * @param entity
     * @param mergeStrategy
     * @return
     */
    public OSMEntity addEntity(final OSMEntity entity, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities) {
        if(entity instanceof OSMNode) {
            OSMNode localNode = allNodes.get(entity.osm_id);
            if(localNode == entity) {
                return localNode;
            }

            if(debugEntityIds.contains(entity.osm_id)) {
                System.out.println(name + " CHECK ADD ENTITY " + entity + ", existing? " + localNode);
            }
            if(localNode != null) {
                handleMerge(entity, localNode, mergeStrategy, allNodes, conflictingEntities);
            } else {
                localNode = new OSMNode((OSMNode) entity, null); //create an exact copy of the node
                allNodes.put(localNode.osm_id, localNode);
                allEntities.put(localNode.osm_id, localNode );
            }
            return localNode;
        } else if(entity instanceof OSMWay) {
            OSMWay localWay = allWays.get(entity.osm_id);
            if(localWay == entity) {
                return localWay;
            }
            if(debugEntityIds.contains(entity.osm_id)) {
                System.out.println(name + " CHECK ADD ENTITY " + entity + ", existing? " + localWay);
            }
            if(localWay != null) { //merge if there's already a local copy of the entity
                handleMerge(entity, localWay, mergeStrategy, allWays, conflictingEntities);
            } else {
                final OSMWay curWay = (OSMWay) entity;
                //add all the child nodes of the way, then add the way itself
                localWay = new OSMWay(curWay, null, OSMEntity.MemberCopyStrategy.none);
                for(final OSMNode node : curWay.getNodes()) {
                    final OSMNode addedNode = (OSMNode) addEntity(node, mergeStrategy, conflictingEntities);
                    localWay.appendNode(addedNode);
                }
                allWays.put(localWay.osm_id, localWay);
                allEntities.put(localWay.osm_id, localWay);
            }
            return localWay;
        } else if(entity instanceof OSMRelation) {
            OSMRelation localRelation = allRelations.get(entity.osm_id);
            if(localRelation == entity) {
                return localRelation;
            }
            if(debugEntityIds.contains(entity.osm_id)) {
                System.out.println(name + " CHECK ADD ENTITY " + entity + ", existing? " + localRelation);
            }

            if(localRelation != null) { //merge if there's already a local copy of the entity
                handleMerge(entity, localRelation, mergeStrategy, allRelations, conflictingEntities);
            } else {
                final OSMRelation curRelation = (OSMRelation) entity;
                localRelation = new OSMRelation(curRelation, null, OSMEntity.MemberCopyStrategy.none);

                //add all the member entities of the relation, then add the relation itself
                for(final OSMRelation.OSMRelationMember member : curRelation.members) {
                    final OSMEntity addedMember = addEntity(member.member, mergeStrategy, conflictingEntities);
                    localRelation.addMember(addedMember, member.role);
                }
                allRelations.put(localRelation.osm_id, localRelation);
                allEntities.put(localRelation.osm_id, localRelation);
            }
            return localRelation;
        }
        //shouldn't reach here!
        return null;
    }
    /**
     * Purges an entity from the dataset, marking it as an incomplete entity
     * @param entity
     */
    public void purgeEntity(final OSMEntity entity) {
        entity.downgradeToIncompleteEntity();
    }
    public boolean deleteEntity(final long entityId) {
        final OSMEntity entityToDelete = allEntities.get(entityId);
        if(entityToDelete != null) {
            return deleteEntity(entityToDelete);
        }
        return false;
    }

    /**
     * Remove the given entity from this space
     * @param entityToDelete
     * @return TRUE if deleted, FALSE if not
     */
    private boolean deleteEntity(final OSMEntity entityToDelete) {
        //entity subclass-specific operations
        if(entityToDelete instanceof OSMNode) {
            final OSMNode theNode = (OSMNode) entityToDelete;
            //check way membership, removing the entity if possible
            for(final OSMWay way : theNode.containingWays.values()) {
                way.removeNode(theNode);
            }
        } else if(entityToDelete instanceof OSMWay) {
            final OSMWay theWay = (OSMWay) entityToDelete;
            //delete any nodes that are untagged, and aren't a member of any other ways or relations
            final List<OSMNode> containedNodes = new ArrayList<>(theWay.getNodes());
            for(final OSMNode containedNode : containedNodes) {
                theWay.removeNode(containedNode);
                if((containedNode.getTags() == null || containedNode.getTags().isEmpty()) &&
                        containedNode.containingWays.isEmpty() && containedNode.containingRelations.isEmpty()) {
                    deleteEntity(containedNode);
                }
            }
        } else if(entityToDelete instanceof OSMRelation) {
            final OSMRelation theRelation = (OSMRelation) entityToDelete;
            theRelation.clearMembers(); //delete all memberships in the relation
        }

        //remove the instances of entityToDelete in any relations
        final Map<Long, OSMRelation> containingRelations = new HashMap<>(entityToDelete.containingRelations);
        for(final OSMRelation relation : containingRelations.values()) {
            relation.removeMember(entityToDelete);
        }

        //and remove all references from the main data arrays
        if(entityToDelete instanceof OSMNode) {
            allNodes.remove(entityToDelete.osm_id);
        } else if(entityToDelete instanceof OSMWay) {
            allWays.remove(entityToDelete.osm_id);
        } else if(entityToDelete instanceof OSMRelation) {
            allRelations.remove(entityToDelete.osm_id);
        }
        final OSMEntity deletedEntity = allEntities.remove(entityToDelete.osm_id);
        return deletedEntity != null;
    }
    /**
     * Merge the given entities
     * @param theEntityId
     * @param withEntityId
     * @return the merged entity
     */
    public OSMEntity mergeEntities(final long theEntityId, final long withEntityId) {
        //get a handle on our main reference to theEntity - must be a member of this space
        final OSMEntity theEntity = allEntities.get(theEntityId), withEntity = allEntities.get(withEntityId);
        if(theEntity == null || withEntity == null) {
            System.out.println("Entities not in space!");
            return null;
        }

        //determine which entity has the "best" (i.e. oldest) metadata
        final OSMEntity entityWithBestMetadata = determinePreservedEntity(theEntity, withEntity);

        final OSMEntity targetEntity;
        final boolean entityReplaced = entityWithBestMetadata == withEntity;
        if(!entityReplaced) { //i.e. we're keeping our current entity
            //System.out.println("using local for merge: " + theEntity.osm_id + "/" + withEntity.osm_id);
            targetEntity = theEntity;
        } else {
            //System.out.println(name + " using OTHER for merge: " + theEntity.osm_id + "/" + withEntity.osm_id);

            //create a copy of theEntity with the OSM id of the incoming entity
            if(theEntity instanceof OSMNode) {
                final OSMNode theNode = (OSMNode) theEntity;
                targetEntity = new OSMNode(theNode, withEntityId);
                //replace all ways' references to the original node with the new node
                for(final OSMWay containingWay : theNode.containingWays.values()) {
                    containingWay.replaceNode(theNode, (OSMNode) targetEntity);
                }
            } else if (theEntity instanceof OSMWay) { //TODO not tested
                targetEntity = new OSMWay((OSMWay) theEntity, withEntityId, OSMEntity.MemberCopyStrategy.shallow);
            } else { //TODO not tested
                targetEntity = new OSMRelation((OSMRelation) theEntity, withEntityId, OSMEntity.MemberCopyStrategy.shallow);
            }

            //and add targetEntity to any relations theEntity is involved in
            for(final OSMRelation containingRelation : theEntity.containingRelations.values()) {
                containingRelation.replaceMember(theEntity, targetEntity);
            }

            //copy the metadata from withEntity, since it's "better"
            OSMEntity.copyMetadata(withEntity, targetEntity);

            //remove theEntity from the database
            deleteEntity(theEntity);
        }

        //copy over withEntity's tags into targetEntity
        targetEntity.copyTagsFrom(withEntity, OSMEntity.TagMergeStrategy.copyTags);

        //if merging nodes, add the localnode to any ways that the withNode belongs to
        if(targetEntity instanceof OSMNode && withEntity instanceof OSMNode) {
            final OSMNode targetNode = (OSMNode) targetEntity, withNode = (OSMNode) withEntity;
            final Map<Long, OSMWay> containingWays = new HashMap<>(withNode.containingWays);
            for (final OSMWay containingWay : containingWays.values()) {
                containingWay.replaceNode(withNode, targetNode);
            }
            targetNode.setCoordinate(withNode.getCentroid());
        }

        //also replace the incoming entity's relation memberships with the target entity
        final Map<Long, OSMRelation> containingRelations = new HashMap<>(withEntity.containingRelations);
        for(final OSMRelation containingRelation : containingRelations.values()) {
            containingRelation.replaceMember(withEntity, targetEntity);
        }

        //remove withEntity from this space
        deleteEntity(withEntity);

        //if the target entity is a new entity (with withEntity's id), add it to this space now that withEntity is deleted (to avoid osm_id conflicts)
        if(entityReplaced) {
            addEntity(targetEntity, OSMEntity.TagMergeStrategy.keepTags, null);
        }
        return targetEntity;
    }
    /**
     *
     * @param entity1
     * @param entity2
     * @return The entity whose metadata should be preserved, or null if neither has metadata (or a corrupted timestamp)
     */
    private static OSMEntity determinePreservedEntity(final OSMEntity entity1, final OSMEntity entity2) {
        //first check if the entities are versioned (i.e. exist in the OSM database)
        final boolean entity1Versioned = entity1.version >= 0, entity2Versioned = entity2.version >= 0;

        //if both entities have metadata, use the older entity's metadata
        if(entity1Versioned && entity2Versioned) {
            final SimpleDateFormat parserSDF = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            try {
                final Date entity1Date = parserSDF.parse(entity1.timestamp);
                final Date entity2Date = parserSDF.parse(entity2.timestamp);
                if(entity1Date.getTime() > entity2Date.getTime()) {
                    return entity2;
                } else {
                    return entity1;
                }
            } catch (ParseException ignored) {}
        } else if(entity1Versioned) {
            return entity1;
        } else if(entity2Versioned){
            return entity2;
        }
        return null;
    }

    /**
     * Split the given way at the given node, returning the new way(s)
     * @param originalWay the way to split
     * @param splitNodes the node(s) to split it at
     * @return the split ways
     * @throws InvalidArgumentException
     */
    public OSMWay[] splitWay(final OSMWay originalWay, final OSMNode[] splitNodes) throws InvalidArgumentException {
        //basic checks
        final List<OSMNode> curNodes = originalWay.getNodes();
        List<NodeIndexer> actualSplitNodes = new ArrayList<>(splitNodes.length);
        for(final OSMNode splitNode : splitNodes) {
            final int nodeIndex = curNodes.indexOf(splitNode);
            if (nodeIndex < 0) {
                final String errMsg[] = {"splitNode " + splitNode.osm_id + " is not a member of the originalWay \"" + originalWay.getTag("name") + "\" (" + originalWay.osm_id + ")"};
                throw new InvalidArgumentException(errMsg);
            }
            //no need to split at first/last nodes
            if (splitNode == originalWay.getFirstNode() || splitNode == originalWay.getLastNode()) {
                continue;
            }
            actualSplitNodes.add(new NodeIndexer(splitNode, nodeIndex));
        }
        if(actualSplitNodes.size() == 0) {
            return new OSMWay[]{originalWay};
        }
        actualSplitNodes.sort(nodeIndexComparator); //sort the split nodes so their order matches the order of originalWay's nodes
        final int splitWayCount = actualSplitNodes.size() + 1;

        //generate the arrays of nodes that will belong to the newly-split ways
        final List<List<OSMNode>> splitWayNodes = new ArrayList<>(splitWayCount);
        for(int i=0;i<actualSplitNodes.size()+1;i++) {
            splitWayNodes.add(new ArrayList<>());
        }

        int splitNodeIndex = 0;
        OSMNode nextSplitNode = actualSplitNodes.get(splitNodeIndex).node;
        List<OSMNode> curWayNodes = splitWayNodes.get(splitNodeIndex);
        for(final OSMNode node : curNodes) {
            curWayNodes.add(node);

            //if we've reached a split node, increment the split index and add the current node to the new split array
            if(node == nextSplitNode) {
                if(++splitNodeIndex < actualSplitNodes.size()) {
                    nextSplitNode = actualSplitNodes.get(splitNodeIndex).node;
                } else {
                    nextSplitNode = null;
                }
                curWayNodes = splitWayNodes.get(splitNodeIndex);
                curWayNodes.add(node);
            }
        }

        //chose which portion will retain the history of originalWay - we'll use the split way with the most nodes
        List<OSMNode> oldWayNewNodes = null;
        int largestNodeSize = -1;
        for(final List<OSMNode> wayNodes : splitWayNodes) {
            if(wayNodes.size() > largestNodeSize) {
                oldWayNewNodes = wayNodes;
                largestNodeSize = wayNodes.size();
            }
        }
        assert oldWayNewNodes != null;

        //and create the new split way(s), removing the new ways' non-intersecting nodes from originalWay
        final OSMWay[] allSplitWays = new OSMWay[splitWayCount];
        final List<OSMWay> newWays = new ArrayList<>(actualSplitNodes.size() + 1);
        int splitWayIndex = 0, originalWayNewIndex = 0;
        for(final List<OSMNode> wayNodes : splitWayNodes) {
            final OSMWay curWay;
            if(wayNodes == oldWayNewNodes) { //originalWay: just edit its node list
                curWay = originalWay;

                //remove all the nodes that aren't in the NEW node list for originalWay
                final List<OSMNode> nodesToRemove = new ArrayList<>(originalWay.getNodes().size() - oldWayNewNodes.size());
                for(final OSMNode oldWayNode : originalWay.getNodes()) {
                    if(!oldWayNewNodes.contains(oldWayNode)) {
                        nodesToRemove.add(oldWayNode);
                    }
                }
                for(final OSMNode nodeToRemove : nodesToRemove) {
                    originalWay.removeNode(nodeToRemove);
                }
                originalWayNewIndex = splitWayIndex;
            } else { //a new way: create an OSMWay (which is added to this space) with originalWay's tags, and add the split nodes
                curWay = createWay(originalWay.getTags(), wayNodes);
                newWays.add(curWay);
            }
            allSplitWays[splitWayIndex++] = curWay;
        }

        //now we need to handle membership of any relations, to ensure they're updated with the correct ways
        for(final OSMRelation containingRelation : originalWay.containingRelations.values()) {
            containingRelation.handleMemberWaySplit(originalWay, allSplitWays);
        }

        return allSplitWays;
    }
    /**
     * Parses an OSM XML file into entity objects, and adds them to this space
     * @param fileName
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public void loadFromXML(final String fileName) throws IOException, ParserConfigurationException, SAXException {
        final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
        long minimumEntityId = 0;
        name = fileName;

        parser.parse(new File(fileName), new DefaultHandler() {
            private final static String tagNode = "node", tagWay = "way", tagRelation = "relation", tagTag = "tag", tagWayNode = "nd", tagRelationMember = "member";
            private final static String keyId = "id", keyRef = "ref", keyRole = "role", keyType = "type";
            private final Stack<OSMEntity> entityStack = new Stack<>();

            private void processBaseValues(OSMEntity entity, Attributes attributes) {
                final String visible = attributes.getValue("visible");
                if (visible != null) {
                    entity.visible = Boolean.parseBoolean(visible);
                }

                //add version metadata, if present
                final String version = attributes.getValue("version");
                if(version != null) {
                    entity.uid = Integer.parseInt(attributes.getValue("uid"));
                    entity.user = attributes.getValue("user");
                    entity.changeset = Integer.parseInt(attributes.getValue("changeset"));
                    entity.version = Integer.parseInt(attributes.getValue("version"));
                    entity.timestamp = attributes.getValue("timestamp");
                }
            }
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                //System.out.println("Start " + uri + ":" + localName + ":" + qName + ", attr: " + attributes.toString());
                switch (qName) {
                    case tagNode: //nodes are simply added to the entity array
                        final OSMNode curNode = new OSMNode(Long.parseLong(attributes.getValue(keyId)));
                        curNode.setComplete(true);
                        curNode.setCoordinate(Double.parseDouble(attributes.getValue("lat")), Double.parseDouble(attributes.getValue("lon")));
                        processBaseValues(curNode, attributes);

                        final OSMNode addedNode = (OSMNode) addEntity(curNode, OSMEntity.TagMergeStrategy.keepTags, null);
                        entityStack.push(addedNode);
                        break;
                    case tagWay:
                        final OSMWay curWay = new OSMWay(Long.parseLong(attributes.getValue(keyId)));
                        curWay.setComplete(true);
                        processBaseValues(curWay, attributes);

                        final OSMWay addedWay = (OSMWay) addEntity(curWay, OSMEntity.TagMergeStrategy.keepTags, null);
                        entityStack.push(addedWay);
                        break;
                    case tagRelation:
                        final OSMRelation curRelation = new OSMRelation(Long.parseLong(attributes.getValue(keyId)));
                        curRelation.setComplete(true);
                        processBaseValues(curRelation, attributes);
                        final OSMRelation addedRelation = (OSMRelation) addEntity(curRelation, OSMEntity.TagMergeStrategy.keepTags, null);
                        entityStack.push(addedRelation);
                        break;
                    case tagTag: //tag for a node/way/relation
                        final OSMEntity curEntity = entityStack.peek();
                        curEntity.setTag(attributes.getValue("k"), attributes.getValue("v"));
                        break;
                    case tagWayNode: //node as a member of a way
                        long nodeId = Long.parseLong(attributes.getValue(keyRef));
                        final OSMNode wayNode = allNodes.get(nodeId);
                        final OSMWay curWayToAdd = (OSMWay) entityStack.peek();
                        curWayToAdd.appendNode(wayNode);
                        break;
                    case tagRelationMember:
                        final OSMRelation relationToAdd = (OSMRelation) entityStack.peek();
                        final long memberId = Long.parseLong(attributes.getValue(keyRef));
                        final OSMEntity memberEntity;
                        switch (attributes.getValue(keyType)) {
                            case tagNode:
                                memberEntity = allNodes.get(memberId);
                                break;
                            case tagWay:
                                memberEntity = allWays.get(memberId);
                                break;
                            case tagRelation:
                                memberEntity = allRelations.get(memberId);
                                break;
                            default:
                                memberEntity = null;
                                break;
                        }
                        if(memberEntity != null) {
                            relationToAdd.addMember(memberEntity, attributes.getValue(keyRole));
                        }
                        break;
                }
            }
            @Override
            public void endElement(String uri, String localName, String qName) throws SAXException {
                switch (qName) {
                    case tagNode: //nodes are simply added to the entity array
                    case tagWay:
                    case tagRelation:
                        entityStack.pop();
                        break;
                    case tagTag: //tag for a node/way/relation
                    case tagWayNode: //node as a member of a way
                    case tagRelationMember:
                        //type == node/way/relation
                        break;

                }
            }
            /*@Override
            public void characters(char[] ch, int start, int length) throws SAXException {
            }*/
        });

        for(long id : allEntities.keySet()) {
            minimumEntityId = Math.min(minimumEntityId, id);
        }
        setIdSequence(minimumEntityId);
    }

    /**
     * Returns the combined bounding box for the entire entity space
     * @return
     */
    public Region getBoundingBox() {
        Region fileBoundingBox = null;
        for (final OSMEntity entity : allEntities.values()) {
            final Region entityBoundingBox = entity.getBoundingBox();
            if(entityBoundingBox == null) {
                continue;
            }
            if(fileBoundingBox != null) {
                fileBoundingBox.combinedBoxWithRegion(entityBoundingBox);
            } else {
                fileBoundingBox = entityBoundingBox;
            }
        }
        return fileBoundingBox;
    }
    /**
     * Outputs the current entity space to an OSM XML file
     * @param fileName
     * @throws IOException
     */
    public void outputXml(String fileName) throws IOException {
        //produce an empty XMl file if no entities
        if(allEntities.size() == 0) {
            final FileWriter writer = new FileWriter(fileName);
            writer.write(XML_DOCUMENT_OPEN);
            writer.write(XML_DOCUMENT_CLOSE);
            writer.close();
            return;
        }

        //generate the bounding box for the file
        final Region fileBoundingBox = getBoundingBox();

        final FileWriter writer = new FileWriter(fileName);
        writer.write(XML_DOCUMENT_OPEN);
        if(fileBoundingBox != null) {
            writer.write(String.format(XML_BOUNDING_BOX, fileBoundingBox.origin.latitude, fileBoundingBox.origin.longitude, fileBoundingBox.extent.latitude, fileBoundingBox.extent.longitude));
        }

        for(final OSMNode node: allNodes.values()) {
            if(node.isComplete()) {
                writer.write(node.toOSMXML());
            }
        }
        for(final OSMWay way: allWays.values()) {
            if(way.isComplete()) {
                writer.write(way.toOSMXML());
            }
        }
        for(final OSMRelation relation: allRelations.values()) {
            if(relation.isComplete()) {
                writer.write(relation.toOSMXML());
            }
        }
        writer.write(XML_DOCUMENT_CLOSE);

        writer.close();
    }

    /**
     * Merge the given space's entities into this space
     * @param otherSpace the space from which to copy the entities
     * @param mergeStrategy determines how to handle entities which exist in both spaces
     * @param conflictingEntities any conflicting entities will be added to this list
     */
    public void mergeWithSpace(final OSMEntitySpace otherSpace, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities) {
        System.out.println("MERGE " + name + " WITH " + otherSpace.name);

        //final HashMap<Long, OSMEntity> addedEntities = new HashMap<>(otherSpace.allEntities.size()), originalEntities = new HashMap<>(otherSpace.allEntities.size());
        for(final OSMEntity otherSpaceEntity : otherSpace.allEntities.values()) {
            final OSMEntity localEntity = addEntity(otherSpaceEntity, mergeStrategy, conflictingEntities);
            /*if(localEntity != otherSpaceEntity) {
                originalEntities.put(otherSpaceEntity.osm_id, otherSpaceEntity);
                addedEntities.put(localEntity.osm_id, localEntity);
            }*/
        }

        //System.out.println(name + " added " + addedEntities.size() + " new entities");
        for(final Long id : debugEntityIds) {
            System.out.println(name + " ENTITY: " + allEntities.get(id));
        }

        /*for(final OSMNode node : allNodes.values()) {
            final HashMap<Long, OSMWay> containingWays = new HashMap<>(4);
            for(final OSMWay way : allWays.values()) {
                if(way.getNodes().contains(node)) {
                    containingWays.put(way.osm_id, way);
                }
            }
            if(containingWays.size() != node.containingWayCount) {
                System.out.println(node.osm_id + " OUT OF SYNC");
            }
        }*/
        /*for(final OSMEntity entity : allEntities.values()) {
            final HashMap<Long, OSMRelation> containingRelations = new HashMap<>(4);
            for(final OSMRelation relation : allRelations.values()) {
                for(final OSMRelation.OSMRelationMember member : relation.members) {
                    if(member.member == entity) {
                        containingRelations.put(entity.osm_id, relation);
                        break;
                    }
                }
            }
            if(containingRelations.size() != entity.containingRelationCount) {
                System.out.println(entity.osm_id + " REL OUT OF SYNC");
            }
        }*/
    }
}
