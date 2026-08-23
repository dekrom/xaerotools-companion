package dev.xaerotools.companion.sync;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shares the chunks XaeroPlus finds — new chunks, old chunks, portals — with
 * a remote XaeroTools server, which keeps its own database of them
 * (POST /ingest/v1/highlights, see docs/INGEST.md).
 *
 * Rows travel, never the database: the real ones run to gigabytes and have no
 * index on foundTime, so neither uploading nor rescanning one is affordable.
 * The source is XaeroPlus's in-memory highlight cache, which is where a find
 * lands before its own writer flushes it, read on the client tick because the
 * cache map is owned by that thread. Everything above a per-dimension
 * watermark goes up; the watermark starts at the first sighting of a database
 * so history is never walked, and only advances on a batch the server has
 * accepted, so a failed upload retries instead of leaving a hole.
 *
 * **Remote servers only.** A server on this machine already reads these
 * databases from disk through a scanned root; feeding it a second copy would
 * fork the same data. The server refuses loopback uploads for the same reason.
 *
 * XaeroPlus is reached by reflection with cached handles — no compile-time
 * dependency, and anything missing (mod absent, internals renamed) turns that
 * one source off for the session instead of crashing.
 */
public class HighlightSync {
    /** Rows per batch — the server's cap. */
    private static final int BATCH_MAX = 4096;
    /** Watermarks are written back at most this often. */
    private static final long SAVE_INTERVAL_MS = 30_000;
    private static final String STATE_FILE = "xaerotools-highlights.properties";

    /**
     * One synced module: how to reach its cache, and the database its rows
     * belong to on the server.
     *
     * OldChunks goes through the cache field rather than its public
     * getHighlightsState, because that method follows the module's inverse
     * toggle and would hand back the ModernChunks cache instead.
     */
    private static final class Source {
        final String moduleClass;
        final String method;
        final String field;
        final String db;
        boolean unavailable;
        Object module;
        Method accessor;
        Method cacheGet;
        Field cacheField;

        Source(String moduleClass, String method, String field, String db) {
            this.moduleClass = moduleClass;
            this.method = method;
            this.field = field;
            this.db = db;
        }
    }

    private final Source[] sources = {
        new Source("xaeroplus.module.impl.LiquidNewChunks", "getNewChunkHighlightsState", null,
            "XaeroPlusNewChunks.db"),
        new Source("xaeroplus.module.impl.OldChunks", null, "oldChunksCache",
            "XaeroPlusOldChunks.db"),
        new Source("xaeroplus.module.impl.Portals", "getHighlightsState", null,
            "XaeroPlusPortals.db"),
    };

    private final Uploader uploader;
    private final IntSupplier intervalSeconds;
    private final BooleanSupplier enabled;
    private final Consumer<String> log;

    /** Highest foundTime the server has taken, per world|db|dim. Persisted. */
    private final Map<String, Long> committed = new ConcurrentHashMap<>();
    /** Highest foundTime sent but not yet acknowledged; rolled back on failure. */
    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();

    private int countdown;
    private boolean loaded;
    private boolean dirty;
    private long lastSaveMs;
    /** Set when the server has said it will never take these uploads. */
    private volatile boolean refused;

    public HighlightSync(Uploader uploader, IntSupplier intervalSeconds, BooleanSupplier enabled,
                         Consumer<String> log) {
        this.uploader = uploader;
        this.intervalSeconds = intervalSeconds;
        this.enabled = enabled;
        this.log = log;
    }

    /** One game tick. Call only while the link is enabled. */
    public void tick() {
        if (refused || !enabled.getAsBoolean() || mc.level == null) return;
        if (--countdown > 0) return;
        countdown = Math.max(1, intervalSeconds.getAsInt()) * 20;
        if (!uploader.isRemoteServer()) return;
        String world = XaeroFlush.currentWorldId();
        if (world == null || world.isEmpty()) return;
        if (!loaded) {
            load();
            loaded = true;
        }
        ResourceKey<Level> dim = mc.level.dimension();
        String dimKey = dev.xaerotools.companion.XaeroTools.currentDimId();
        for (Source s : sources) {
            Long2LongMap map = cacheMap(s, dim);
            if (map == null || map.isEmpty()) continue;
            send(world, s, dimKey, map);
        }
        if (dirty && java.lang.System.currentTimeMillis() - lastSaveMs >= SAVE_INTERVAL_MS) save();
    }

    /** Flushes watermarks; call when the link is switched off. */
    public void stop() {
        if (dirty) save();
    }

