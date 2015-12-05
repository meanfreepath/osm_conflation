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
import java.util.*;

/**
 * Created by nick on 11/4/15.
 */
public class OSMEntitySpace {
    private final static String
            XML_DOCUMENT_OPEN = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" upload=\"false\" generator=\"KCMetroImporter\">\n",
            XML_BOUNDING_BOX = " <bounds minlat=\"%.07f\" minlon=\"%.07f\" maxlat=\"%.07f\" maxlon=\"%.07f\"/>\n",
            XML_DOCUMENT_CLOSE = "</osm>\n";

    public enum EntityMergeStrategy {
        dontMerge, overwrite, mergeTags, mergeTagsIgnoreConflict
    }

    public final List<OSMEntity> allEntities;
    public final HashMap<Long, OSMNode> allNodes;
    public final HashMap<Long, OSMWay> allWays;
    public final HashMap<Long, OSMRelation> allRelations;

    public OSMEntitySpace(int capacity) {
        allEntities = new ArrayList<>(capacity);
        allNodes = new HashMap<>(capacity);
        allWays = new HashMap<>(capacity);
        allRelations = new HashMap<>(capacity);
    }
    private static void mergeTags(OSMEntity entity1, OSMEntity entity2, boolean overwriteConflict, List<OSMEntity> conflictingEntities) {
        for(Map.Entry<String, String> tag : entity2.tags.entrySet()) {
            final String tagKey = tag.getKey();
            boolean e1HasTag = entity1.hasTag(tagKey);
            if(!e1HasTag || overwriteConflict) {
                entity1.setTag(tagKey, tag.getValue());
            } else if(e1HasTag && entity2.hasTag(tagKey) && !entity1.getTag(tagKey).equals(entity2.getTag(tagKey))) {
                if(conflictingEntities != null) {
                    conflictingEntities.add(entity1);
                }
            }
        }
    }
    private boolean handleMerge(final OSMEntity entity, final EntityMergeStrategy mergeStrategy, final HashMap<Long, ? extends OSMEntity> entityList, List<OSMEntity> conflictingEntities) {
        boolean shouldAddEntity = false;
        final OSMEntity existingEntity;
        switch(mergeStrategy) {
            case dontMerge:
                if(!entityList.containsKey(entity.osm_id)) {
                    shouldAddEntity = true;
                }
                break;
            case overwrite:
                shouldAddEntity = true;
                break;
            case mergeTags:
                existingEntity = entityList.get(entity.osm_id);
                if(existingEntity == null) {
                    shouldAddEntity = true;
                } else {
                    mergeTags(existingEntity, entity, false, conflictingEntities);
                }
                break;
            case mergeTagsIgnoreConflict:
                existingEntity = entityList.get(entity.osm_id);
                if(existingEntity == null) {
                    shouldAddEntity = true;
                } else {
                    mergeTags(existingEntity, entity, true, conflictingEntities);
                }
                break;
        }
        return shouldAddEntity;
    }
    /**
     * Add an OSM entity to the space, as well as any entities it contains
     * @param entity
     */
    public void addEntity(OSMEntity entity, EntityMergeStrategy mergeStrategy, List<OSMEntity> conflictingEntities) {

        if(entity instanceof OSMNode) {
            if(handleMerge(entity, mergeStrategy, allNodes, conflictingEntities)) {
                allNodes.put(entity.osm_id, (OSMNode) entity);
            }
        } else if(entity instanceof OSMWay) {
            if(handleMerge(entity, mergeStrategy, allWays, conflictingEntities)) {
                //add all the child nodes of the way, then add the way itself
                OSMWay curWay = (OSMWay) entity;
                for(OSMNode node : curWay.getNodes()) { //TODO handle member node merging
                    addEntity(node, mergeStrategy, conflictingEntities);
                }
                allWays.put(entity.osm_id, (OSMWay) entity);
            }
        } else if(entity instanceof OSMRelation) {
            OSMRelation curRelation = (OSMRelation) entity;
            if(handleMerge(entity, mergeStrategy, allRelations, conflictingEntities)) {
                //add all the member entities of the relation, then add the relation itself
                for(OSMRelation.OSMRelationMember member : curRelation.members) {  //TODO handle member merging
                    addEntity(member.member, mergeStrategy, conflictingEntities);
                }
                allRelations.put(entity.osm_id, (OSMRelation) entity);
            }
        }
        allEntities.add(entity);
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
     * Parses an OSM XML file into entity objects
     * @param fileName
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws SAXException
     */
    public void loadFromXML(String fileName) throws IOException, ParserConfigurationException, SAXException {
        SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
        long minimumEntityId = 0;

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

                        addEntity(curNode, EntityMergeStrategy.dontMerge, null);
                        entityStack.push(curNode);
                        break;
                    case tagWay:
                        final OSMWay curWay = new OSMWay(Long.parseLong(attributes.getValue(keyId)));
                        processBaseValues(curWay, attributes);

                        addEntity(curWay, EntityMergeStrategy.dontMerge, null);
                        entityStack.push(curWay);
                        break;
                    case tagRelation:
                        final OSMRelation curRelation = new OSMRelation(Long.parseLong(attributes.getValue(keyId)));
                        processBaseValues(curRelation, attributes);
                        addEntity(curRelation, EntityMergeStrategy.dontMerge, null);
                        entityStack.push(curRelation);
                        break;
                    case tagTag: //tag for a node/way/relation
                        OSMEntity curEntity = entityStack.peek();
                        curEntity.setTag(attributes.getValue("k"), attributes.getValue("v"));
                        break;
                    case tagWayNode: //node as a member of a way
                        long nodeId = Long.parseLong(attributes.getValue(keyRef));
                        OSMNode wayNode = allNodes.get(nodeId);
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
                //System.out.println("End " + uri + ":" + localName + ":" + qName);
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
        OSMEntity.setIdSequence(minimumEntityId);
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
     * Outputs the current entity space into an OSM XML file
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
    public void mergeWithSpace(OSMEntitySpace otherSpace, EntityMergeStrategy mergeStrategy, List<OSMEntity> conflictingEntities) {
        for(OSMNode node : otherSpace.allNodes.values()) {
            addEntity(node, mergeStrategy, conflictingEntities);
        }
        for(OSMWay way : otherSpace.allWays.values()) {
            addEntity(way, mergeStrategy, conflictingEntities);
        }
        for(OSMRelation relation: otherSpace.allRelations.values()) {
            addEntity(relation, mergeStrategy, conflictingEntities);
        }
    }
}
