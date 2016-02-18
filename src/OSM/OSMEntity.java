package OSM;

import com.sun.javaws.exceptions.InvalidArgumentException;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by nick on 10/15/15.
 */
public abstract class OSMEntity {
    public final static String KEY_LATITUDE = "lat", KEY_LONGITUDE = "lon", KEY_OSMID = "osm_id", KEY_FROM = "from", KEY_VIA = "via", KEY_TO = "to", KEY_OPERATOR = "operator", KEY_ROUTE = "route", KEY_ROUTE_MASTER = "route_master", KEY_NAME = "name", KEY_REF = "ref", KEY_LOCAL_REF = "local_ref", KEY_DESCRIPTION = "description", KEY_WEBSITE = "website", KEY_TYPE = "type", KEY_COLOUR = "colour", KEY_AMENITY = "amenity", KEY_WHEELCHAIR = "wheelchair", KEY_SOURCE = "source";
    public final static String TAG_ROUTE = "route", TAG_ROUTE_MASTER = "route_master", TAG_BUS = "bus", TAG_LIGHT_RAIL = "light_rail", TAG_TRAM = "tram", TAG_SUBWAY = "subway", TAG_TRAIN = "train", TAG_FERRY = "ferry", TAG_AERIALWAY = "aerialway", TAG_YES = "yes", TAG_NO = "no";
    public final static String MEMBERSHIP_DEFAULT = "", MEMBERSHIP_STOP = "stop", MEMBERSHIP_PLATFORM = "platform";

    public final static String KEY_HIGHWAY = "highway", KEY_RAILWAY = "railway", KEY_SUBWAY = "subway", KEY_PUBLIC_TRANSPORT = "public_transport", KEY_PUBLIC_TRANSPORT_VERSION = "public_transport:version", KEY_BUS = "bus", KEY_TRAIN = "train", KEY_FERRY = "ferry", KEY_TRAM = "tram", KEY_AERIALWAY = "aerialway", KEY_FUNICULAR = "funicular";
    public final static String TAG_LEGACY_BUS_STOP = "bus_stop", TAG_PLATFORM = "platform", TAG_STOP_POSITION = "stop_position", TAG_LEGACY_FERRY_TERMINAL = "ferry_terminal";

    protected final static String
            BASE_XML_TAG_FORMAT_TAG = "  <tag k=\"%s\" v=\"%s\"/>\n",
            ACTION_ATTRIBUTE_FORMAT = " action=\"%s\"";

    public enum OSMType {
        node, way, relation
    }
    public enum MemberCopyStrategy {
        none, shallow
    }
    public enum TagMergeStrategy {
        keepTags, replaceTags, copyTags, copyNonexistentTags, mergeTags
    }
    public enum ChangeAction {
        none, modify, delete
    }

    public static boolean debugEnabled = false;

    public final long osm_id;

    //Metadata (not required)
    public int uid = -1, version = -1, changeset = -1;
    public boolean visible = true;
    public String user = null, timestamp = null;
    protected ChangeAction action = ChangeAction.none;


    protected Region boundingBox;
    protected boolean complete = false;

    protected HashMap<String,String> tags;
    public final HashMap<Long, OSMRelation> containingRelations = new HashMap<>(4);
    public short containingRelationCount = 0;

    public abstract OSMType getType();
    public abstract Region getBoundingBox();
    public abstract Point getCentroid();
    public abstract String toOSMXML();

    public OSMEntity(final long id) {
        osm_id = id;
    }

    /**
     * Copy constructor
     * @param entityToCopy
     * @param idOverride: if specified, will use this id instead of entityToCopy's OSM id
     */
    public OSMEntity(final OSMEntity entityToCopy, final Long idOverride) {
        if(idOverride == null) {
            osm_id = entityToCopy.osm_id;
        } else {
            osm_id = idOverride;
        }
        complete = entityToCopy.complete;
        action = entityToCopy.action;
        boundingBox = entityToCopy.boundingBox;
        if(entityToCopy.tags != null) {
            tags = new HashMap<>(entityToCopy.tags);
        }

        copyMetadata(entityToCopy, this);
    }
    protected void upgradeToCompleteEntity(final OSMEntity completeEntity) {
        if(complete || osm_id != completeEntity.osm_id) {
            System.out.println("BAD UPGRADE " + osm_id + "/" + completeEntity.osm_id);
        }
        complete = completeEntity.complete;
        action = completeEntity.action;
        boundingBox = completeEntity.boundingBox;
        if(completeEntity.tags != null) {
            tags = new HashMap<>(completeEntity.tags);
        }

        copyMetadata(completeEntity, this);

        //and notify any containing relations that this member is now complete
        for(final OSMRelation containingRelation : containingRelations.values()) {
            containingRelation.memberWasMadeComplete(this);
        }
    }
    protected void downgradeToIncompleteEntity() {
        complete = false;
        boundingBox = null;
        tags = null;

        uid = version = changeset = -1;
        user = timestamp = null;
    }

