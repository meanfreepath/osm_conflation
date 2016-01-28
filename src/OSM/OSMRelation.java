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

    public OSMRelation(final long id) {
        super(id);
    }

    /**
     * Copy constructor
     * @param relationToCopy
     * @param memberCopyStrategy
     */
    public OSMRelation(final OSMRelation relationToCopy, final Long idOverride, final MemberCopyStrategy memberCopyStrategy) {
        super(relationToCopy, idOverride);

        //add the nodes
        switch (memberCopyStrategy) {
            case deep: //if deep copying, individually copy the members as well
                for(final OSMRelationMember member : relationToCopy.members) {
                    final OSMEntity clonedMember;
                    if(member.member instanceof OSMNode) {
                        clonedMember = new OSMNode((OSMNode) member.member, null);
                    } else if(member.member instanceof OSMWay) {
                        clonedMember = new OSMWay((OSMWay) member.member, null, memberCopyStrategy);
                    } else {
                        clonedMember = new OSMRelation((OSMRelation) member.member, null, memberCopyStrategy);
                    }
                    addMember(clonedMember, member.role);
                }
                break;
            case shallow:
                members.addAll(relationToCopy.getMembers());
                break;
            case none:
                break;
        }
    }

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        //generate the combined bounding box from the members' bounding boxes
        Region combinedBoundingBox = null, curBoundingBox;
        for(OSMRelationMember member: members) {
            curBoundingBox = member.member.getBoundingBox();
            if(curBoundingBox == null) {
                continue;
            } else if(combinedBoundingBox == null) {
                combinedBoundingBox = new Region(curBoundingBox.origin, curBoundingBox.extent);
                continue;
            }
            combinedBoundingBox.combinedBoxWithRegion(curBoundingBox);
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
            for (final OSMRelationMember member : members) {
                xml.append(String.format(BASE_XML_TAG_FORMAT_MEMBER, member.member.getType(), member.member.osm_id, escapeForXML(member.role)));
            }

            //and tags (if any)
            if(tagCount > 0) {
                for (final HashMap.Entry<String, String> entry : tags.entrySet()) {
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
        for(final OSMRelationMember member : members) {
            member.member.didRemoveFromRelation(this);
        }
        members.clear();
    }

    public int indexOfMember(final OSMEntity entity) {
        int index = 0;
        for(final OSMRelationMember member : members) {
            if(member.member == entity) {
                return index;
            }
            index++;
        }
        return -1;
    }
    /**
     * Checks whether the given node is a member of this way
     * @param entity
     * @return
     */
    public boolean containsMember(final OSMEntity entity) {
        return indexOfMember(entity) >= 0;
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
            if(members.remove(relationMemberToRemove)) {
                relationMemberToRemove.member.didRemoveFromRelation(this);
                return true;
            }
        }
        return false;
    }
    public boolean addMember(final OSMEntity member, final String role) {
        if(members.add(new OSMRelationMember(member, role))) {
            member.didAddToRelation(this);
            return true;
        }
        return false;
    }
    /**
     * Replace the old member with the new member
     * @param oldEntity
     * @param newEntity
     */
    public void replaceMember(final OSMEntity oldEntity, final OSMEntity newEntity) {
        final int memberIndex = indexOfMember(oldEntity);
        if(memberIndex >= 0) {
            final OSMRelationMember oldMember = members.get(memberIndex);
            final OSMRelationMember newMember = new OSMRelationMember(newEntity, oldMember.role);
            members.set(memberIndex, newMember);

            oldMember.member.didRemoveFromRelation(this);
            newMember.member.didAddToRelation(this);

            boundingBox = null; //invalidate the bounding box
        }
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
