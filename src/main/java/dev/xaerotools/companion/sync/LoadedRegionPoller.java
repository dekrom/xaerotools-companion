package dev.xaerotools.companion.sync;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Finds freshly-written region files by stat-ing the few regions the player is
 * actually near, instead of watching the whole map tree.
 *
 * Why not a WatchService: a long-lived archive keeps every region of a
 * dimension in ONE directory — a real 2b2t overworld folder measured here holds
 * over a million region files. Registering that tree costs a stat per file
 * before anything can be uploaded, and on macOS the JDK has no native watcher:
 * {@code FileSystems.getDefault().newWatchService()} silently returns
 * {@code PollingWatchService}, which detects changes by re-listing and
 * re-stat-ing every entry of every registered directory on each pass. Against a
 * directory of a million entries that never keeps up, so live region uploads
 * stop happening altogether while the rest of the addon looks healthy.
 *
 * This walks the other way round, and never lists a layer directory:
 * <ul>
 *   <li>the player's position gives the region coordinates worth looking at;</li>
 *   <li>the directories above the layer (world / dim / multiworld) are small,
 *       and their listing is cached for {@link #LAYER_REFRESH_MS};</li>
 *   <li>the region file itself is stat-ed by exact name.</li>
 * </ul>
 * Cost is bounded by the radius, not by the size of the map — tens of stats per
 * pass whether the archive is 300 MB or 300 GB.
 *
 * A region is handed on only once its size and mtime have stopped changing for
 * the configured settle time, so a file caught mid-write is never read torn.
 * Regions the player has recently been near stay in the candidate set for
 * {@link #RECENT_TTL_MS} so that the write Xaero performs *after* you leave an
 * area is still picked up.
 *
 * Region files that already existed and have not been touched this session are
 * adopted as a baseline rather than uploaded: this channel shares what you map
 * while you play. {@code .xt sync} remains the way to back up a whole map.
 */
public class LoadedRegionPoller implements Runnable {
    /** Xaero region grid: 512x512 blocks, so {@code regionX = blockX >> 9}. */
    private static final int REGION_SHIFT = 9;
    /** How often the candidate set is stat-ed. */
    private static final long POLL_MS = 1000;
    /** How often the world/dim/multiworld directory listing is refreshed. */
    private static final long LAYER_REFRESH_MS = 30_000;
    /** Regions kept as candidates after the player has moved away. */
    private static final long RECENT_TTL_MS = 10 * 60_000L;
    /** Cap on that set, so a long flight cannot grow it without bound. */
    private static final int RECENT_MAX = 256;
    /** Cap on per-file state, evicted oldest-first. */
    private static final int SEEN_MAX = 8192;
    /**
     * A region whose file is older than this at first sight predates the
     * session and is adopted silently; anything newer is treated as a write
     * worth uploading. Covers a save that landed just before we started.
     */
    private static final long FIRST_SIGHT_GRACE_MS = 60_000;

    /** The mod's own version-backup snapshots: {@code <version>_backup_<n>}. */
    private static final Pattern BACKUP_DIR = Pattern.compile(".*_backup_\\d+");
    private static final Pattern CAVE_DIR = Pattern.compile("\\d+");

    private final List<Path> roots;
    private final IntSupplier settleSeconds;
    private final IntSupplier radius;
    private final BooleanSupplier caves;
    private final Supplier<String> worldId;
    private final Consumer<RegionRef> out;

    /** Written on the main thread each tick, read by the poller thread. */
    private volatile boolean hasPlayer;
    private volatile int playerRx;
    private volatile int playerRz;
    private volatile boolean running = true;

    /** The other way round: written by the poller, read for {@code .xt status}. */
    private volatile List<Path> layers = List.of();

    // Poller-thread state only.
    private final Map<Path, Entry> seen = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Long> recent = new LinkedHashMap<>();
    private long layersAt;
    private long startedAt;

    private static final class Entry {
        long size = -1;
        long mtime = -1;
        long changedAt;
        long upSize = -1;
        long upMtime = -1;
    }

    public LoadedRegionPoller(List<Path> roots, IntSupplier settleSeconds, IntSupplier radius,
                              BooleanSupplier caves, Supplier<String> worldId, Consumer<RegionRef> out) {
        this.roots = roots;
        this.settleSeconds = settleSeconds;
        this.radius = radius;
        this.caves = caves;
        this.worldId = worldId;
        this.out = out;
    }

    /** Called from the client tick; cheap enough to run every time. */
    public void setPlayerBlock(int blockX, int blockZ) {
        this.playerRx = blockX >> REGION_SHIFT;
        this.playerRz = blockZ >> REGION_SHIFT;
        this.hasPlayer = true;
    }

    /** The player left the world; stop producing candidates until they rejoin. */
    public void clearPlayer() {
        this.hasPlayer = false;
    }

    public void stop() {
        running = false;
    }

    /** Region files currently being tracked — what {@code .xt status} reports. */
    public int trackedCount() {
        synchronized (seen) {
            return seen.size();
        }
    }

    /** Layer directories currently resolved, for diagnostics. */
    public int layerCount() {
        return layers.size();
    }

    @Override
    public void run() {
        startedAt = System.currentTimeMillis();
        while (running) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) return;
            try {
                pass();
            } catch (RuntimeException e) {
                // A pass that trips over a vanishing directory must not kill the
                // thread: the next one re-resolves everything.
                layersAt = 0;
            }
        }
    }

    private void pass() {
        if (!hasPlayer) return;
        long now = System.currentTimeMillis();
        if (now - layersAt > LAYER_REFRESH_MS) {
            layers = resolveLayers();
            layersAt = now;
        }
        if (layers.isEmpty()) return;

        List<long[]> candidates = candidates(now);
        long settleMs = Math.max(0, settleSeconds.getAsInt()) * 1000L;
        for (Path layer : layers) {
            for (long[] c : candidates) {
                check(layer, (int) c[0], (int) c[1], now, settleMs);
            }
        }
        trim();
    }

    /**
     * The regions worth stat-ing: a square around the player, plus everywhere
     * they have been recently (Xaero writes a region after you leave it).
     */
    private List<long[]> candidates(long now) {
        int r = Math.max(0, radius.getAsInt());
        int cx = playerRx;
        int cz = playerRz;
        List<long[]> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int rx = cx + dx;
                int rz = cz + dz;
                out.add(new long[]{rx, rz});
                recent.remove(key(rx, rz));
                recent.put(key(rx, rz), now);
            }
        }
        for (Iterator<Map.Entry<Long, Long>> it = recent.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() > RECENT_TTL_MS) {
                it.remove();
                continue;
            }
            int rx = (int) (e.getKey() >> 32);
            int rz = e.getKey().intValue();
            if (Math.abs(rx - cx) <= r && Math.abs(rz - cz) <= r) continue;
            out.add(new long[]{rx, rz});
        }
        while (recent.size() > RECENT_MAX) {
            Iterator<Long> it = recent.keySet().iterator();
            it.next();
            it.remove();
        }
        return out;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** Stat one candidate in one layer dir and decide whether it has settled. */
    private void check(Path layer, int rx, int rz, long now, long settleMs) {
        Path file = layer.resolve(rx + "_" + rz + ".zip");
        BasicFileAttributes attrs = statOrNull(file);
        if (attrs == null) {
            file = layer.resolve(rx + "_" + rz + ".xaero");
            attrs = statOrNull(file);
            if (attrs == null) return;
        }
        long size = attrs.size();
        long mtime = attrs.lastModifiedTime().toMillis();

        Entry e;
        synchronized (seen) {
            e = seen.get(file);
            if (e == null) {
                e = new Entry();
                seen.put(file, e);
                // Pre-existing and untouched this session: adopt as baseline.
                if (mtime < startedAt - FIRST_SIGHT_GRACE_MS) {
                    e.size = size;
                    e.mtime = mtime;
                    e.upSize = size;
                    e.upMtime = mtime;
                    return;
                }
            }
        }
        if (size != e.size || mtime != e.mtime) {
            e.size = size;
            e.mtime = mtime;
            e.changedAt = now;
            return;
        }
        if (size == e.upSize && mtime == e.upMtime) return;
        if (now - e.changedAt < settleMs) return;

        RegionRef ref = null;
        for (Path root : roots) {
            if (file.startsWith(root)) {
                ref = RegionRef.parse(root, file);
                if (ref != null) break;
            }
        }
        e.upSize = size;
        e.upMtime = mtime;
        if (ref != null) out.accept(ref);
    }

    private static BasicFileAttributes statOrNull(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Resolves the layer directories under the current world. Only directories
     * with few entries are ever listed — the layer dir itself, which is the one
     * holding a million region files, is never enumerated.
     */
    private List<Path> resolveLayers() {
        String world = worldId.get();
        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            for (Path worldDir : listDirs(root)) {
                String name = worldDir.getFileName().toString();
                if (skipDir(name) || name.startsWith(".")) continue;
                if (world != null && !world.isEmpty() && !name.equals(world)) continue;
                for (Path dimDir : listDirs(worldDir)) {
                    if (!RegionRef.isDimDir(dimDir.getFileName().toString())) continue;
                    for (Path mwDir : listDirs(dimDir)) {
                        if (!RegionRef.isMultiworldDir(mwDir.getFileName().toString())) continue;
                        found.add(mwDir);
                        if (!caves.getAsBoolean()) continue;
                        Path cavesDir = mwDir.resolve("caves");
                        if (!Files.isDirectory(cavesDir)) continue;
                        for (Path lvl : listDirs(cavesDir)) {
                            if (CAVE_DIR.matcher(lvl.getFileName().toString()).matches()) found.add(lvl);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static List<Path> listDirs(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) out.add(p);
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private static boolean skipDir(String name) {
        return name.startsWith("cache") || name.equals("XaeroPlus-db-backups") || BACKUP_DIR.matcher(name).matches();
    }

    /** Bounds per-file state; the oldest tracked files fall out first. */
    private void trim() {
        synchronized (seen) {
            if (seen.size() <= SEEN_MAX) return;
            Iterator<Path> it = seen.keySet().iterator();
            while (seen.size() > SEEN_MAX && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
}
