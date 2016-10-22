package OSM;

import java.util.HashMap;

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
    public final HashMap<Long, OSMWay> containingWays = new HashMap<>(4);
    public short containingWayCount = 0;

    public OSMNode(final long id) {
        super(id);
    }

    /**
     * Copy constructor
     * @param nodeToCopy
     */
    public OSMNode(final OSMNode nodeToCopy, final Long idOverride) {
        super(nodeToCopy, idOverride);
        if(complete) {
            setCoordinate(nodeToCopy.coordinate);
        }
    }
    @Override
    protected void upgradeToCompleteEntity(final OSMEntity completeEntity) {
        super.upgradeToCompleteEntity(completeEntity);
        setCoordinate(((OSMNode) completeEntity).coordinate);

        //notify the containing ways that this node is now complete
        for(final OSMWay way : containingWays.values()) {
            way.nodeWasMadeComplete(this);
        }
    }
    /**
     * Notifies this node it's been added to the given way's node list
     * @param way
     */
    protected void didAddToWay(final OSMWay way) {
        if(!containingWays.containsKey(way.osm_id)) {
            containingWays.put(way.osm_id, way);
            containingWayCount++;
        }
    }
    /**
     * Notifies this node it's been removed from the given way's node list
     * @param way
     */
    protected void didRemoveFromWay(final OSMWay way) {
        if(containingWays.containsKey(way.osm_id)) {
            containingWays.remove(way.osm_id);
            containingWayCount--;
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

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        if(!complete) {
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
            setTag("wcount", Short.toString(containingWayCount));
            setTag("rcount", Short.toString(containingRelationCount));
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
    public String toString() {
        return String.format("node@%d (id %d): %.01f,%.01f (%s)", hashCode(), osm_id, coordinate.x, coordinate.y, complete ? getTag(OSMEntity.KEY_NAME) : "incomplete");
    }
}
