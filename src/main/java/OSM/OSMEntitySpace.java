package OSM;

import Importer.InvalidArgumentException;
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
            XML_DOCUMENT_OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" upload=\"%s\" generator=\"KCMetroImporter\">\n",
            XML_BOUNDING_BOX = " <bounds minlat=\"%.07f\" minlon=\"%.07f\" maxlat=\"%.07f\" maxlon=\"%.07f\"/>\n",
            XML_DOCUMENT_CLOSE = "</osm>\n";

    private final static boolean debugEnabled = false;

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
    public final HashMap<Long, OSMEntity> deletedEntities;
    public final HashMap<Long, OSMNode> allNodes;
    public final HashMap<Long, OSMWay> allWays;
    public final HashMap<Long, OSMRelation> allRelations;
    public String name;
    public final HashMap<Long, OSMEntity> debugEntities = new HashMap<>(8);

    public ArrayList<Long> debugEntityIds = new ArrayList<>();

    public void setCanUpload(boolean canUpload) {
        this.canUpload = canUpload;
    }
    private boolean canUpload = false;

    private static void setIdSequence(long sequence) {
        osmIdSequence = sequence;
    }

    /**
     * Create a space with the given initial capacity
     * @param capacity
     */
    public OSMEntitySpace(final int capacity) {
        initDebug();
        final int nodeCapacity = (int) (0.8 * capacity), wayCapacity = (int) (0.1 * capacity), relationCapacity = (int) (0.1 * capacity);

        allEntities = new HashMap<>(capacity);
        allNodes = new HashMap<>(nodeCapacity);
        allWays = new HashMap<>(wayCapacity);
        allRelations = new HashMap<>(relationCapacity);
        deletedEntities = new HashMap<>(capacity / 10);

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
        deletedEntities = new HashMap<>(capacity / 10);
        mergeWithSpace(spaceToDuplicate, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    private void initDebug() {
        if(debugEnabled) {
            debugEntityIds.add(94043L);
            debugEntityIds.add(1571732L);
            debugEntityIds.add(371267123L);
        }
    }

    /**
     * Create a new Node in this space
     * @param x
     * @param y
     * @param withTags tags to add to the node, if any
     * @return the new node
     */
    public OSMNode createNode(final double x, final double y, final Map<String, String> withTags) {
        final OSMNode newNode = new OSMNode(--osmIdSequence);
        newNode.setCoordinate(x, y);
        newNode.setComplete(OSMEntity.CompletionStatus.self);
        newNode.markAsModified();

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newNode.setTag(tag.getKey(), tag.getValue());
            }
        }
        addNodeToSpaceList(newNode);
        return newNode;
    }

    /**
     * Creates a local copy of the given node
     * @param nodeToCopy
     * @return
     */
    private OSMNode importNode(final OSMNode nodeToCopy, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities) {
        //first check if we have a local copy of the node
        final OSMNode localNode = allNodes.get(nodeToCopy.osm_id);
        if(localNode == nodeToCopy) {
            return localNode;
        } else if(localNode != null) {
            handleMerge(nodeToCopy, localNode, mergeStrategy, conflictingEntities);
            return localNode;
        }

        //if not, create an exact copy of the node in the local space
        final OSMNode newNode = new OSMNode(nodeToCopy, null);
        addNodeToSpaceList(newNode);
        return newNode;
    }
    /**
     * Create an incomplete (i.e. not fully-downloaded) node in this space
     * @param osm_id
     * @return the newly-created node, or if this space already has a copy, the existing node
     */
    public OSMNode addIncompleteNode(final long osm_id) {
        //check if a node with the given id already exists
        final OSMNode existingNode = allNodes.get(osm_id);
        if (existingNode != null) {
            return existingNode;
        }

        //otherwise, create a local copy
        final OSMNode newNode = new OSMNode(osm_id);
        addNodeToSpaceList(newNode);
        return newNode;
    }
    /**
     * Create a new Way in this space
     * @param withTags tags to add to the way, if any
     * @param withNodes nodes to add to the way, if any
     * @return the new way
     */
    public OSMWay createWay(final Map<String, String> withTags, final List<OSMNode> withNodes) {
        final OSMWay newWay = new OSMWay(--osmIdSequence);
        newWay.setComplete(OSMEntity.CompletionStatus.memberList);
        newWay.markAsModified();

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newWay.setTag(tag.getKey(), tag.getValue());
            }
        }
        if(withNodes != null) {
            newWay.setNodes(withNodes);
        }
        addWayToSpaceList(newWay);
        return newWay;
    }

    /**
     * Creates a local copy of the given way
     * @param wayToCopy the way to copy
     * @param addChildNodes whether to add the child nodes of the way as complete entities
     * @return the local copy of the way
     */
    private OSMWay importWay(final OSMWay wayToCopy, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities, final boolean addChildNodes) {
        final OSMWay localWay = allWays.get(wayToCopy.osm_id);
        if(localWay == wayToCopy) { //ways are the same object, just return
            return localWay;
        } else if(localWay != null) { //ways are different objects: check if anything needs to be merged
            if(addChildNodes) {
                handleMerge(wayToCopy, localWay, mergeStrategy, conflictingEntities);
            }
            return localWay;
        }

        //otherwise, create an exact copy of the way in the local space
        final OSMWay newWay;
        if(addChildNodes || wayToCopy.action != OSMEntity.ChangeAction.none) {
            newWay = new OSMWay(wayToCopy, null);
            importExternalWayNodes(wayToCopy, newWay);
            addWayToSpaceList(newWay);
        } else {
            newWay = addIncompleteWay(wayToCopy.osm_id);
        }
        return newWay;
    }
    private void upgradeIncompleteNodes(final OSMWay externalWay, final OSMWay localWay) {
        for(final OSMNode node : localWay.getNodes()) {
            if(node.complete == OSMEntity.CompletionStatus.incomplete) { //if the local node is incomplete, scan the external way's nodes for it
                for(final OSMNode externalNode : externalWay.getNodes()) {
                    if(externalNode.osm_id == node.osm_id) {
                        if(externalNode.complete != OSMEntity.CompletionStatus.incomplete) {
                            addEntity(externalNode, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                        }
                        break;
                    }
                }
            }
        }
    }
    private void upgradeIncompleteMembers(final OSMRelation externalRelation, final OSMRelation localRelation) {
        for(final OSMRelation.OSMRelationMember member : localRelation.members) {
            if(member.member.complete == OSMEntity.CompletionStatus.incomplete) { //if the local member is incomplete, scan the external relations's members for it
                for(final OSMRelation.OSMRelationMember externalMember : externalRelation.members) {
                    if(externalMember.member.osm_id == member.member.osm_id) {
                        if(externalMember.member.complete != OSMEntity.CompletionStatus.incomplete) {
                            addEntity(externalMember.member, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                        }
                        break;
                    }
                }
            }
        }
    }
    /**
     * Create an incomplete (i.e. not fully-downloaded) way in this space
     * @param osm_id the id of the entity
     * @return the entity
     */
    public OSMWay addIncompleteWay(final long osm_id) {
        final OSMWay existingWay = allWays.get(osm_id);
        if (existingWay != null) {
            return existingWay;
        }

        //otherwise, create a local copy
        final OSMWay newWay = new OSMWay(osm_id);
        addWayToSpaceList(newWay);
        return newWay;
    }
    /**
     * Create a new Relation in this space
     * @param withTags tags to add to the relation, if any
     * @param withMembers memberList to add to the relation, if any
     * @return the new relation
     */
    public OSMRelation createRelation(final Map<String, String> withTags, final List<OSMRelation.OSMRelationMember> withMembers) {
        final OSMRelation newRelation = new OSMRelation(--osmIdSequence);
        newRelation.setComplete(OSMEntity.CompletionStatus.memberList);
        newRelation.markAsModified();

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newRelation.setTag(tag.getKey(), tag.getValue());
            }
        }
        if(withMembers != null) {
            newRelation.copyMembers(withMembers);
        }

        addRelationToSpaceList(newRelation);
        return newRelation;
    }
    /**
     * Creates a local instance of the given relation, if not already present
     * @param relationToCopy the relation
     * @param mergeStrategy the tag merge strategy to use
     * @param conflictingEntities the conflicting entities arising from this operation, if any
     * @param addChildMembers whether to add the memberList as complete entities
     * @return the local copy of the relation
     */
    private OSMRelation importRelation(final OSMRelation relationToCopy, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities, final boolean addChildMembers) {
        OSMRelation localRelation = allRelations.get(relationToCopy.osm_id);
        if(localRelation == relationToCopy) { //relations are the same object, just return
            return localRelation;
        } else if(localRelation != null) { //relations are different objects: check if anything needs to be merged
            if(addChildMembers) {
                handleMerge(relationToCopy, localRelation, mergeStrategy, conflictingEntities);
            }
            return localRelation;
        }

        //otherwise, create an exact copy of the relation in the local space (members will be incomplete if addChildMembers is false)
        final OSMRelation newRelation = new OSMRelation(relationToCopy, null);
        importExternalRelationMembers(relationToCopy, newRelation, addChildMembers);

        addRelationToSpaceList(newRelation);
        return newRelation;
    }
    /**
     * Create an incomplete (i.e. not fully-downloaded) relation in this space
     * @param osm_id
     * @return
     */
    public OSMRelation addIncompleteRelation(final long osm_id) {
        final OSMRelation existingRelation = allRelations.get(osm_id);
        if (existingRelation != null) {
            return existingRelation;
        }
        final OSMRelation newRelation = new OSMRelation(osm_id);
        addRelationToSpaceList(newRelation);
        return newRelation;
    }
    private void addNodeToSpaceList(final OSMNode newNode) {
        allNodes.put(newNode.osm_id, newNode);
        allEntities.put(newNode.osm_id, newNode);

        if(debugEnabled && debugEntityIds.contains(newNode.osm_id)) {
            System.out.println(name + " CREATE " + newNode.complete.toString() + " NODE " + newNode);
            debugEntities.put(newNode.osm_id, newNode);
        }
    }
    private void addWayToSpaceList(final OSMWay newWay) {
        allWays.put(newWay.osm_id, newWay);
        allEntities.put(newWay.osm_id, newWay);

        if(debugEnabled && debugEntityIds.contains(newWay.osm_id)) {
            System.out.println(name + " CREATE " + newWay.complete.toString() + " WAY " + newWay);
            debugEntities.put(newWay.osm_id, newWay);
        }
    }
    private void addRelationToSpaceList(final OSMRelation newRelation) {
        allRelations.put(newRelation.osm_id, newRelation);
        allEntities.put(newRelation.osm_id, newRelation);

        if(debugEnabled && debugEntityIds.contains(newRelation.osm_id)) {
            System.out.println(name + " CREATE " + newRelation.complete.toString() + " RELATION " + newRelation);
            debugEntities.put(newRelation.osm_id, newRelation);
        }
    }

    /**
     *
     * @param entity
     * @param existingEntity
     * @param mergeStrategy
     * @param conflictingEntities
     * @return TRUE if a new entity should be created, FALSE if it has been merged
     */
    private void handleMerge(final OSMEntity entity, final OSMEntity existingEntity, final OSMEntity.TagMergeStrategy mergeStrategy, List<OSMEntity> conflictingEntities) {
        if(entity.complete == OSMEntity.CompletionStatus.incomplete) { //no merging can happen if incoming entity is incomplete
            return;
        }
        if(existingEntity.complete != OSMEntity.CompletionStatus.incomplete) { //existingEntity is complete: just update tags
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

            //for ways and relations, check if the local entity needs to import any of its child members
            if(existingEntity.complete != OSMEntity.CompletionStatus.membersComplete) {
                if (existingEntity instanceof OSMWay) {
                    upgradeIncompleteNodes((OSMWay) entity, (OSMWay) existingEntity);
                } else if (existingEntity instanceof OSMRelation) {
                    upgradeIncompleteMembers((OSMRelation) entity, (OSMRelation) existingEntity);
                }
            }
        } else { //if existing entity is not complete, we need to "upgrade" it without altering the base object pointer (which would screw up a lot of dependencies)
            existingEntity.upgradeCompletionStatus(entity);
            //handle entity-specific cases here
            if(entity instanceof OSMWay) {
                importExternalWayNodes((OSMWay) entity, (OSMWay) existingEntity);
            } else if(entity instanceof OSMRelation) {
                importExternalRelationMembers((OSMRelation) entity, (OSMRelation) existingEntity, true);
            }

            if(debugEnabled && debugEntityIds.contains(entity.osm_id)) {
                System.out.println(name + " UPGRADE ENTITY " + existingEntity);
            }
        }
    }
    /**
     * Add the given OSM entity to the space, as well as any entities it contains
     * @param entity the entity to add
     * @param mergeStrategy the tag merging strategy to use
     * @param addChildEntities when TRUE, import child entities (ways' nodes, relation memberList).  When FALSE, will import child entities as incomplete entities.
     * @param addContainingEntitiesToDepth
     * @return this entitySpace's copy of the entity
     */
    public OSMEntity addEntity(final OSMEntity entity, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities, boolean addChildEntities, int addContainingEntitiesToDepth) {
        final OSMEntity.ChangeAction originalEntityChangeAction = entity.action;
        final OSMEntity addedEntity;
        if(entity instanceof OSMNode) {
            if(addChildEntities) {
                addedEntity = importNode((OSMNode) entity, mergeStrategy, conflictingEntities);
            } else {
                addedEntity = addIncompleteNode(entity.osm_id);
            }
        } else if(entity instanceof OSMWay) {
            addedEntity = importWay((OSMWay) entity, mergeStrategy, conflictingEntities, addChildEntities);
        } else if(entity instanceof OSMRelation) {
            addedEntity = importRelation((OSMRelation) entity, mergeStrategy, conflictingEntities, addChildEntities);
        } else { //shouldn't reach here!
            addedEntity = null;
        }
        assert addedEntity != null;

        if(addContainingEntitiesToDepth > 0) {
            for(OSMRelation containingRelation : entity.getContainingRelations().values()) {
                addEntity(containingRelation, OSMEntity.TagMergeStrategy.keepTags, null, false, addContainingEntitiesToDepth - 1);
            }
        }

        //the entity may have been marked as modified when updating tags on the new local copy: restore here
        addedEntity.action = originalEntityChangeAction;
        return addedEntity;
    }

    /**
     * Copy the nodes from externalWay into this entity space and onto localWay
     * TODO: handle conflicts between the ways' node memberships
     * @param externalWay the external way whose nodes we want to copy
     * @param localWay the local copy of the way
     */
    private void importExternalWayNodes(final OSMWay externalWay, final OSMWay localWay) {
        //make sure all the nodes on the incoming completed way are in this way's entitySpace
        final List<OSMNode> localNodes = new ArrayList<>(externalWay.getNodes().size());
        for(final OSMNode node : externalWay.getNodes()) {
            localNodes.add((OSMNode) addEntity(node, OSMEntity.TagMergeStrategy.keepTags, null, true, 0));
        }

        //and add them onto the local way
        localWay.setNodes(localNodes);
    }

    /**
     * Copy the memberList from externalRelation into this entity space and onto localRelation
     * @param externalRelation the external way whose nodes we want to copy
     * @param localRelation the local copy of the way
     * @param asComplete whether to add the nodes as complete entities or not
     */
    private OSMRelation importExternalRelationMembers(final OSMRelation externalRelation, final OSMRelation localRelation, final boolean asComplete) {
        //add all the member entities of the relation, then add the relation itself
        final List<OSMRelation.OSMRelationMember> localMembers = new ArrayList<>(externalRelation.members.size());
        for(final OSMRelation.OSMRelationMember member : externalRelation.members) {
            final OSMEntity addedEntity = addEntity(member.member, OSMEntity.TagMergeStrategy.keepTags, null, asComplete, 0);
            localMembers.add(new OSMRelation.OSMRelationMember(addedEntity, member.role));
        }
        localRelation.copyMembers(localMembers);
        return localRelation;
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
    public boolean deleteEntity(final OSMEntity entityToDelete) {
        //get a handle on the local copy of the entity
        OSMEntity localEntityToDelete = allEntities.get(entityToDelete.osm_id);

        //if the local entity to delete doesn't exist in this space, we need to add it before beginning the deletion process
        if(localEntityToDelete == null) {
            localEntityToDelete = addEntity(entityToDelete, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
        }

        //remove all references from the main data arrays
        switch (localEntityToDelete.getType()) {
            case node:
                allNodes.remove(localEntityToDelete.osm_id);
                break;
            case way:
                allWays.remove(localEntityToDelete.osm_id);
                break;
            case relation:
                allRelations.remove(localEntityToDelete.osm_id);
                break;
        }
        allEntities.remove(localEntityToDelete.osm_id);

        //and mark the entity as deleted if it's already on the OSM server
        if(localEntityToDelete.didDelete(this)) {
            deletedEntities.put(localEntityToDelete.osm_id, localEntityToDelete);
        }
        return true;
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
            System.out.println("Entities not in space: " + theEntityId + ":" + (theEntity != null ? "OK" : "MISSING") + "/" + withEntityId + ":" + (withEntity != null ? "OK" : "MISSING"));
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
                for(final OSMWay containingWay : theNode.getContainingWays().values()) {
                    containingWay.replaceNode(theNode, (OSMNode) targetEntity);
                }
            } else if (theEntity instanceof OSMWay) { //TODO not tested
                targetEntity = new OSMWay((OSMWay) theEntity, withEntityId);
            } else { //TODO not tested
                targetEntity = new OSMRelation((OSMRelation) theEntity, withEntityId);
            }

            //and add targetEntity to any relations theEntity is involved in
            for(final OSMRelation containingRelation : theEntity.getContainingRelations().values()) {
                containingRelation.replaceEntityMemberships(theEntity, targetEntity);
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
            final Map<Long, OSMWay> containingWays = new HashMap<>(withNode.getContainingWays());
            for (final OSMWay containingWay : containingWays.values()) {
                containingWay.replaceNode(withNode, targetNode);
            }
            Point nodeCentroid = withNode.getCentroid();
            assert nodeCentroid != null;
            targetNode.setCoordinate(nodeCentroid);
        }

        //also replace the incoming entity's relation memberships with the target entity
        final Map<Long, OSMRelation> containingRelations = new HashMap<>(withEntity.getContainingRelations());
        for(final OSMRelation containingRelation : containingRelations.values()) {
            containingRelation.replaceEntityMemberships(withEntity, targetEntity);
        }

        //remove withEntity from this space
        deleteEntity(withEntity);

        //if the target entity is a new entity (with withEntity's id), add it to this space now that withEntity is deleted (to avoid osm_id conflicts)
        if(entityReplaced) {
            addEntity(targetEntity, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
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
        final boolean originalWayIsClosed = originalWay.isClosed();
        final List<NodeIndexer> actualSplitNodes = new ArrayList<>(splitNodes.length);
        for (final OSMNode splitNode : splitNodes) {
            final int nodeIndex = curNodes.indexOf(splitNode);
            if (nodeIndex < 0) {
                throw new InvalidArgumentException("splitNode " + splitNode.osm_id + " is not a member of the originalWay \"" + originalWay.getTag("name") + "\" (" + originalWay.osm_id + ")");
            }
            //no need to split at first/last nodes (unclosed ways only)
            if (!originalWayIsClosed && (splitNode == originalWay.getFirstNode() || splitNode == originalWay.getLastNode())) {
                continue;
            }
            actualSplitNodes.add(new NodeIndexer(splitNode, nodeIndex));
        }
        if (actualSplitNodes.size() == 0) {
            return new OSMWay[]{originalWay};
        }
        if (originalWayIsClosed && actualSplitNodes.size() < 2) {
            throw new InvalidArgumentException("Need to provide at least 2 unique nodes to split a closed way!");
        }

        //sort the split nodes so their order matches the order of originalWay's nodes
        actualSplitNodes.sort(nodeIndexComparator);
        final int splitWayCount = actualSplitNodes.size() + (originalWayIsClosed ? 0 : 1);

        //generate the arrays of nodes that will belong to the newly-split ways
        final List<List<OSMNode>> splitWayNodes = new ArrayList<>(splitWayCount);
        for (int i = 0; i < splitWayCount; i++) {
            splitWayNodes.add(new ArrayList<>());
        }

        if (originalWayIsClosed) {
            int splitNodeIndex = 0;
            NodeIndexer curSplitNodeIndexer = actualSplitNodes.get(0), nextSplitNodeIndexer;
            final ListIterator<NodeIndexer> splitNodeIterator = actualSplitNodes.listIterator(1);
            boolean finished = false;
            //iterate over the split nodes, using their indexes in the curNodes list to split into new node strings as needed
            while (!finished) {
                if (splitNodeIterator.hasNext()) {
                    nextSplitNodeIndexer = splitNodeIterator.next();
                } else {
                    nextSplitNodeIndexer = actualSplitNodes.get(0);
                    finished = true;
                }

                final List<OSMNode> curWayNodes = splitWayNodes.get(splitNodeIndex++);
                if (curSplitNodeIndexer.nodeIndex < nextSplitNodeIndexer.nodeIndex) { //get the sublist of nodes between the 2 split node indexes
                    curWayNodes.addAll(curNodes.subList(curSplitNodeIndexer.nodeIndex, nextSplitNodeIndexer.nodeIndex + 1));
                } else { //if the next index is less than the current, we need to wrap around the first/last nodes up to the next splitnode's index
                    curWayNodes.addAll(curNodes.subList(curSplitNodeIndexer.nodeIndex, curNodes.size() - 1));
                    curWayNodes.addAll(curNodes.subList(0, nextSplitNodeIndexer.nodeIndex + 1));
                }
                curSplitNodeIndexer = nextSplitNodeIndexer;
            }
        } else {
            int splitNodeIndex = 0;
            OSMNode nextSplitNode = actualSplitNodes.get(splitNodeIndex).node;
            List<OSMNode> curWayNodes = splitWayNodes.get(splitNodeIndex);
            for (final OSMNode node : curNodes) {
                curWayNodes.add(node);

                //if we've reached a split node, increment the split index and add the current node to the new split array
                if (node == nextSplitNode) {
                    if (++splitNodeIndex < actualSplitNodes.size()) {
                        nextSplitNode = actualSplitNodes.get(splitNodeIndex).node;
                    } else {
                        nextSplitNode = null;
                    }
                    curWayNodes = splitWayNodes.get(splitNodeIndex);
                    curWayNodes.add(node);
                }
            }
        }

        //chose which portion will retain the history of originalWay - we'll use the split way with the most nodes
        List<OSMNode> oldWayNewNodes = null;
        int largestNodeSize = -1;
        for (final List<OSMNode> wayNodes : splitWayNodes) {
            if (wayNodes.size() > largestNodeSize) {
                oldWayNewNodes = wayNodes;
                largestNodeSize = wayNodes.size();
            }
        }
        assert oldWayNewNodes != null;

        //Check if the originalWay's containing relations are valid PRIOR to the split
        final ArrayList<Boolean> containingRelationValidity = new ArrayList<>(originalWay.getContainingRelations().size());
        for (final OSMRelation containingRelation : originalWay.getContainingRelations().values()) {
            containingRelationValidity.add(containingRelation.isValid());
        }

        //and create the new split way(s), removing the new ways' non-intersecting nodes from originalWay
        final List<OSMNode> originalNodeList = new ArrayList<>(originalWay.getNodes()); //make a copy to preserve the nodes to make it easier for post-split code to run comparisons etc
        final OSMWay[] allSplitWays = new OSMWay[splitWayCount];
        int splitWayIndex = 0;
        for (final List<OSMNode> wayNodes : splitWayNodes) {
            final OSMWay curWay;
            if (wayNodes == oldWayNewNodes) { //originalWay: just edit its node list
                curWay = originalWay;
                curWay.setNodes(oldWayNewNodes);
            } else { //a new way: create an OSMWay (which is added to this space) with originalWay's tags, and add the split nodes
                curWay = createWay(originalWay.getTags(), wayNodes);
            }
            allSplitWays[splitWayIndex++] = curWay;
            curWay.markAsModified();
        }

        //now we need to handle membership of any relations, to ensure they're updated with the correct ways
        int idx = 0;
        final ArrayList<OSMRelation> originalWayContainingRelations = new ArrayList<>(originalWay.getContainingRelations().values());
        for (final OSMRelation containingRelation : originalWayContainingRelations) {
            containingRelation.handleMemberWaySplit(originalWay, originalNodeList, allSplitWays, containingRelationValidity.get(idx++));
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
                        curNode.setComplete(OSMEntity.CompletionStatus.self);
                        curNode.setCoordinate(SphericalMercator.latLonToMercator(Double.parseDouble(attributes.getValue("lat")), Double.parseDouble(attributes.getValue("lon"))));
                        processBaseValues(curNode, attributes);

                        final OSMNode addedNode = (OSMNode) addEntity(curNode, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                        entityStack.push(addedNode);
                        break;
                    case tagWay:
                        final OSMWay curWay = new OSMWay(Long.parseLong(attributes.getValue(keyId)));
                        curWay.setComplete(OSMEntity.CompletionStatus.memberList);
                        processBaseValues(curWay, attributes);

                        final OSMWay addedWay = (OSMWay) addEntity(curWay, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
                        entityStack.push(addedWay);
                        break;
                    case tagRelation:
                        final OSMRelation curRelation = new OSMRelation(Long.parseLong(attributes.getValue(keyId)));
                        curRelation.setComplete(OSMEntity.CompletionStatus.memberList);
                        processBaseValues(curRelation, attributes);
                        final OSMRelation addedRelation = (OSMRelation) addEntity(curRelation, OSMEntity.TagMergeStrategy.keepTags, null, true, 0);
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
                        curWayToAdd.setComplete(OSMEntity.CompletionStatus.memberList);
                        curWayToAdd.appendNode(wayNode);
                        break;
                    case tagRelationMember:
                        final OSMRelation relationToAdd = (OSMRelation) entityStack.peek();
                        relationToAdd.setComplete(OSMEntity.CompletionStatus.memberList);
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
    public void outputXml(final String fileName) throws IOException {
        outputXml(fileName, null);
    }
    /**
     * Outputs the current entity space to an OSM XML file
     * @param fileName
     * @throws IOException
     */
    public void outputXml(final String fileName, Region fileBoundingBox) throws IOException {
        //produce an empty XMl file if no entities
        if(allEntities.size() == 0) {
            final FileWriter writer = new FileWriter(fileName);
            writer.write(String.format(XML_DOCUMENT_OPEN, Boolean.toString(canUpload)));
            writer.write(XML_DOCUMENT_CLOSE);
            writer.close();
            return;
        }

        //create a sorted list of the relations first, to ensure that relations referring to other relations are placed after them
        final ArrayList<OSMRelation> sortedRelations = Graph.sortRelationsTopologically(allRelations);

        //generate the bounding box for the file, if not provided
        if(fileBoundingBox == null) {
            fileBoundingBox = getBoundingBox();
        }

        final FileWriter writer = new FileWriter(fileName);
        writer.write(String.format(XML_DOCUMENT_OPEN, Boolean.toString(canUpload)));
        if(fileBoundingBox != null) {
            final LatLonRegion latLonBoundingBox = SphericalMercator.mercatorToLatLon(fileBoundingBox);
            writer.write(String.format(XML_BOUNDING_BOX, latLonBoundingBox.origin.latitude, latLonBoundingBox.origin.longitude, latLonBoundingBox.extent.latitude, latLonBoundingBox.extent.longitude));
        }

        for(final OSMNode node: allNodes.values()) {
            if(node.complete != OSMEntity.CompletionStatus.incomplete) {
                writer.write(node.toOSMXML());
            }
        }
        for(final OSMWay way: allWays.values()) {
            if(way.complete.compareTo(OSMEntity.CompletionStatus.memberList) >= 0) {
                writer.write(way.toOSMXML());
            }
        }

        //NOTE: sortedRelations will be null if the Graph is unable to order them (i.e. cyclical relation)
        final Collection<OSMRelation> relationList = sortedRelations != null ? sortedRelations : allRelations.values();
        for(final OSMRelation relation: relationList) {
            if(relation.complete.compareTo(OSMEntity.CompletionStatus.memberList) >= 0) {
                writer.write(relation.toOSMXML());
            }
        }
        for(final OSMEntity entity: deletedEntities.values()) {
            writer.write(entity.toOSMXML());
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
        if(debugEnabled) {
            System.out.println("MERGE " + name + " WITH " + otherSpace.name);
        }

        //merge in the entities
        for(final OSMEntity otherSpaceEntity : otherSpace.allEntities.values()) {
            addEntity(otherSpaceEntity, mergeStrategy, conflictingEntities, true, 0);
        }

        //and delete any entities that were marked as deleted in the other space
        for(final OSMEntity otherSpaceEntity : otherSpace.deletedEntities.values()) {
            deleteEntity(otherSpaceEntity); //TODO: need to warn/handle case when the local copy of our entity was modified prior to this merge
        }

        if(debugEnabled) {
            for (final Long id : debugEntityIds) {
                System.out.println(name + " ENTITY: " + allEntities.get(id));
            }
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
                if(relation.containsMember(entity)) {
                    containingRelations.put(relation.osm_id, relation);
                }
            }
            if(containingRelations.size() != entity.containingRelationCount) {
                System.out.println(entity.osm_id + " REL OUT OF SYNC");
            }
        }*/
    }
    public void markAllEntitiesWithAction(final OSMEntity.ChangeAction action) {
        for(final OSMEntity entity : allEntities.values()) {
            entity.action = action;
        }
    }
    @Override
    public String toString() {
        return String.format("OSMEntitySpace @%d (“%s”): %d nodes, %d ways, %d relations, %d total", hashCode(), name, allNodes.size(), allWays.size(), allRelations.size(), allEntities.size());
    }
}
