package OSM;

import com.sun.istack.internal.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 10/15/15.
 */
public class OSMRelation extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <relation id=\"%d\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <relation id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <relation id=\"%s\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <relation id=\"%s\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\">\n",
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
        super(id);
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
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user));
            } else {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id, String.valueOf(visible));
            }

            final StringBuilder xml = new StringBuilder(tagCount * 64 + memberCount * 24 + openTag.length() + BASE_XML_TAG_FORMAT_CLOSE.length());
            xml.append(openTag);

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
            if(version > 0) {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, String .valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user));
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, String .valueOf(visible));
            }
        }
    }
    public void clearMembers() {
        members.clear();
    }

    /**
     * Removes the given member from this relation
     * @param member
     * @return TRUE if removed, FALSE if not found in relation
     */
    public boolean removeMember(final OSMEntity member) {
        OSMRelationMember relationMemberToRemove = null;
        for(final OSMRelationMember containedMember : members) {
            if(member == containedMember.member) {
                relationMemberToRemove = containedMember;
                break;
            }
        }

        if(relationMemberToRemove != null) {
            members.remove(relationMemberToRemove);
            return true;
        }
        return false;
    }
    public void addMember(final OSMEntity member, final String role) {
        members.add(new OSMRelationMember(member, role));
    }
    public List<OSMRelationMember> getMembers() {
        return members;
    }
    public List<OSMRelationMember> getMembers(final String role) {
        ArrayList<OSMRelationMember> matchingMembers = new ArrayList<>(members.size());
        for(OSMRelationMember member : members) {
            if(member.role.equals(role)) {
                matchingMembers.add(member);
            }
        }
        return matchingMembers;
    }
}
