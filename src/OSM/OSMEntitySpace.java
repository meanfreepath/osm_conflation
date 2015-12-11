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

    public enum EntityTagMergeStrategy {
        keepTags, replaceTags, mergeTags, mergeTagsIgnoreConflict
    }

    /**
     * Id sequence for new OSM entities
     */
    private static long osmIdSequence = 0;

    public final List<OSMEntity> allEntities;
    public final HashMap<Long, OSMNode> allNodes;
    public final HashMap<Long, OSMWay> allWays;
    public final HashMap<Long, OSMRelation> allRelations;

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
        return (OSMNode) addEntity(newNode, EntityTagMergeStrategy.keepTags, null);
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
        return (OSMWay) addEntity(newWay, EntityTagMergeStrategy.keepTags, null);
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
        return (OSMRelation) addEntity(newRelation, EntityTagMergeStrategy.keepTags, null);
    }

    /**
     * Create a space with the given initial capacity
     * @param capacity
     */
    public OSMEntitySpace(final int capacity) {
        allEntities = new ArrayList<>(capacity);
        allNodes = new HashMap<>(capacity);
        allWays = new HashMap<>(capacity);
        allRelations = new HashMap<>(capacity);
    }
    private static boolean handleMerge(final OSMEntity entity, final OSMEntity existingEntity, final EntityTagMergeStrategy mergeStrategy, final HashMap<Long, ? extends OSMEntity> entityList, List<OSMEntity> conflictingEntities) {
        boolean shouldAddEntity = existingEntity == null;
        switch(mergeStrategy) {
            case keepTags:
                break;
            case replaceTags:
                if(!shouldAddEntity) {
                    existingEntity.copyTagsFrom(entity, true, false);
                }
                break;
            case mergeTags:
                if(!shouldAddEntity) {
                    Map<String, String> conflictingTags = existingEntity.copyTagsFrom(entity, false, true);
                    if(conflictingTags != null) { //add the conflict to the list for processing
                        conflictingEntities.add(existingEntity);
                    }
                }
                break;
            case mergeTagsIgnoreConflict:
                if(!shouldAddEntity) {
                    existingEntity.copyTagsFrom(entity, false, false);
                }
                break;
        }
        return shouldAddEntity;
    }
    /**
     * Add the given OSM entity to the space, as well as any entities it contains
     * @param entity
     * @return
     */
    public OSMEntity addEntity(final OSMEntity entity, final EntityTagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities) {
        final OSMEntity resultEntity;
        final OSMEntity existingEntity;
        if(entity instanceof OSMNode) {
            existingEntity = allNodes.get(entity.osm_id);
            final OSMNode nodeToAdd;
            if(handleMerge(entity, existingEntity, mergeStrategy, allNodes, conflictingEntities)) {
                nodeToAdd = new OSMNode((OSMNode) entity); //create an exact copy of the node
                allNodes.put(nodeToAdd.osm_id, nodeToAdd);
                allEntities.add(nodeToAdd);
                return nodeToAdd;
            }
            return existingEntity;
        } else if(entity instanceof OSMWay) {
            existingEntity = allWays.get(entity.osm_id);
            if(handleMerge(entity, existingEntity, mergeStrategy, allWays, conflictingEntities)) {
                //add all the child nodes of the way, then add the way itself
                final OSMWay curWay = (OSMWay) entity;
                final OSMWay localWay = new OSMWay(curWay, OSMEntity.MemberCopyStrategy.none);
                for(final OSMNode node : curWay.getNodes()) {
                    final OSMNode addedNode = (OSMNode) addEntity(node, mergeStrategy, conflictingEntities);
                    localWay.appendNode(addedNode);
                }
                allWays.put(localWay.osm_id, localWay);
                allEntities.add(localWay);
                return localWay;
            }
            return existingEntity;
        } else if(entity instanceof OSMRelation) {
            existingEntity = allRelations.get(entity.osm_id);
            final OSMRelation curRelation = (OSMRelation) entity;
            if(handleMerge(entity, existingEntity, mergeStrategy, allRelations, conflictingEntities)) {
                final OSMRelation localRelation = new OSMRelation(curRelation, OSMEntity.MemberCopyStrategy.none);

                //add all the member entities of the relation, then add the relation itself
                for(final OSMRelation.OSMRelationMember member : curRelation.members) {
                    final OSMEntity addedMember = addEntity(member.member, mergeStrategy, conflictingEntities);
                    localRelation.addMember(addedMember, member.role);
                }
                allRelations.put(localRelation.osm_id, localRelation);
                allEntities.add(localRelation);
                return localRelation;
            }
            return existingEntity;
        }
        //shouldn't reach here!
        return null;
    }
    public void mergeEntities(final OSMEntity theEntity, final OSMEntity withEntity) {
        if(withEntity.getTags() != null) {
            for(final String key : withEntity.getTags().keySet()) {
                OSMEntity.copyTag(withEntity, theEntity, key);
            }
        }

        if(theEntity.osm_id < 0 && withEntity.osm_id > 0) {
            //theEntity.osm_id = withEntity.osm_id; //TODO
        }

        if(withEntity.version >= 0) {
            //osm_id = otherEntity.osm_id;

            //if both entities have metadata, use the older entity's metadata
            if(theEntity.version >= 0) {
                final SimpleDateFormat parserSDF = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                try {
                    final Date theDate = parserSDF.parse(theEntity.timestamp);
                    final Date withDate = parserSDF.parse(withEntity.timestamp);
                    if(theDate.getTime() > withDate.getTime()) {
                        theEntity.uid = withEntity.uid;
                        theEntity.version = withEntity.version;
                        theEntity.changeset = withEntity.changeset;
                        theEntity.user = withEntity.user;
                        theEntity.timestamp = withEntity.timestamp;
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    /**
     * Generates the mapping between nodes and their containing ways, in preparation for
     * checking way intersections etc.
     * @param clearPrevious - whether to wipe the nodes' previous containingWays value
     */
    public void generateWayNodeMapping(final boolean clearPrevious) {
        if(clearPrevious) {
            for(final OSMNode node : allNodes.values()) {
                node.resetContainingWays();
            }
        }

        for(final OSMWay way : allWays.values()) {
            for(final OSMNode containedNode : way.getNodes()) {
                containedNode.addContainingWay(way);
            }
        }
    }
    /**
     * Parses an OSM XML file into entity objects, and adds them to this space
     * @param fileName
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public void loadFromXML(String fileName) throws IOException, ParserConfigurationException, SAXException {
        final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
        long minimumEntityId = 0;

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

                        final OSMNode addedNode = (OSMNode) addEntity(curNode, EntityTagMergeStrategy.keepTags, null);
                        entityStack.push(addedNode);
                        break;
                    case tagWay:
                        final OSMWay curWay = new OSMWay(Long.parseLong(attributes.getValue(keyId)));
                        processBaseValues(curWay, attributes);

                        final OSMWay addedWay = (OSMWay) addEntity(curWay, EntityTagMergeStrategy.keepTags, null);
                        entityStack.push(addedWay);
                        break;
                    case tagRelation:
                        final OSMRelation curRelation = new OSMRelation(Long.parseLong(attributes.getValue(keyId)));
                        processBaseValues(curRelation, attributes);
                        final OSMRelation addedRelation = (OSMRelation) addEntity(curRelation, EntityTagMergeStrategy.keepTags, null);
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

        for(OSMEntity e : allEntities) {
            minimumEntityId = Math.min(minimumEntityId, e.osm_id);
        }
        setIdSequence(minimumEntityId);
    }

    /**
     * Returns the combined bounding box for the entire entity space
     * @return
     */
    public Region getBoundingBox() {
        Region fileBoundingBox = null;
        for (OSMEntity entity : allEntities) {
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
            FileWriter writer = new FileWriter(fileName);
            writer.write(XML_DOCUMENT_OPEN);
            writer.write(XML_DOCUMENT_CLOSE);
            writer.close();
            return;
        }

        //generate the bounding box for the file
        Region fileBoundingBox = getBoundingBox();

        final FileWriter writer = new FileWriter(fileName);
        writer.write(XML_DOCUMENT_OPEN);
        if(fileBoundingBox != null) {
            writer.write(String.format(XML_BOUNDING_BOX, fileBoundingBox.origin.latitude, fileBoundingBox.origin.longitude, fileBoundingBox.extent.latitude, fileBoundingBox.extent.longitude));
        }

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

    /**
     * Merge the given space's entities into this space
     * @param otherSpace the space from which to copy the entities
     * @param mergeStrategy determines how to handle entities which exist in both spaces
     * @param conflictingEntities any conflicting entities will be added to this list
     */
    public void mergeWithSpace(final OSMEntitySpace otherSpace, final EntityTagMergeStrategy mergeStrategy, final List<OSMEntity> conflictingEntities) {
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
