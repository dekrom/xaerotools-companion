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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shares the chunks XaeroPlus finds — both new-chunk detections and their
 * inverses, old/modern chunks, portals, old biomes and breadcrumb trails —
 * with a remote XaeroTools server, which keeps its own database of them
 * (POST /ingest/v1/highlights, see docs/INGEST.md).
 *
 * Rows travel, never the database: the real ones run to gigabytes and have no
 * index on foundTime, so neither uploading nor rescanning one is affordable.
 * The source is XaeroPlus's in-memory highlight cache, which is where a find
 * lands before its own writer flushes it, read on the client tick because the
 * cache map is owned by that thread. Everything above a per-dimension
 * watermark goes up; a database seen for the first time starts its watermark
 * where the link came up, so history is never walked, and it only advances on
 * a batch the server has accepted, so a failed upload retries instead of
 * leaving a hole.
 *
 * Best-effort past that. XaeroPlus's cache is a moving window — it drops
 * chunks that leave it, and clears a dimension's map outright on a dimension
 * hop — so a find swept out of it before the next pass is never sent, and
 * nothing goes back for it. Only additions travel, too: a highlight XaeroPlus
 * later retracts stays on the shared map. Giving a server a whole database is
 * still a matter of copying the file into a root it scans.
 *
 * **Remote servers only.** A server on this machine already reads these
 * databases from disk through a scanned root; feeding it a second copy would
 * fork the same data. The server refuses loopback uploads for the same reason.
 *
 * XaeroPlus is reached by reflection with cached handles — no compile-time
 * dependency, and anything missing (mod absent, internals renamed) turns that
 * one source off for the session instead of crashing.
 *
 * A module the user has turned off costs nothing: its cache map is empty, and
 * an empty map is skipped before any batch is built. So the set that travels
 * is exactly the set of highlights that instance is actually collecting —
 * there is no second list of modules to keep in sync with XaeroPlus's own.
 */
public class HighlightSync {
    /** Rows per batch — the server's cap. */
    private static final int BATCH_MAX = 4096;
    /** Watermarks are written back at most this often. */
    private static final long SAVE_INTERVAL_MS = 30_000;
    /** How long a database the server rejected stands down before one retry. */
    private static final long SOURCE_RETRY_MS = 30 * 60_000L;
    /** How long the whole sweep stands down after the token was rejected. */
    private static final long AUTH_RETRY_MS = 5 * 60_000L;
    /** Consecutive unreadable sweeps before a source is given up on. */
    private static final int MAX_READ_ERRORS = 10;
    private static final String STATE_FILE = "xaerotools-highlights.properties";

    /**
     * One synced module: the cache field to read, and the database its rows
     * belong to on the server.
     *
     * Always the field, never the module's public getHighlightsState: several
     * modules own two caches (a detection and its inverse) and that method
     * follows the module's own render toggle, so it hands back whichever one
     * the user happens to be *looking* at. Reading the fields takes both, and
     * takes them regardless of what is currently drawn in game.
     */
    private static final class Source {
        final String moduleClass;
        final String field;
        final String db;
        /** Nothing here is readable any more; set on the tick or off it. */
        volatile boolean unavailable;
        /** Rejected by the server: skipped until this time. Set off-thread. */
        volatile long retryAt;
        /** Consecutive failed reads. Tick thread only. */
        int readErrors;
        Object module;
        Method accessor;
        Method cacheGet;
        Field cacheField;

        Source(String moduleClass, String field, String db) {
            this.moduleClass = moduleClass;
            this.field = field;
            this.db = db;
        }
    }

    /**
     * Every XaeroPlus module whose value column is a timestamp — the server's
     * allowlist, in the same order.
     *
     * LavaColumns is deliberately absent and must stay absent: it stores a
     * column *height* where the others store a first-sighting time, so the
     * watermark below — "everything above the last value the server took" —
     * would page it by lava depth and lose almost every row.
     */
    private final Source[] sources = {
        new Source("xaeroplus.module.impl.LiquidNewChunks", "newChunksCache",
            "XaeroPlusNewChunks.db"),
        new Source("xaeroplus.module.impl.LiquidNewChunks", "inverseNewChunksCache",
            "XaeroPlusNewChunksLiquidInverse.db"),
        new Source("xaeroplus.module.impl.PaletteNewChunks", "newChunksCache",
            "XaeroPlusPaletteNewChunks.db"),
        new Source("xaeroplus.module.impl.PaletteNewChunks", "newChunksInverseCache",
            "XaeroPlusPaletteNewChunksInverse.db"),
        new Source("xaeroplus.module.impl.OldChunks", "oldChunksCache",
            "XaeroPlusOldChunks.db"),
        new Source("xaeroplus.module.impl.OldChunks", "modernChunksCache",
            "XaeroPlusModernChunks.db"),
        new Source("xaeroplus.module.impl.Portals", "portalsCache",
            "XaeroPlusPortals.db"),
        new Source("xaeroplus.module.impl.OldBiomes", "oldBiomesCache",
            "XaeroPlusOldBiomes.db"),
        new Source("xaeroplus.module.impl.Breadcrumbs", "breadcrumbsCache",
            "XaeroPlusBreadcrumbs.db"),
    };

