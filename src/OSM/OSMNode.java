package OSM;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by nick on 10/15/15.
 */
public class OSMNode extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_EMPTY_METADATA = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\"/>\n",
            BASE_XML_TAG_FORMAT_OPEN = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\">\n",
            BASE_XML_TAG_FORMAT_OPEN_METADATA = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" visible=\"%s\" timestamp=\"%s\" version=\"%d\" changeset=\"%d\" uid=\"%d\" user=\"%s\">\n",
            BASE_XML_TAG_FORMAT_CLOSE = " </node>\n";
    private final static OSMType type = OSMType.node;
    private double lat, lon;
    private Point coordinate;
    public HashMap<Long, OSMWay> containingWays = null;
    public short containingWayCount = -1;

    public static OSMNode create() {
        return new OSMNode(acquire_new_id());
    }
    public static OSMNode create(Point point) {
        OSMNode node = new OSMNode(acquire_new_id());
        node.lat = point.latitude;
        node.lon = point.longitude;
        node.coordinate = point;
        return node;
    }
    public static OSMNode create(double latitude, double longitude) {
        OSMNode node = new OSMNode(acquire_new_id());
        node.lat = latitude;
        node.lon = longitude;
        node.coordinate = new Point(latitude, longitude);
        return node;
    }

    /**
     * Resets this node's containing way map
     */
    public void resetContainingWays() {
        containingWays = null;
        containingWayCount = 0;
    }
    /**
     * Adds the given way to this node's containingWays array
     * @param way
     */
    public void addContainingWay(final OSMWay way) {
        if(containingWays == null) {
            containingWays = new HashMap<>(4);
            containingWayCount = 0;
        }
        if(!containingWays.containsKey(way.osm_id)) {
            containingWays.put(way.osm_id, way);
            containingWayCount++;
            setTag("icount", Short.toString(containingWayCount));
        }
    }
    public OSMNode(long id) {
        super(id);
    }

    public double getLat() {
        return lat;
    }
    public double getLon() {
        return lon;
    }
    public void setLat(double lat) {
        this.lat = lat;
        boundingBox = null; //invalidate the bounding box
    }
    public void setLon(double lon) {
        this.lon = lon;
        boundingBox = null; //invalidate the bounding box
    }
    public void setCoordinate(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
        coordinate = new Point(lat, lon);
        boundingBox = null; //invalidate the bounding box
    }
    public void setCoordinate(Point coordinate) {
        this.lat = coordinate.latitude;
        this.lon = coordinate.longitude;
        this.coordinate = coordinate;
        boundingBox = null; //invalidate the bounding box
    }

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        if(boundingBox == null) {
            boundingBox = new Region(lat, lon, 0.0, 0.0);
        }
        return boundingBox;
    }

    @Override
    public Point getCentroid() {
        return coordinate;
    }

    @Override
    public String toString() {
        if(tags != null) {
            final String openTag;
            if(version > 0) {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN_METADATA, osm_id, lat, lon, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user));
            } else {
                openTag = String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id, lat, lon, String.valueOf(visible));
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
                return String.format(BASE_XML_TAG_FORMAT_EMPTY_METADATA, osm_id, lat, lon, String.valueOf(visible), timestamp, version, changeset, uid, escapeForXML(user));
            } else {
                return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, lat, lon, String.valueOf(visible));
            }
        }
    }
    public void setTag(String name, String value) {
        switch (name) {
            case KEY_LATITUDE:
                lat = Double.parseDouble(value);
                break;
            case KEY_LONGITUDE:
                lon = Double.parseDouble(value);
                break;
            default:
                super.setTag(name, value);
                break;
        }
    }
}
