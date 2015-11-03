package OSM;

import java.util.HashMap;

/**
 * Created by nick on 10/15/15.
 */
public class OSMNode extends OSMEntity {
    private final static String
            BASE_XML_TAG_FORMAT_EMPTY = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" version=\"1\"/>\n",// user=\"$osmuser\" uid=\"$osmid\" visible=\"true\" version=\"1\">\n"];
            BASE_XML_TAG_FORMAT_OPEN = " <node id=\"%d\" lat=\"%.07f\" lon=\"%.07f\" version=\"1\">\n"/* user=\"$osmuser\" uid=\"$osmid\" visible=\"true\" version=\"1\">\n"];*/,
            BASE_XML_TAG_FORMAT_CLOSE = " </node>\n";
    private final static OSMType type = OSMType.node;
    public double lat, lon;

    public static OSMNode create() {
        return new OSMNode(acquire_new_id());
    }
    public static OSMNode create(Point point) {
        OSMNode node = new OSMNode(acquire_new_id());
        node.lat = point.latitude;
        node.lon = point.longitude;
        return node;
    }
    public static OSMNode create(double latitude, double longitude) {
        OSMNode node = new OSMNode(acquire_new_id());
        node.lat = latitude;
        node.lon = longitude;
        return node;
    }
    public OSMNode(long id) {
        osm_id = id;
    }

    @Override
    public OSMType getType() {
        return type;
    }

    @Override
    public Region getBoundingBox() {
        return new Region(lat, lon, 0.0, 0.0);
    }

    @Override
    public Point getCentroid() {
        return new Point(lat, lon);
    }

    @Override
    public String toString() {
        if(tags != null) {
            StringBuilder xml = new StringBuilder(tags.size() * 16);
            xml.append(String.format(BASE_XML_TAG_FORMAT_OPEN, osm_id, lat, lon));
            for (HashMap.Entry<String, String> entry : tags.entrySet()) {
                xml.append(String.format(BASE_XML_TAG_FORMAT_TAG, escapeForXML(entry.getKey()), escapeForXML(entry.getValue())));
            }
            xml.append(BASE_XML_TAG_FORMAT_CLOSE);
            return xml.toString();
        } else {
            return String.format(BASE_XML_TAG_FORMAT_EMPTY, osm_id, lat, lon);
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
