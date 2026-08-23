package dev.xaerotools.companion.sync;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The seam onto Xaero's World Map: nudging freshly-mapped regions to disk
 * early, and asking which world folder it is writing.
 *
 * Xaero holds a dirty region in memory for up to SAVE_TIME (60 s) before its
 * processor thread saves it; the region watcher (and so the shared map's
 * authoritative data) can only ever be that stale. The save gate is simply
 * {@code now - region.getLastSaveTime() >= SAVE_TIME} for loaded regions that
 * are being written, so zeroing {@code lastSaveTime} makes a region eligible
 * on the processor's next pass — Xaero's own thread then performs the save
 * with all its usual locking. Verified against the world map jar's bytecode
 * ({@code MapSaveLoad.updateSave}).
 *
 * Everything is reflection with cached handles: no compile-time dependency on
 * the world map mod, and any breakage (mod absent, renamed internals) turns
 * the feature off for the session instead of crashing.
 */
public final class XaeroFlush {
    private static boolean unavailable;
    private static Method getCurrentSession;
    private static Method getMapProcessor;
    private static Method getMapWorld;
    private static Method getCurrentDimension;
    private static Method getLayeredMapRegions;
    private static Method getLoadedListUnsynced;
    private static Method getLevel;
    private static Method getLoadState;
    private static Method isBeingWritten;
    private static Method setLastSaveTime;
    private static boolean worldIdUnavailable;
    private static Method worldIdSession;
    private static Method worldIdProcessor;
    private static Method getCurrentWorldId;

    private XaeroFlush() {}

    /**
     * The world folder Xaero is writing right now ("Multiplayer_2b2t"), or
     * null when there is no session. This is the same id the mod builds its
     * save path from ({@code MapSaveLoad.getMWSubFolder(worldId, dimId, mwId)}
     * in the world map jar), so it names the folder uploads land in.
     *
     * Handles are resolved separately from the flush path on purpose: one of
     * the two breaking on a mod update must not take the other with it.
     */
    public static String currentWorldId() {
        if (worldIdUnavailable) return null;
        try {
            if (getCurrentWorldId == null) {
                Class<?> session = Class.forName("xaero.map.WorldMapSession");
                worldIdSession = session.getMethod("getCurrentSession");
                worldIdProcessor = session.getMethod("getMapProcessor");
                getCurrentWorldId = Class.forName("xaero.map.MapProcessor").getMethod("getCurrentWorldId");
            }
            Object session = worldIdSession.invoke(null);
            if (session == null) return null;
            Object processor = worldIdProcessor.invoke(session);
            if (processor == null) return null;
            return (String) getCurrentWorldId.invoke(processor);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            worldIdUnavailable = true;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Marks every dirty loaded region save-eligible. Returns how many regions
     * were nudged (0 when idle, the mod is absent, or anything went wrong).
     */
    public static int flushDirtyRegions() {
        if (unavailable) return 0;
        try {
            if (getCurrentSession == null) {
                Class<?> session = Class.forName("xaero.map.WorldMapSession");
                getCurrentSession = session.getMethod("getCurrentSession");
                getMapProcessor = session.getMethod("getMapProcessor");
                Class<?> processor = Class.forName("xaero.map.MapProcessor");
                getMapWorld = processor.getMethod("getMapWorld");
                Class<?> world = Class.forName("xaero.map.world.MapWorld");
                getCurrentDimension = world.getMethod("getCurrentDimension");
                Class<?> dim = Class.forName("xaero.map.world.MapDimension");
                getLayeredMapRegions = dim.getMethod("getLayeredMapRegions");
                Class<?> mgr = Class.forName("xaero.map.region.LayeredRegionManager");
                getLoadedListUnsynced = mgr.getMethod("getLoadedListUnsynced");
                Class<?> leveled = Class.forName("xaero.map.region.LeveledRegion");
                getLevel = leveled.getMethod("getLevel");
                Class<?> region = Class.forName("xaero.map.region.MapRegion");
                getLoadState = region.getMethod("getLoadState");
                isBeingWritten = region.getMethod("isBeingWritten");
                setLastSaveTime = region.getMethod("setLastSaveTime", long.class);
            }
            Object session = getCurrentSession.invoke(null);
            if (session == null) return 0;
            Object processor = getMapProcessor.invoke(session);
            if (processor == null) return 0;
            Object world = getMapWorld.invoke(processor);
            if (world == null) return 0;
            Object dim = getCurrentDimension.invoke(world);
            if (dim == null) return 0;
            Object mgr = getLayeredMapRegions.invoke(dim);
            List<?> loaded = new ArrayList<>((List<?>) getLoadedListUnsynced.invoke(mgr));
            int nudged = 0;
            for (Object r : loaded) {
                if (r == null || (int) getLevel.invoke(r) != 0) continue;
                // Load state 2 = fully loaded; isBeingWritten = has unsaved writes.
                if ((byte) getLoadState.invoke(r) != 2) continue;
                if (!(boolean) isBeingWritten.invoke(r)) continue;
                setLastSaveTime.invoke(r, 0L);
                nudged++;
            }
            return nudged;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // World map mod absent or its internals changed: stay off quietly.
            unavailable = true;
            return 0;
        } catch (Throwable t) {
            // A concurrent list change mid-copy is possible; retry next cycle.
            return 0;
        }
    }
}