    private final Uploader uploader;
    private final IntSupplier intervalSeconds;
    private final BooleanSupplier enabled;
    private final Consumer<String> log;

    /** Highest foundTime the server has taken, per world|db|dim. Persisted. */
    private final Map<String, Long> committed = new ConcurrentHashMap<>();
    /** Keys with a batch outstanding; cleared by that batch's reply. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** When the link came up: the anchor for a database first seen later. */
    private final long sessionStartMs = java.lang.System.currentTimeMillis();

    private int countdown;
    private boolean loaded;
    private long lastSaveMs;
    /** Watermarks moved since the last write; set from the upload thread. */
    private volatile boolean dirty;
    /** Set when the server has said it will never take these uploads. */
    private volatile boolean refused;
    /** No sweep before this time — the server rejected the token. */
    private volatile long quietUntilMs;
    private volatile boolean authWarned;

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
        // Standing down after a rejected token: the sweep comes back on its
        // own, so a token corrected in the settings needs no off/on cycle.
        if (java.lang.System.currentTimeMillis() < quietUntilMs) return;
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
        // One batch at a time per key. Two of them in flight together can be
        // answered out of order, and a later success would carry the watermark
        // past the rows of an earlier failure — which are then never resent.
        // The reply always clears this, so it cannot wedge.
        if (inFlight.contains(key)) return;
        Long mark = committed.get(key);
        if (mark == null) {
            // First sighting of this database: anchor where the link came up,
            // not at now — a module the user switched on ten minutes in has
            // been finding chunks since then, and those are ours to send.
            // Everything older is history, seeded server-side, not streamed.
            committed.put(key, sessionStartMs);
            dirty = true;
            return;
        }
        long since = mark;
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
        inFlight.add(key);
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
                } else if (status == 401) {
                    // The token is read fresh on every request, so a corrected
                    // one has to be able to take effect by itself; latching
                    // here would leave an off/on cycle of the whole system as
                    // the only way back. Every rejected token costs the server
                    // a global backoff, though, so stand the sweep down rather
                    // than asking again at the configured interval.
                    quietUntilMs = java.lang.System.currentTimeMillis() + AUTH_RETRY_MS;
                    if (!authWarned) {
                        authWarned = true;
                        log.accept("highlight sync: the server rejected the token (401)"
                            + " — check the token setting under Connection");
                    }
                } else if (status == 400) {
                    // The server will not take this database — most often one
                    // running a build older than the module list above. Asking
                    // every sweep would achieve nothing, so this one source
                    // stands down for half an hour (a server upgraded under us
                    // is then picked up on its own) and the others carry on.
                    boolean first = s.retryAt == 0;
                    s.retryAt = java.lang.System.currentTimeMillis() + SOURCE_RETRY_MS;
                    if (first) {
                        log.accept("highlight sync: server rejected " + s.db
                            + " (400) — pausing that one; the others continue");
                    }
                }
            });
    }

    /** XaeroPlus's live cache for one dimension, or null when unreachable. */
    private Long2LongMap cacheMap(Source s, ResourceKey<Level> dim) {
        if (s.unavailable) return null;
        if (s.retryAt > java.lang.System.currentTimeMillis()) return null;
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
                s.cacheField = cls.getField(s.field);
                s.cacheGet = Class
                    .forName("xaeroplus.feature.highlights.SavableHighlightCacheInstance")
                    .getMethod("get");
                s.accessor = Class
                    .forName("xaeroplus.feature.highlights.ChunkHighlightCache")
                    .getMethod("getCacheMap", ResourceKey.class);
                s.module = module;
            }
            Object cache = s.cacheGet.invoke(s.cacheField.get(s.module));
            if (cache == null) return null;
            Long2LongMap map = (Long2LongMap) s.accessor.invoke(cache, dim);
            s.readErrors = 0;
            return map;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
            // XaeroPlus absent, or this module's internals moved.
            s.unavailable = true;
            return null;
        } catch (Throwable t) {
            // A field that changed type, an accessor that threw. Not proof the
            // handle is dead — a cache still coming up lands here too — so it
            // gets a few sweeps first. Then it has to be said: a source failing
            // in silence is indistinguishable from a module the user turned
            // off, which is the one state nobody would think to check.
            if (++s.readErrors >= MAX_READ_ERRORS) {
                s.unavailable = true;
                log.accept("highlight sync: cannot read " + s.db + " from XaeroPlus ("
                    + t.getClass().getSimpleName() + ") — that source is off for this session");
            }
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
        // Cleared before the snapshot, not after it: a batch acknowledged
        // while this is writing must leave the flag set, or its watermark
        // waits for the next one to come along.
        dirty = false;
        Properties p = new Properties();
        for (Map.Entry<String, Long> e : committed.entrySet()) {
            p.setProperty(e.getKey(), Long.toString(e.getValue()));
        }
        Path file = stateFile();
        Path tmp = file.resolveSibling(STATE_FILE + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "XaeroTools highlight sync — highest foundTime the server has taken");
        } catch (IOException e) {
            dirty = true;
            return;
        }
        try {
            // Renamed into place: a crash mid-write must not leave a state file
            // that re-anchors every database.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            dirty = true;
            return;
        }
        lastSaveMs = java.lang.System.currentTimeMillis();
    }
}
