package OSM;

import com.sun.istack.internal.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 10/15/15.
 */
public class OSMRelation extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <relation id=\"%d\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <relation id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\" action=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <relation id=\"%s\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <relation id=\"%s\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\" action=\"%s\">\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </relation>\n",
            BASE_XML_TAG_FORMAT_MEMBER = "  <member type=\"%s\" ref=\"%d\" role=\"%s\"/>\n";
    private final static OSMType type = OSMType.relation;

    protected final ArrayList<OSMRelationMember> members = new ArrayList<>();
    protected int completedMemberCount = 0;

    public static class OSMRelationMember {
        public final OSMEntity member;
        public final String role;

        public OSMRelationMember(OSMEntity memberEntity, @NotNull String memberRole) {
            role = memberRole;
            member = memberEntity;
        }
        public String toString() {
            return String.format("RelMem@%d: role \"%s\" member %s", hashCode(), role, member);
        }
    }

    public OSMRelation(final long id) {
        super(id);
    }

    /**
     * Copy constructor
     * @param relationToCopy
     */
    public OSMRelation(final OSMRelation relationToCopy, final Long idOverride) {
        super(relationToCopy, idOverride);
    }
    @Override
    protected void downgradeToIncompleteEntity() {
        super.downgradeToIncompleteEntity();
        final List<OSMRelationMember> membersToRemove = new ArrayList<>(members);
        for(final OSMRelationMember member : membersToRemove) {
            removeMember(member.member);
        }
        completedMemberCount = 0;
    }
    protected void copyMembers(final List<OSMRelationMember> membersToCopy) {
        if(complete) {
            for(final OSMRelationMember member : membersToCopy) {
                addMember(member.member, member.role);
                if(member.member.isComplete()) {
                    completedMemberCount++;
                }
            }
        }
    }
    protected void memberWasMadeComplete(final OSMEntity memberEntity) {
        completedMemberCount++;
    }
    public boolean areAllMembersComplete() {
        return completedMemberCount == members.size();
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
    public String toOSMXML() {
        if(debugEnabled) {
            setTag("rcount", Short.toString(containingRelationCount));
            if(osm_id < 0) {
                setTag("origid", Long.toString(osm_id));
            }
        }

        final int tagCount = tags != null ? tags.size() : 0, memberCount = members.size();

        if(tagCount + memberCount > 0) {
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), action != ChangeAction.none ? action.name() : "");
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
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, String .valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), action != ChangeAction.none ? action.name() : "");
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, String .valueOf(visible));
            }
        }
    }
    public void clearMembers() {
        if(members.size() > 0) {
            markAsModified();
        }

        for(final OSMRelationMember member : members) {
            member.member.didRemoveFromRelation(this);
        }
        members.clear();
        completedMemberCount = 0;
    }

    /**
     * Gets the OSMRelationMember object for the given entity
     * @param entity
     * @return
     */
    public OSMRelationMember getMemberForEntity(final OSMEntity entity) {
        int index = indexOfMember(entity);
        if(index < 0) {
            return null;
        }
        return members.get(index);
    }

    /**
     * Get the index of the membership object for the given entity
     * @param entity
     * @return the index, or -1 if entity isn't a member of this relation
     */
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
                markAsModified();
                if(relationMemberToRemove.member.isComplete()) {
                    completedMemberCount--;
                }
                relationMemberToRemove.member.didRemoveFromRelation(this);
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a member to the end of the relation list
     * @param member
     * @param role
     * @return
     */
    public boolean addMember(final OSMEntity member, final String role) {
        return addMemberInternal(member, role, members.size());
    }
    /**
     * Add a new member before the given existing member in the member list
     * @param member
     * @param role
     * @param existingMember
     * @return
     */
    public boolean insertBeforeMember(final OSMEntity member, final String role, final OSMEntity existingMember) {
        final int existingMemberIndex = existingMember != null ? indexOfMember(existingMember) : 0; //default to the first if no existingMember provided
        return addMemberInternal(member, role, existingMemberIndex);
    }
    /**
     * Add a new member after the given existing member in the member list
     * @param member
     * @param role
     * @param existingMember
     * @return
     */
    public boolean insertAfterMember(final OSMEntity member, final String role, final OSMEntity existingMember) {
        final int existingMemberIndex = existingMember != null ? indexOfMember(existingMember) : members.size() - 2; //default to the last if no existingMember provided
        return addMemberInternal(member, role, existingMemberIndex + 1);
    }
    private boolean addMemberInternal(final OSMEntity member, final String role, final int index) {
        members.add(index, new OSMRelationMember(member, role));
        if(member.isComplete()) {
            completedMemberCount++;
        }
        member.didAddToRelation(this);
        markAsModified();
        return true;
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
            if(oldMember.member.isComplete()) {
                completedMemberCount--;
            }
            if(newMember.member.isComplete()) {
                completedMemberCount++;
            }

            oldMember.member.didRemoveFromRelation(this);
            newMember.member.didAddToRelation(this);

            boundingBox = null; //invalidate the bounding box
            markAsModified();
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
    /**
     * Checks whether this relation is valid
     * @return true if valid, FALSE if not
     */
    public boolean isValid() {
        final String relationType = getTag(OSMEntity.KEY_TYPE);
        if(relationType == null) {
            return false;
        }
        switch (relationType) {
            case "restriction":
                //first validate the restriction
                final List<OSMRelation.OSMRelationMember> viaEntities = getMembers("via");
                final List<OSMRelation.OSMRelationMember> fromWays = getMembers("from");
                final List<OSMRelation.OSMRelationMember> toWays = getMembers("to");

                //restrictions should only have 1 each of "from", "via", and "to"  members
                boolean restrictionIsValid = viaEntities.size() == 1 && fromWays.size() == 1 && toWays.size() == 1;
                //and the "from" and "to" members must be ways, and the "via" member must be a node or way
                if(!restrictionIsValid) {
                    return false;
                }
                restrictionIsValid = fromWays.get(0).member instanceof OSMWay && toWays.get(0).member instanceof OSMWay && (viaEntities.get(0).member instanceof OSMNode || viaEntities.get(0).member instanceof OSMWay);
                if(!restrictionIsValid) {
                    return false;
                }

                //check the intersection of the members
                final OSMWay fromWay = (OSMWay) fromWays.get(0).member, toWay = (OSMWay) toWays.get(0).member;
                final OSMEntity viaEntity = viaEntities.get(0).member;
                if(viaEntity instanceof OSMNode) { //if "via" is a node, the to and from ways must start or end on it
                    restrictionIsValid = (fromWay.getFirstNode() == viaEntity || fromWay.getLastNode() == viaEntity) && (toWay.getFirstNode() == viaEntity || toWay.getLastNode() == viaEntity);
                } else if(viaEntity instanceof OSMWay) { //if "via" is a way, the to and from ways' first/last nodes must match its first/last/nodes
                    final OSMWay viaWay = (OSMWay) viaEntities.get(0).member;
                    final OSMNode viaFirstNode = viaWay.getFirstNode(), viaLastNode = viaWay.getLastNode();
                    restrictionIsValid = (viaFirstNode == fromWay.getFirstNode() || viaFirstNode == fromWay.getLastNode() || viaFirstNode == toWay.getFirstNode() || viaFirstNode == toWay.getLastNode()) &&
                            (viaLastNode == fromWay.getFirstNode() || viaLastNode == fromWay.getLastNode() || viaLastNode == toWay.getFirstNode() || viaLastNode == toWay.getLastNode());
                } else {
                    restrictionIsValid = false;
                }

                return restrictionIsValid;
            default:
                return false;
        }
    }
    /**
     * Handle the necessary assignment/reordering of any members after a contained way is split into multiple ways
     * @param originalWay
     * @param allSplitWays
     */
    public void handleMemberWaySplit(final OSMWay originalWay, final OSMWay[] allSplitWays, final boolean wasValidBeforeSplit) {
        //get the relation's type, using the default handling if not set
        final String relationType = hasTag(OSMEntity.KEY_TYPE) ? getTag(OSMEntity.KEY_TYPE) : "";
        assert relationType != null;
        switch (relationType) {
            case "restriction": //turn restriction: use the way that contains the "via" node
                //if the restriction is valid, check if the new way should be added to it or not
                if(wasValidBeforeSplit) {
                    final OSMEntity viaEntity = getMembers("via").get(0).member;
                    //"via" member is a node
                    if(viaEntity instanceof OSMNode && !originalWay.getNodes().contains(viaEntity)) {
                        //check which splitWay contains the via node, and update the relation membership as needed
                        for(final OSMWay splitWay : allSplitWays) {
                            if(splitWay != originalWay && splitWay.getNodes().contains(viaEntity)) {
                                replaceMember(originalWay, splitWay);
                                break;
                            }
                        }
                    } else if(viaEntity instanceof OSMWay) { //"via" member is a originalWay
                        final OSMWay viaWay = (OSMWay) viaEntity;

                        //check which splitWay intersects the "via" way, replace the old originalWay with it in the relation
                        for(final OSMWay splitWay : allSplitWays) {
                            if(splitWay != originalWay && splitWay.getNodes().contains(splitWay.getFirstNode()) || splitWay.getNodes().contains(splitWay.getLastNode())) {
                                replaceMember(originalWay, splitWay);
                                break;
                            }
                        }
                    }
                } else { //if the restriction is invalid, just add the new originalWay to it and log a warning
                    final OSMRelationMember member = getMemberForEntity(originalWay);
                    for(final OSMWay splitWay : allSplitWays) {
                        if (splitWay != originalWay) {
                            addMember(splitWay, member.role);
                        }
                    }
                }
            case "turnlanes:turns":
                break;
            default: //all other types: just add the new ways to the relation, in the correct order if possible
                //get the order of originalWay in the relation
                final int index = indexOfMember(originalWay);
                final OSMRelation.OSMRelationMember originalWayMember = getMemberForEntity(originalWay);

                //determine the order in which we should add the new ways to the relation, to ensure they are continuous
                //check the previous member to determine the order
                Boolean addForward = null;
                if(index > 0) {
                    final OSMRelation.OSMRelationMember previousMember = members.get(index - 1);
                    if(previousMember.member instanceof OSMWay) {
                        final OSMWay prevMember = (OSMWay) previousMember.member;
                        //if the previous member connects with the first node of originalWay, the direction is forward.  If last, the direction is backward
                        if(prevMember.getLastNode() == originalWay.getFirstNode() || prevMember.getFirstNode() == originalWay.getFirstNode()) {
                            addForward = true;
                        } else if(prevMember.getFirstNode() == originalWay.getLastNode() || prevMember.getLastNode() == originalWay.getLastNode()) {
                            addForward = false;
                        }
                    }
                }

                //if unable to determine the order by checking the previous member, try checking the next member
                if(addForward == null && index < members.size() - 1) {
                    final OSMRelation.OSMRelationMember nextMember = members.get(index + 1);
                    if(nextMember.member instanceof OSMWay) {
                        final OSMWay nexMember = (OSMWay) nextMember.member;
                        //if the next member connects with the last node of originalWay, the direction is forward
                        if(nexMember.getFirstNode() == originalWay.getLastNode() || nexMember.getLastNode() == originalWay.getLastNode()) {
                            addForward = true;
                        } else if(nexMember.getFirstNode() == originalWay.getFirstNode() || nexMember.getLastNode() == originalWay.getFirstNode()) {
                            addForward = false;
                        }
                    }
                }

                //and add all the newly-split ways to the relation
                if(addForward == null || addForward) { //add in the forward direction
                    boolean hitOriginal = false;
                    for (final OSMWay splitWay : allSplitWays) {
                        if(splitWay == originalWay) { //don't re-add the originalWay
                            hitOriginal = true;
                            continue;
                        }
                        //System.out.println("Adding new originalWay FORWARD " + (hitOriginal ? "AFTER" : "BEFORE") + ": " + splitWay.getTag("name") + " to relation " + getTag("name"));
                        if(hitOriginal) {
                            insertAfterMember(splitWay, originalWayMember.role, originalWay);
                        } else {
                            insertBeforeMember(splitWay, originalWayMember.role, originalWay);
                        }
                    }
                } else { //add in the backward direction
                    final List<OSMWay> splitWaysForRelation = new ArrayList<>(allSplitWays.length);
                    Collections.addAll(splitWaysForRelation, allSplitWays);
                    Collections.reverse(splitWaysForRelation);

                    boolean hitOriginal = false;
                    for (final OSMWay splitWay : splitWaysForRelation) {
                        if(splitWay == originalWay) { //don't re-add the originalWay
                            hitOriginal = true;
                            continue;
                        }
                        //System.out.println("Adding new originalWay BACKWARD "  + (hitOriginal ? "AFTER" : "BEFORE") + splitWay.getTag("name") + " to relation " + getTag("name"));
                        if(hitOriginal) {
                            insertBeforeMember(splitWay, originalWayMember.role, originalWay);
                        } else {
                            insertAfterMember(splitWay, originalWayMember.role, originalWay);
                        }
                    }
                }
                break;
        }
    }
    public String toString() {
        return String.format("relation@%d (id %d): %d/%d members (%s)", hashCode(), osm_id, completedMemberCount, members.size(), complete ? getTag(OSMEntity.KEY_NAME) : "incomplete");
    }
}
