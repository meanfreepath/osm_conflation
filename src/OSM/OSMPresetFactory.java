package OSM;

/**
 * Created by nick on 2/18/16.
 */
public abstract class OSMPresetFactory {
    public static void makePlatform(final OSMEntity entity) {
        entity.setTag(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_PLATFORM);
    }
    public static void makeBusPlatform(final OSMEntity entity) {
        makePlatform(entity);
        entity.setTag(OSMEntity.KEY_HIGHWAY, OSMEntity.TAG_LEGACY_BUS_STOP); //legacy tag
    }
    public static void makeTrainPlatform(final OSMEntity entity) {
        makePlatform(entity);
        entity.setTag(OSMEntity.KEY_RAILWAY, OSMEntity.TAG_PLATFORM); //legacy tag
        if(entity instanceof OSMWay) {
            entity.setTag(OSMEntity.KEY_AREA, OSMEntity.TAG_YES);
        }
    }
    public static void makeSubwayPlatform(final OSMEntity entity) {
        makePlatform(entity);
        entity.setTag(OSMEntity.KEY_RAILWAY, OSMEntity.TAG_PLATFORM); //legacy tag
    }
    public static void makeStopPosition(final OSMEntity entity) {
        entity.setTag(OSMEntity.KEY_PUBLIC_TRANSPORT, OSMEntity.TAG_STOP_POSITION);
    }
    public static void makeRoute(final OSMRelation route) {
        route.setTag(OSMEntity.KEY_TYPE, OSMEntity.TAG_ROUTE);
        route.setTag(OSMEntity.KEY_PUBLIC_TRANSPORT_VERSION, "2");
    }
    public static void makeBusRoute(final OSMRelation route) {
        makeRoute(route);
        route.setTag(OSMEntity.KEY_ROUTE, OSMEntity.TAG_BUS);
    }
    public static void makeRouteMaster(final OSMRelation routeMaster) {
        routeMaster.setTag(OSMEntity.KEY_TYPE, OSMEntity.TAG_ROUTE_MASTER);
        routeMaster.setTag(OSMEntity.KEY_PUBLIC_TRANSPORT_VERSION, "2");
    }
}
