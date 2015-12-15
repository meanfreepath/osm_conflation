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

    public final HashMap<Long, OSMEntity> allEntities;
    public final HashMap<Long, OSMNode> allNodes;
    public final HashMap<Long, OSMWay> allWays;
    public final HashMap<Long, OSMRelation> allRelations;
    public String name;

    private void setIdSequence(long sequence) {
        osmIdSequence = sequence;
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

        if(withTags != null) {
            for(Map.Entry<String, String> tag : withTags.entrySet()) {
                newNode.setTag(tag.getKey(), tag.getValue());
            }
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
        return (OSMRelation) addEntity(newRelation, OSMEntity.TagMergeStrategy.keepTags, null);
    }

    /**
     * Create a space with the given initial capacity
     * @param capacity
     */
    public OSMEntitySpace(final int capacity) {
        allEntities = new HashMap<>(capacity);
        allNodes = new HashMap<>(capacity);
        allWays = new HashMap<>(capacity);
        allRelations = new HashMap<>(capacity);
    }
    public OSMEntitySpace(final OSMEntitySpace spaceToDuplicate, final int additionalCapacity) {
        name = spaceToDuplicate.name;

        final int capacity = spaceToDuplicate.allEntities.size() + additionalCapacity;
        allEntities = new HashMap<>(capacity);
        allNodes = new HashMap<>(capacity);
        allWays = new HashMap<>(capacity);
        allRelations = new HashMap<>(capacity);
        mergeWithSpace(spaceToDuplicate, OSMEntity.TagMergeStrategy.keepTags, null);
    }
    private static boolean handleMerge(final OSMEntity entity, final OSMEntity existingEntity, final OSMEntity.TagMergeStrategy mergeStrategy, final HashMap<Long, ? extends OSMEntity> entityList, List<OSMEntity> conflictingEntities) {
        boolean shouldAddEntity = existingEntity == null;
        switch(mergeStrategy) {
            case keepTags:
            case replaceTags:
            case copyTags:
            case copyNonexistentTags:
                if(!shouldAddEntity) {
                    existingEntity.copyTagsFrom(entity, mergeStrategy);
                }
                break;
            case mergeTags:
                if(!shouldAddEntity) {
                    Map<String, String> conflictingTags = existingEntity.copyTagsFrom(entity, mergeStrategy);
                    if(conflictingTags != null) { //add the conflict to the list for processing
                        conflictingEntities.add(existingEntity);
                    }
                }
                break;
        }
        return shouldAddEntity;
    }
    /**
     * Add the given OSM entity to the space, as well as any entities it contains
     * @param entity
     * @param mergeStrategy
     * @return
     */
    public OSMEntity addEntity(final OSMEntity entity, final OSMEntity.TagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities) {
        final OSMEntity existingEntity;
        if(entity instanceof OSMNode) {
            existingEntity = allNodes.get(entity.osm_id);
            final OSMNode nodeToAdd;
            if(handleMerge(entity, existingEntity, mergeStrategy, allNodes, conflictingEntities)) {
                nodeToAdd = new OSMNode((OSMNode) entity, null); //create an exact copy of the node
                allNodes.put(nodeToAdd.osm_id, nodeToAdd);
                allEntities.put(nodeToAdd.osm_id, nodeToAdd);
                return nodeToAdd;
            }
            return existingEntity;
        } else if(entity instanceof OSMWay) {
            existingEntity = allWays.get(entity.osm_id);
            if(handleMerge(entity, existingEntity, mergeStrategy, allWays, conflictingEntities)) {
                //add all the child nodes of the way, then add the way itself
                final OSMWay curWay = (OSMWay) entity;
                final OSMWay localWay = new OSMWay(curWay, null, OSMEntity.MemberCopyStrategy.none);
                for(final OSMNode node : curWay.getNodes()) {
                    final OSMNode addedNode = (OSMNode) addEntity(node, mergeStrategy, conflictingEntities);
                    localWay.appendNode(addedNode);
                }
                allWays.put(localWay.osm_id, localWay);
                allEntities.put(localWay.osm_id, localWay);
                return localWay;
            }
            return existingEntity;
        } else if(entity instanceof OSMRelation) {
            existingEntity = allRelations.get(entity.osm_id);
            final OSMRelation curRelation = (OSMRelation) entity;
            if(handleMerge(entity, existingEntity, mergeStrategy, allRelations, conflictingEntities)) {
                final OSMRelation localRelation = new OSMRelation(curRelation, null, OSMEntity.MemberCopyStrategy.none);

                //add all the member entities of the relation, then add the relation itself
                for(final OSMRelation.OSMRelationMember member : curRelation.members) {
                    final OSMEntity addedMember = addEntity(member.member, mergeStrategy, conflictingEntities);
                    localRelation.addMember(addedMember, member.role);
                }
                allRelations.put(localRelation.osm_id, localRelation);
                allEntities.put(localRelation.osm_id, localRelation);
                return localRelation;
            }
            return existingEntity;
        }
        //shouldn't reach here!
        return null;
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

        //also copy over any memberships of the incoming entity
        final Map<Long, OSMRelation> containingRelations = new HashMap<>(withEntity.containingRelations);
        for(final OSMRelation containingRelation : containingRelations.values()) {
            final int memberIndex = containingRelation.indexOfMember(withEntity);
            final OSMRelation.OSMRelationMember member = containingRelation.members.get(memberIndex);
            containingRelation.addMember(targetEntity, member.role);
        }

        //remove withEntity from this space
        deleteEntity(withEntity);

        //if the target entity is a new entity (with withEntity's id), add it to this spacenow that withEntity is deleted
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

        final OSMEntitySpace entitySpace = this;
        parser.parse(new File(fileName), new DefaultHandler() {
            private final static String tagNode = "node", tagWay = "way", tagRelation = "relation", tagTag = "tag", tagWayNode = "nd", tagRelationMember = "member";
            private final static String keyId = "id", keyRef = "ref", keyRole = "role", keyType = "type";
            private Stack<OSMEntity> entityStack = new Stack<>();

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
                        curNode.setCoordinate(Double.parseDouble(attributes.getValue("lat")), Double.parseDouble(attributes.getValue("lon")));
                        processBaseValues(curNode, attributes);

                        final OSMNode addedNode = (OSMNode) addEntity(curNode, OSMEntity.TagMergeStrategy.keepTags, null);
                        entityStack.push(addedNode);
                        break;
                    case tagWay:
                        final OSMWay curWay = new OSMWay(Long.parseLong(attributes.getValue(keyId)));
                        processBaseValues(curWay, attributes);

                        final OSMWay addedWay = (OSMWay) addEntity(curWay, OSMEntity.TagMergeStrategy.keepTags, null);
                        entityStack.push(addedWay);
                        break;
                    case tagRelation:
                        final OSMRelation curRelation = new OSMRelation(Long.parseLong(attributes.getValue(keyId)));
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
            if(fileBoundingBox != null) {
                fileBoundingBox.combinedBoxWithRegion(entity.getBoundingBox());
            } else {
                fileBoundingBox = entity.getBoundingBox();
            }
        }
        return fileBoundingBox;
    }
    /**
     * Outputs the current entity space to an OSM XML file
     * @param fileName
     * @throws IOException
     * @throws InvalidArgumentException
     */
    public void outputXml(String fileName) throws IOException, InvalidArgumentException {
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
            writer.write(node.toString());
        }
        for(final OSMWay way: allWays.values()) {
            writer.write(way.toString());
        }
        for(final OSMRelation relation: allRelations.values()) {
            writer.write(relation.toString());
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
        for(final OSMNode node : otherSpace.allNodes.values()) {
            addEntity(node, mergeStrategy, conflictingEntities);
        }
        for(final OSMWay way : otherSpace.allWays.values()) {
            addEntity(way, mergeStrategy, conflictingEntities);
        }
        for(final OSMRelation relation: otherSpace.allRelations.values()) {
            addEntity(relation, mergeStrategy, conflictingEntities);
        }
    }
}