    /**
     * Copy the value of the given tag (if present) between entities
     * @param from
     * @param to
     * @param name
     */
    public static void copyTag(final OSMEntity from, final OSMEntity to, final String name) {
        final String fromValue = from.getTag(name);
        if(fromValue != null) {
            to.setTag(name, fromValue);
        }
    }
    protected static void copyMetadata(final OSMEntity from, final OSMEntity to) {
        to.uid = from.uid;
        to.version = from.version;
        to.changeset = from.changeset;
        to.user = from.user;
        to.timestamp = from.timestamp;
    }
    /**
     * Sets the given tag on this entity, only if it doesn't already exist
     * @param name
     * @param value
     * @throws InvalidArgumentException
     */
    public void addTag(final String name, final String value) throws InvalidArgumentException {
        if(!complete) { //can't set a tag on an incomplete entity
            return;
        }
        if(tags == null) {
            tags = new HashMap<>();
        }

        if(tags.containsKey(name)) {
            String[] msg = {"Tag \"" + name + "\" already set!"};
            throw new InvalidArgumentException(msg);
        }
        tags.put(name, value.trim());
        markAsModified();
    }

    /**
     * Sets the given tag on this entity, replacing the previous value (if present)
     * @param name
     * @param value
     */
    public void setTag(final String name, final String value) {
        if(!complete) { //can't set a tag on an incomplete entity
            System.out.println("ADDING TAG TO INCOMPLETE " + osm_id);
            return;
        }
        if(tags == null) {
            tags = new HashMap<>();
        }
        if(value != null) {
            tags.put(name, value.trim());
        } else {
            removeTag(name);
        }
        markAsModified();
    }
    public boolean removeTag(final String name) {
        if(!complete) { //can't set a tag on an incomplete entity
            return false;
        }
        if(tags == null) {
            return false;
        }
        final String removedTag = tags.remove(name);
        if(removedTag != null) {
            markAsModified();
            return true;
        }
        return false;
    }
    /**
     *
     * @param otherEntity
     * @param mergeStrategy
     * @return Any tags that conflict (if checkForConflicts is TRUE), null otherwise
     */
    public Map<String, String> copyTagsFrom(final OSMEntity otherEntity, final TagMergeStrategy mergeStrategy) {
        if(!complete) { //can't set a tag on an incomplete entity
            return null;
        }
        if(tags == null) {
            tags = new HashMap<>();
        }

        HashMap<String, String> conflictingTags = null;
        switch (mergeStrategy) {
            case keepTags:
                break;
            case replaceTags:
                tags = new HashMap<>(otherEntity.tags);
                break;
            case copyTags:
                tags.putAll(otherEntity.tags);
                break;
            case copyNonexistentTags:
                for(Map.Entry<String, String> tag : otherEntity.tags.entrySet()) {
                    if(!tags.containsKey(tag.getKey())) {
                        tags.put(tag.getKey(), tag.getValue());
                    }
                }
                break;
            case mergeTags:
                conflictingTags = new HashMap<>(4);
                for(Map.Entry<String, String> tag : otherEntity.tags.entrySet()) {
                    if(tags.containsKey(tag.getKey()) && !tags.get(tag.getKey()).equals(tag.getValue())) {
                        conflictingTags.put(tag.getKey(), tag.getValue());
                    }
                }
                break;
        }
        markAsModified();

        return conflictingTags != null && conflictingTags.size() > 0 ? conflictingTags : null;
    }
    public void markAsModified() {
        action = ChangeAction.modify;
    }
    public void markAsDeleted() {
        action = ChangeAction.delete;
    }
    public ChangeAction getAction() {
        return action;
    }
    /**
     * Get the value of the current tag
     * @param key
     * @return
     */
    public final String getTag(final String key) {
        if(tags == null) {
            return null;
        }
        return tags.get(key);
    }
    /**
     * Gets the full list of tags for this entity
     * @return
     */
    public final Map<String, String> getTags() {
        if(tags == null) {
            return null;
        }
        return tags;
    }
    public boolean hasTag(final String name) {
        return tags != null && tags.containsKey(name);
    }

    /**
     * Notifies this entity it's been added to the given relation's member list
     * @param relation
     */
    protected void didAddToRelation(final OSMRelation relation) {
        addContainingRelation(relation);
    }
    /**
     * Notifies this entity it's been removed from the given relation's member list
     * @param relation
     */
    protected void didRemoveFromRelation(final OSMRelation relation) {
        removeContainingRelation(relation);
    }
    protected void addContainingRelation(final OSMRelation relation) {
        if(!containingRelations.containsKey(relation.osm_id)) {
            containingRelations.put(relation.osm_id, relation);
            containingRelationCount++;
        }
    }
    protected void removeContainingRelation(final OSMRelation relation) {
        if(containingRelations.containsKey(relation.osm_id)) {
            containingRelations.remove(relation.osm_id);
            containingRelationCount--;
        }
    }
    public boolean isComplete() {
        return complete;
    }
    public void setComplete(boolean complete) {
        if(!this.complete && complete) { //can't set a node back to incomplete
            this.complete = true;
        }
    }
    public static String escapeForXML(final String str){
        final StringBuilder result = new StringBuilder(str.length());
        final StringCharacterIterator iterator = new StringCharacterIterator(str);
        char character = iterator.current();
        while (character != CharacterIterator.DONE ){
            if (character == '<') {
                result.append("&lt;");
            }
            else if (character == '>') {
                result.append("&gt;");
            }
            else if (character == '\"') {
                result.append("&quot;");
            }
            else if (character == '\'') {
                result.append("&#039;");
            }
            else if (character == '&') {
                result.append("&amp;");
            }
            else {
                //the char is not a special one
                //add it to the result as is
                result.append(character);
            }
            character = iterator.next();
        }
        return result.toString();
    }
    protected static String actionTagAttribute(final ChangeAction action) {
        return action != ChangeAction.none ? String.format(ACTION_ATTRIBUTE_FORMAT, action.name()) : "";
    }
}
