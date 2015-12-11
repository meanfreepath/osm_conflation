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
    public final static String KEY_LATITUDE = "lat", KEY_LONGITUDE = "lon", KEY_OSMID = "osm_id", KEY_FROM = "from", KEY_VIA = "via", KEY_TO = "to", KEY_OPERATOR = "operator", KEY_ROUTE = "route", KEY_ROUTE_MASTER = "route_master", KEY_NAME = "name", KEY_REF = "ref", KEY_LOCAL_REF = "local_ref", KEY_DESCRIPTION = "description", KEY_WEBSITE = "website", KEY_TYPE = "type", KEY_PUBLIC_TRANSPORT_VERSION = "public_transport:version", KEY_COLOUR = "colour", KEY_AMENITY = "amenity", KEY_WHEELCHAIR = "wheelchair";
    public final static String TAG_ROUTE = "route", TAG_ROUTE_MASTER = "route_master", TAG_BUS = "bus", TAG_LIGHT_RAIL = "light_rail", TAG_TRAM = "tram", TAG_SUBWAY = "subway", TAG_TRAIN = "train", TAG_FERRY = "ferry", TAG_AERIALWAY = "aerialway", TAG_YES = "yes", TAG_NO = "no";

    public enum OSMType {
        node, way, relation
    }
    public enum MemberCopyStrategy {
        none, shallow, deep
    }

    protected final static String BASE_XML_TAG_FORMAT_TAG = "  <tag k=\"%s\" v=\"%s\"/>\n";
    public final long osm_id;

    //Metadata (not required)
    public int uid = -1, version = -1, changeset = -1;
    public boolean visible = true;
    public String user = null, timestamp = null;
    protected Region boundingBox;

    protected HashMap<String,String> tags;

    public abstract OSMType getType();
    public abstract Region getBoundingBox();
    public abstract Point getCentroid();
    public abstract String toString();

    public OSMEntity(final long id) {
        osm_id = id;
    }

    /**
     * Copy constructor
     * @param entityToCopy
     */
    public OSMEntity(final OSMEntity entityToCopy) {
        osm_id = entityToCopy.osm_id;
        uid = entityToCopy.uid;
        version = entityToCopy.version;
        changeset = entityToCopy.changeset;
        user = entityToCopy.user;
        timestamp = entityToCopy.timestamp;
        boundingBox = entityToCopy.boundingBox;

        if(entityToCopy.tags != null) {
            tags = new HashMap<>(entityToCopy.tags);
        }
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
    /**
     * Sets the given tag on this entity, only if it doesn't already exist
     * @param name
     * @param value
     * @throws InvalidArgumentException
     */
    public void addTag(final String name, final String value) throws InvalidArgumentException {
        if(tags == null) {
            tags = new HashMap<>();
        }

        if(tags.containsKey(name)) {
            String[] msg = {"Tag \"" + name + "\" already set!"};
            throw new InvalidArgumentException(msg);
        }
        tags.put(name, value.trim());
    }

    /**
     * Sets the given tag on this entity, replacing the previous value (if present)
     * @param name
     * @param value
     */
    public void setTag(final String name, final String value) {
        if(tags == null) {
            tags = new HashMap<>();
        }
        tags.put(name, value.trim());
    }

    /**
     *
     * @param otherEntity
     * @param clearTags
     * @return Any tags that conflict (if checkForConflicts is TRUE), null otherwise
     */
    public Map<String, String> copyTagsFrom(final OSMEntity otherEntity, final boolean clearTags, final boolean checkForConflicts) {
        if(tags == null) {
            tags = new HashMap<>();
        } else if(clearTags) {
            tags.clear();
        }

        if(checkForConflicts) {
            HashMap<String, String> conflictingTags = new HashMap<>(4);
            for(Map.Entry<String, String> tag : otherEntity.tags.entrySet()) {
                if(tags.containsKey(tag.getKey()) && !tags.get(tag.getKey()).equals(tag.getValue())) {
                    conflictingTags.put(tag.getKey(), tag.getValue());
                }
            }
            return conflictingTags.size() > 0 ? conflictingTags : null;
        } else {
            tags.putAll(otherEntity.tags);
        }
        return null;
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
}
