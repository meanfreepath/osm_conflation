package OSM;

import com.sun.istack.internal.NotNull;

import java.util.*;

/**
 * Created by nick on 10/15/15.
 */
public class OSMRelation extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <relation id=\"%d\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <relation id=\"%d\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"%s/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <relation id=\"%s\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <relation id=\"%s\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"%s>\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </relation>\n",
            BASE_XML_TAG_FORMAT_MEMBER = "  <member type=\"%s\" ref=\"%d\" role=\"%s\"/>\n";
    private final static OSMType type = OSMType.relation;

    protected final ArrayList<OSMRelationMember> members = new ArrayList<>();

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
     * @param relationToCopy the relation to copy
     */
    public OSMRelation(final OSMRelation relationToCopy, final Long idOverride) {
        super(relationToCopy, idOverride);
    }

    /**
     * Gets the completion counts of this relation's members
     * @return array of completion counts, in this order: incomplete, partially complete, fully complete members
     */
    public int[] getCompletedMemberCounts() {
        int completedMemberCount = 0, partiallyCompleteMemberCount = 0, incompleteMemberCount = 0;
        for(final OSMRelationMember member : members) {
            if(member.member instanceof OSMNode) {
                if(member.member.complete != CompletionStatus.incomplete) {
                    completedMemberCount++;
                } else {
                    incompleteMemberCount++;
                }
            } else if(member.member.complete == CompletionStatus.membersComplete) {
                completedMemberCount++;
            } else if(member.member.complete == CompletionStatus.memberList) {
                partiallyCompleteMemberCount++;
            } else {
                incompleteMemberCount++;
            }
        }
        return new int[]{incompleteMemberCount, partiallyCompleteMemberCount, completedMemberCount};
    }
    @Override
    protected void downgradeToIncompleteEntity() {
        super.downgradeToIncompleteEntity();

        //flush the members
        final ListIterator<OSMRelationMember> memberListIterator = members.listIterator();
        while (memberListIterator.hasNext()) {
            final OSMRelationMember member = memberListIterator.next();
            memberListIterator.remove();
            member.member.didRemoveFromEntity(this, false);
        }
    }
    public void copyMembers(final List<OSMRelationMember> membersToCopy) {
        if(complete != CompletionStatus.incomplete) {
            for(final OSMRelationMember member : membersToCopy) {
                addMemberInternal(member.member, member.role, members.size(), false);
            }
            updateCompletionStatus();
        }
    }
    protected void memberWasMadeComplete(final OSMEntity memberEntity) {
        updateCompletionStatus();
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
        return new Point(0.5 * (boundingBox.origin.x + boundingBox.extent.x), 0.5 * (boundingBox.origin.y + boundingBox.extent.y));
    }

    @Override
    public String toOSMXML() {
        if(debugEnabled) {
            setTag("rcount", Short.toString(getContainingRelationCount()));
            if(osm_id < 0) {
                setTag("origid", Long.toString(osm_id));
            }
        }

        final int tagCount = tags != null ? tags.size() : 0, memberCount = members.size();

        if(tagCount + memberCount > 0) {
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), actionTagAttribute(action));
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
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, String .valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), actionTagAttribute(action));
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, String .valueOf(visible));
            }
        }
    }

    @Override
    public void didAddToEntity(OSMEntity entity) {
        if(entity instanceof OSMRelation) { //relations can only be added to other relations
            addContainingRelation((OSMRelation) entity);
        }
    }
    @Override
    public void didRemoveFromEntity(OSMEntity entity, boolean entityWasDeleted) {
        if(entity instanceof OSMRelation) {
            removeContainingRelation((OSMRelation) entity);
        }
    }
    @Override
    public void containedEntityWasDeleted(OSMEntity entity) {
        removeMember(entity, Integer.MAX_VALUE); //remove all instances of the entity from the member list
    }

    @Override
    public boolean didDelete(OSMEntitySpace fromSpace) {
        if(members.size() > 0) {
            markAsModified();
        }

        for(final OSMRelationMember member : members) {
            member.member.didRemoveFromEntity(this, true);
        }
        members.clear();
        return super.didDelete(fromSpace);
    }

    /**
     * Gets the OSMRelationMember object(s) for the given entity
     * @param entity the entity to search for
     * @return array of OSMRelationMembers containing entity
     */
    public OSMRelationMember[] getMembersForEntity(final OSMEntity entity) {
        int indexes[] = getMembershipIndexes(entity);
        if(indexes == null) {
            return null;
        }
        List<OSMRelationMember> entityMembers = new ArrayList<>(indexes.length);
        for(int idx : indexes) {
            entityMembers.add(members.get(idx));
        }
        return entityMembers.toArray(new OSMRelationMember[entityMembers.size()]);
    }

    /**
     * Get the index(es) of the membership object for the given entity
     * @param entity the entity to search for
     * @return the index(es), or null if entity isn't a member of this relation
     */
    public int[] getMembershipIndexes(final OSMEntity entity) {
        int index = 0;
        List<Integer> indexes = new ArrayList<>(8);
        for(final OSMRelationMember member : members) {
            if(member.member == entity) {
                indexes.add(index);
            }
            index++;
        }
        if(indexes.size() > 0) {
            int[] retVal = new int[indexes.size()];
            int i = 0;
            for(Integer idx : indexes) {
                retVal[i++] = idx;
            }
            return retVal;
        } else {
            return null;
        }
    }
    /**
     * Checks whether the given node is a member of this way
     * @param entity the entity to search for
     * @return true if entity has at least 1 membership, false if not
     */
    public boolean containsMember(final OSMEntity entity) {
        return getMembershipIndexes(entity) != null;
    }

    /**
     * Removes the given member from this relation
     * @param member the entity to be removed
     * @param maxToRemove the max number of memberships to remove (i.e. if an entity has multiple member instances)
     * @return TRUE if removed, FALSE if not found in relation
     */
    public boolean removeMember(final OSMEntity member, final int maxToRemove) {
        final List<OSMRelationMember> relationMembersToRemove = new ArrayList<>(4);
        int numRemoved = 0;
        for(final OSMRelationMember containedMember : members) {
            if(member == containedMember.member) {
                relationMembersToRemove.add(containedMember);
                if(++numRemoved >= maxToRemove) {
                    break;
                }
            }
        }

        for(final OSMRelationMember relationMemberToRemove : relationMembersToRemove) {
            if(members.remove(relationMemberToRemove)) {
                markAsModified();
                relationMemberToRemove.member.didRemoveFromEntity(this, false);
                return true;
            }
        }
        return false;
    }

    /**
     * Adds an entity to the end of the relation list
     * @param entity the entity to add
     * @param role its role in the relation
     * @return true if added, false otherwise
     */
    public boolean addMember(final OSMEntity entity, final String role) {
        final boolean status = addMemberInternal(entity, role, members.size(), true);
        if(status) {
            updateCompletionStatus();
        }
        return status;
    }
    /**
     * Add a new entity before the given existing member in the member list
     * @param entity the entity to add
     * @param role its role in the relation
     * @param existingMember the existing member object
     * @return true if added, false if not (i.e. existingMember not actually a member of this relation)
     */
    public boolean insertBeforeMember(final OSMEntity entity, final String role, final OSMRelationMember existingMember) {
        final int existingMemberIndex = members.indexOf(existingMember);
        if(existingMemberIndex < 0) {
            return false;
        }
        final boolean status = addMemberInternal(entity, role, existingMemberIndex, true);
        if(status) {
            updateCompletionStatus();
        }
        return status;
    }
    /**
     * Add a new member after the given existing member in the member list
     * @param entity the entity to add
     * @param role its role in the relation
     * @param existingMember the existing member object
     * @return true if added, false if not (i.e. existingMember not actually a member of this relation)
     */
    public boolean insertAfterMember(final OSMEntity entity, final String role, final OSMRelationMember existingMember) {
        final int existingMemberIndex = members.indexOf(existingMember);
        if(existingMemberIndex < 0) {
            return false;
        }
        final boolean status = addMemberInternal(entity, role, existingMemberIndex + 1, true);
        if(status) {
            updateCompletionStatus();
        }
        return status;
    }
    private boolean addMemberInternal(final OSMEntity member, final String role, final int index, final boolean markAsModified) {
        members.add(index, new OSMRelationMember(member, role));
        member.didAddToEntity(this);
        if(markAsModified) {
            markAsModified();
        }
        return true;
    }
    private void updateCompletionStatus() {
        boolean allMembersComplete = complete.compareTo(CompletionStatus.memberList) >= 0 && getCompletedMemberCounts()[2] == members.size();
        complete = allMembersComplete ? CompletionStatus.membersComplete : CompletionStatus.memberList;
    }
    /**
     * Replaces all memberships of the old entity with the new entity
     * @param oldEntity the entity to replace
     * @param newEntity the entity to replate oldEntity with
     */
    public int replaceEntityMemberships(final OSMEntity oldEntity, final OSMEntity newEntity) {
        final ListIterator<OSMRelationMember> memberListIterator = members.listIterator();
        int replaceCount = 0;
        while(memberListIterator.hasNext()) {
            final OSMRelationMember oldMember = memberListIterator.next();
            if (oldMember.member == oldEntity) {
                final OSMRelationMember newMember = new OSMRelationMember(newEntity, oldMember.role);
                memberListIterator.set(newMember);

                oldMember.member.didRemoveFromEntity(this, false);
                newMember.member.didAddToEntity(this);

                boundingBox = null; //invalidate the bounding box
                markAsModified();
                updateCompletionStatus();

                replaceCount++;
            }
        }
        return replaceCount;
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
                final List<OSMRelation.OSMRelationMember> viaEntities = getMembers(KEY_VIA);
                final List<OSMRelation.OSMRelationMember> fromWays = getMembers(KEY_FROM);
                final List<OSMRelation.OSMRelationMember> toWays = getMembers(KEY_TO);

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
    public void handleMemberWaySplit(final OSMWay originalWay, final List<OSMNode> originalNodeList, final OSMWay[] allSplitWays, final boolean wasValidBeforeSplit) {
        //get the relation's type, using the default handling if not set
        final String relationType = hasTag(KEY_TYPE) ? getTag(KEY_TYPE) : "";
        assert relationType != null;
        switch (relationType) {
            case "restriction": //turn restriction: use the way that contains the "via" node
                //if the restriction is valid, check if the new way should be added to it or not
                if(wasValidBeforeSplit || complete != CompletionStatus.membersComplete) { //TODO: ideally include an "incomplete" flag in the original validity check
                    final OSMEntity viaEntity = getMembers(KEY_VIA).get(0).member;
                    //"via" member is a node
                    if(viaEntity instanceof OSMNode && !originalWay.getNodes().contains(viaEntity)) {
                        //check which splitWay contains the via node, and update the relation membership as needed
                        for(final OSMWay splitWay : allSplitWays) {
                            if(splitWay.complete == CompletionStatus.membersComplete && splitWay != originalWay && splitWay.getNodes().contains(viaEntity)) {
                                replaceEntityMemberships(originalWay, splitWay);
                                break;
                            }
                        }
                    } else if(viaEntity instanceof OSMWay) { //"via" member is a way
                        final OSMWay viaWay = (OSMWay) viaEntity;

                        //check which splitWay intersects the "via" way, replace the old way with it in the relation
                        for(final OSMWay splitWay : allSplitWays) {
                            if(splitWay != originalWay && splitWay.complete == CompletionStatus.membersComplete && (splitWay.getNodes().contains(viaWay.getFirstNode()) || splitWay.getNodes().contains(viaWay.getLastNode()))) {
                                replaceEntityMemberships(originalWay, splitWay);
                                break;
                            }
                        }
                    }
                } else { //if the restriction is invalid, just add the new splitWays to it and log a warning
                    final OSMRelationMember members[] = getMembersForEntity(originalWay);
                    for(final OSMWay splitWay : allSplitWays) {
                        if (splitWay != originalWay) {
                            addMember(splitWay, members[0].role);
                        }
                    }
                }
            case "turnlanes:turns":
                break;
            default: //all other types: just add the new ways to the relation, in the correct order if possible
                final OSMNode originalWayFirstNode = originalNodeList.get(0), originalWayLastNode = originalNodeList.get(originalNodeList.size() - 1);
                final List<OSMRelationMember> relationWays = new ArrayList<>(members.size());
                for(final OSMRelationMember member : members) {
                    if(member.member instanceof OSMWay) {
                        relationWays.add(member);
                    }
                }

                //get the order of the originalWay's membership(s) in the relation, defaulting to adding in the forward direction
                final OSMRelationMember originalWayMemberships[] = getMembersForEntity(originalWay);
                for(final OSMRelationMember originalWayMember : originalWayMemberships) {
                    boolean addForward = true;
                    final int index = relationWays.indexOf(originalWayMember);
                    if(index > 0) { //if not the first way in list: use previous member's first/last nodes to check
                        final OSMWay prevWay = (OSMWay) relationWays.get(index - 1).member;
                        if(originalWayLastNode == prevWay.getFirstNode() ||originalWayLastNode == prevWay.getLastNode()) {
                            addForward = false;
                        } else if(originalWayFirstNode == prevWay.getFirstNode() ||originalWayFirstNode == prevWay.getLastNode()) {
                            addForward = true;
                        }
                    } else {
                        final OSMWay nextWay = (OSMWay) relationWays.get(index + 1).member;
                        if(originalWayLastNode == nextWay.getFirstNode() ||originalWayLastNode == nextWay.getLastNode()) {
                            addForward = true;
                        } else if(originalWayFirstNode == nextWay.getFirstNode() ||originalWayFirstNode == nextWay.getLastNode()) {
                            addForward = false;
                        }
                    }

                    //and add all the newly-split ways to the relation:
                    final List<OSMWay> splitWaysForRelation = new ArrayList<>(allSplitWays.length);
                    Collections.addAll(splitWaysForRelation, allSplitWays);
                    if(!addForward) { //reverse the iteration order if adding in a backward direction
                        Collections.reverse(splitWaysForRelation);
                    }

                    /*System.out.format("SPLIT %s into %d ways, index %d:: %s\n", originalWay.getTag("name"), allSplitWays.length, index, addForward);
                    System.out.format("\tOrigin F/L nodes: %d/%d\n", originalWayFirstNode.osm_id, originalWayLastNode.osm_id);
                    for(final OSMWay splitWay : allSplitWays) {
                        System.out.format("\tWay: %s\n", splitWay);
                    }*/
                    boolean hitOriginal = false;
                    for (final OSMWay splitWay : splitWaysForRelation) {
                        if (splitWay == originalWay) { //don't re-add the originalWay
                            hitOriginal = true;
                            continue;
                        }
                        //System.out.format("\tAdding new splitWay (%d nodes) %s %s: %s to relation %s\n", splitWay.getNodes().size(), addForward ? "FORWARD" : "BACKWARD", hitOriginal ? "AFTER" : "BEFORE", splitWay.getTag("name"), getTag("name"));
                        if (hitOriginal) {
                            insertAfterMember(splitWay, originalWayMember.role, originalWayMember);
                        } else {
                            insertBeforeMember(splitWay, originalWayMember.role, originalWayMember);
                        }
                    }
                }
                break;
        }
    }
    @Override
    public String toString() {
        if(complete == CompletionStatus.incomplete) {
            return String.format("relation@%d (id %d): %s", hashCode(), osm_id, complete);
        } else {
            final int[] completedMemberCounts = getCompletedMemberCounts();
            return String.format("relation@%d (id %d): %d/%d/%d/%d inc/pc/cpl/total members (%s): %s", hashCode(), osm_id, completedMemberCounts[0], completedMemberCounts[1], completedMemberCounts[2], members.size(), getTag(OSMEntity.KEY_NAME), complete);
        }
    }
}
