package OSM;

/**
 * Created by nick on 11/20/16.
 */
interface OSMEntityObserver {
    void addReferringEntity(OSMEntity entity);
    void removeReferringEntity(OSMEntity entity);
}
