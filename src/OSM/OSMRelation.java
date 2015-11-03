package OSM;

import com.sun.istack.internal.NotNull;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by nick on 10/15/15.
 */
public class OSMRelation extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <relation id=\"%d\" version=\"1\"/>\n",// user=\"$osmuser\" uid=\"$osmid\" visible=\"true\" version=\"1\">\n"];
            BASE_XML_TAG_FORMAT_OPEN = " <relation id=\"%s\" version=\"1\">\n"/* user=\"$osmuser\" uid=\"$osmid\" visible=\"true\" version=\"1\">\n"];*/,
            BASE_XML_TAG_FORMAT_CLOSE = " </relation>\n",
            BASE_XML_TAG_FORMAT_MEMBER = "  <member type=\"%s\" ref=\"%d\" role=\"%s\"/>\n";
    private final static OSMType type = OSMType.relation;

    protected final ArrayList<OSMRelationMember> members = new ArrayList<>();

    public class OSMRelationMember {
        public final OSMEntity member;
        public final String role;

        public OSMRelationMember(OSMEntity memberEntity, @NotNull String memberRole) {
            role = memberRole;
            member = memberEntity;
        }
    }

    public static OSMRelation create() {
        return new OSMRelation(acquire_new_id());
    }

    public OSMRelation(long id) {
        osm_id = id;
    }

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        if(members.size() == 0) {
            return null;
        }

        Region member0BoundingBox = members.get(0).member.getBoundingBox();
        Region combinedBoundingBox = new Region(member0BoundingBox.origin, member0BoundingBox.extent);
        for(OSMRelationMember member: members) {
            combinedBoundingBox.combinedBoxWithRegion(member.member.getBoundingBox());
        }
        return combinedBoundingBox;
    }

    @Override
    public Point getCentroid() {
        Region boundingBox = getBoundingBox();
        return new Point(0.5 * (boundingBox.origin.latitude + boundingBox.extent.latitude), 0.5 * (boundingBox.origin.longitude + boundingBox.extent.longitude));
    }

    @Override
    public String toString() {
        int tagCount = tags != null ? tags.size() : 0, memberCount = members.size();

        if(tagCount + memberCount > 0) {
            StringBuilder xml = new StringBuilder(tagCount * 32 + memberCount * 64);
            xml.append(String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id));

            //output members
            for (OSMRelationMember member : members) {
                xml.append(String.format(BASE_XML_TAG_FORMAT_MEMBER, member.member.getType(), member.member.osm_id, escapeForXML(member.role)));
            }

            //and tags (if any)
            if(tagCount > 0) {
                for (HashMap.Entry<String, String> entry : tags.entrySet()) {
                    xml.append(String.format(BASE_XML_TAG_FORMAT_TAG, escapeForXML(entry.getKey()), escapeForXML(entry.getValue())));
                }
            }
            xml.append(BASE_XML_TAG_FORMAT_CLOSE);
            return xml.toString();
        } else {
            return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id);
        }
    }
    public void addMember(OSMEntity member, String role) {
        members.add(new OSMRelationMember(member, role));
    }
}
