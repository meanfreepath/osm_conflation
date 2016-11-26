package OSM;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Created by nick on 10/15/15.
 */
public class OSMNode extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"%s/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"%s>\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </node>\n";
    private final static OSMType type = OSMType.node;
    private Point coordinate;
    private HashMap<Long, WeakReference<OSMWay>> containingWays = null;

    public OSMNode(final long id) {
        super(id);
    }

    /**
     * Copy constructor
     * @param nodeToCopy
     */
    public OSMNode(final OSMNode nodeToCopy, final Long idOverride) {
        super(nodeToCopy, idOverride);
        if(complete != CompletionStatus.incomplete) {
            setCoordinate(nodeToCopy.coordinate);
        }
    }
    @Override
    protected void upgradeCompletionStatus(final OSMEntity completeEntity) {
        super.upgradeCompletionStatus(completeEntity);
        setCoordinate(((OSMNode) completeEntity).coordinate);

        //notify the containing ways that this node is now complete
        if(containingWays != null) {
            for (final OSMWay way : getContainingWays().values()) {
                way.nodeWasMadeComplete(this);
            }
        }
    }
    public void setCoordinate(final double x, final double y) {
        if(this.coordinate != null) { //mark as modified if changing (vs initial assignment)
            markAsModified();
        }
        coordinate = new Point(x, y);
        boundingBox = null; //invalidate the bounding box
    }
    public void setCoordinate(final Point coordinate) {
        if(this.coordinate != null && Point.distance(this.coordinate, coordinate) > Double.MIN_VALUE) { //mark as modified if changing (vs initial assignment)
            markAsModified();
        }
        this.coordinate = new Point(coordinate);
        boundingBox = null; //invalidate the bounding box
    }
    public HashMap<Long, OSMWay> getContainingWays() {
        if(containingWays == null) {
            return new HashMap<>();
        }
        final HashMap<Long, OSMWay> activeWays = new HashMap<>(containingWays.size());
        final Iterator<WeakReference<OSMWay>> wayIterator = containingWays.values().iterator();
        WeakReference<OSMWay> curWayRef;
        OSMWay containingWay;
        while(wayIterator.hasNext()) {
            curWayRef = wayIterator.next();
            containingWay = curWayRef.get();
            if(containingWay != null) {
                activeWays.put(containingWay.osm_id, containingWay);
            } else { //remove any expired containing ways on the fly
                wayIterator.remove();
            }
        }
        return activeWays;
    }
    public short getContainingWayCount() {
        short containingWayCount = 0;
        if(containingWays != null) {
            for (final WeakReference<OSMWay> containingWay : containingWays.values()) {
                if (containingWay.get() != null) {
                    containingWayCount++;
                }
            }
        }
        return containingWayCount;
    }

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        if(complete == CompletionStatus.incomplete) {
            return null;
        }
        if(boundingBox == null) {
            boundingBox = new Region(coordinate.x, coordinate.y, 0.0, 0.0);
        }
        return boundingBox;
    }

    @Override
    public Point getCentroid() {
        return coordinate;
    }

    @Override
    public String toOSMXML() {
        if(debugEnabled) {
            setTag("wcount", Short.toString(getContainingWayCount()));
            setTag("rcount", Short.toString(getContainingRelationCount()));
            if(osm_id < 0) {
                setTag("origid", Long.toString(osm_id));
            }
        }
        final LatLon latLonCoordinate = SphericalMercator.mercatorToLatLon(coordinate);
        if(tags != null) {
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, latLonCoordinate.latitude, latLonCoordinate.longitude, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), actionTagAttribute(action));
            } else {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id, latLonCoordinate.latitude, latLonCoordinate.longitude, String.valueOf(visible));
            }
            final StringBuilder xml = new StringBuilder(tags.size() * 64 + openTag.length() + BASE_XML_TAG_FORMAT_CLOSE.length());
            xml.append(openTag);
            for (HashMap.Entry<String, String> entry : tags.entrySet()) {
                xml.append(String.format(BASE_XML_TAG_FORMAT_TAG, escapeForXML(entry.getKey()), escapeForXML(entry.getValue())));
            }
            xml.append(BASE_XML_TAG_FORMAT_CLOSE);
            return xml.toString();
        } else {
            if(version > 0) {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, latLonCoordinate.latitude, latLonCoordinate.longitude, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user), actionTagAttribute(action));
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, latLonCoordinate.latitude, latLonCoordinate.longitude, String.valueOf(visible));
            }
        }
    }

    @Override
    public void didAddToEntity(OSMEntity entity) {
        if(entity instanceof OSMWay) {
            final OSMWay way = (OSMWay) entity;
            if(containingWays == null) { //lazy init the containing ways
                containingWays = new HashMap<>(4);
            }
            final WeakReference<OSMWay> existingWayRef = containingWays.get(way.osm_id);
            if(existingWayRef == null || existingWayRef.get() == null) {
                containingWays.put(way.osm_id, new WeakReference<>(way));
            }
        } else if(entity instanceof OSMRelation) {
            addContainingRelation((OSMRelation) entity);
        }
    }

    @Override
    public void didRemoveFromEntity(OSMEntity entity, boolean entityWasDeleted) {
        if(entity instanceof OSMWay) { //remove the way from the containedWays list
            final OSMWay way = (OSMWay) entity;
            if(containingWays != null && containingWays.containsKey(way.osm_id)) {
                containingWays.remove(way.osm_id);
            }
        } else if(entity instanceof OSMRelation) {
            removeContainingRelation((OSMRelation) entity);
        }
    }

    @Override
    public void containedEntityWasDeleted(OSMEntity entity) {
        //nodes can't contain other entities
    }

    @Override
    public boolean didDelete(OSMEntitySpace fromSpace) {
        //remove this node from any containing ways
        for(final OSMWay way : getContainingWays().values()) {
            way.containedEntityWasDeleted(this);
            didRemoveFromEntity(way, true);
        }
        return super.didDelete(fromSpace);
    }

    @Override
    public String toString() {
        if(complete != CompletionStatus.incomplete) {
            return String.format("node@%d (id %d): %.01f,%.01f (%s): [%s/%s]", hashCode(), osm_id, coordinate.x, coordinate.y, getTag(OSMEntity.KEY_NAME), complete, action);
        } else {
            return String.format("node@%d (id %d): [%s]", hashCode(), osm_id, complete);
        }
    }
}