    private void send(String world, Source s, String dimKey, Long2LongMap map) {
        String key = world + '|' + s.db + '|' + dimKey;
        Long mark = committed.get(key);
        if (mark == null) {
            // First sighting of this database: anchor at now. Everything older
            // is history, and history is seeded server-side, not streamed.
            committed.put(key, java.lang.System.currentTimeMillis());
            dirty = true;
            return;
        }
        long since = Math.max(mark, inFlight.getOrDefault(key, Long.MIN_VALUE));
        List<long[]> fresh = new ArrayList<>();
        for (Long2LongMap.Entry e : map.long2LongEntrySet()) {
            if (e.getLongValue() > since) fresh.add(new long[] {e.getLongKey(), e.getLongValue()});
        }
        if (fresh.isEmpty()) return;
        // Oldest first, so the watermark the batch advances to is a point every
        // row before it has already passed.
        fresh.sort((a, b) -> Long.compare(a[1], b[1]));
        int n = Math.min(fresh.size(), BATCH_MAX);
        long batchMark = fresh.get(n - 1)[1];
        ByteBuffer buf = ByteBuffer.allocate(7 + n * 16).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[] {'X', 'T', 'H', 'L', 1});
        buf.putShort((short) n);
        for (int i = 0; i < n; i++) {
            long packed = fresh.get(i)[0];
            buf.putInt(ChunkPos.getX(packed));
            buf.putInt(ChunkPos.getZ(packed));
            buf.putLong(fresh.get(i)[1]);
        }
        inFlight.put(key, batchMark);
        uploader.postHighlights(world, s.db, dimKey, buf.array(),
            () -> {
                committed.merge(key, batchMark, Math::max);
                inFlight.remove(key);
                dirty = true;
            },
            status -> {
                inFlight.remove(key);
                // 403 is the server saying these uploads are not welcome (it is
                // the one reading these databases locally); 404 is a server too
                // old to have the endpoint. Neither is worth retrying.
                if (status == 403 || status == 404) {
                    refused = true;
                    log.accept("highlight sync off for this session (server said "
                        + status + " — it is not a remote server, or predates the endpoint)");
                }
            });
    }

    /** XaeroPlus's live cache for one dimension, or null when unreachable. */
    private Long2LongMap cacheMap(Source s, ResourceKey<Level> dim) {
        if (s.unavailable) return null;
        try {
            if (s.module == null) {
                Class<?> cls = Class.forName(s.moduleClass);
                Object module = Class.forName("xaeroplus.module.ModuleManager")
                    .getMethod("getModule", Class.class)
                    .invoke(null, cls);
                if (module == null) {
                    s.unavailable = true;
                    return null;
                }
                if (s.method != null) {
                    s.accessor = cls.getMethod(s.method, ResourceKey.class);
                } else {
                    s.cacheField = cls.getField(s.field);
                    s.cacheGet = Class
                        .forName("xaeroplus.feature.highlights.SavableHighlightCacheInstance")
                        .getMethod("get");
                    s.accessor = Class
                        .forName("xaeroplus.feature.highlights.ChunkHighlightCache")
                        .getMethod("getCacheMap", ResourceKey.class);
                }
                s.module = module;
            }
            Object target = s.method != null ? s.module : s.cacheGet.invoke(s.cacheField.get(s.module));
            if (target == null) return null;
            return (Long2LongMap) s.accessor.invoke(target, dim);
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            // XaeroPlus absent, or this module's internals moved.
            s.unavailable = true;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(STATE_FILE);
    }

    private void load() {
        Properties p = new Properties();
        Path file = stateFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                // Unreadable state re-anchors every database at now, which
                // costs the rows found since the last save and nothing else.
                return;
            }
        }
        for (String name : p.stringPropertyNames()) {
            try {
                committed.put(name, Long.parseLong(p.getProperty(name).trim()));
            } catch (NumberFormatException ignored) {
                // Hand-edited or truncated line: treat as unseen.
            }
        }
    }

    private void save() {
        Properties p = new Properties();
        for (Map.Entry<String, Long> e : committed.entrySet()) {
            p.setProperty(e.getKey(), Long.toString(e.getValue()));
        }
        Path file = stateFile();
        Path tmp = file.resolveSibling(STATE_FILE + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "XaeroTools highlight sync — highest foundTime the server has taken");
        } catch (IOException e) {
            return;
        }
        try {
            // Renamed into place: a crash mid-write must not leave a state file
            // that re-anchors every database.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            return;
        }
        dirty = false;
        lastSaveMs = java.lang.System.currentTimeMillis();
    }
}
