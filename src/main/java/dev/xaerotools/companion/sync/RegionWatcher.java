package dev.xaerotools.companion.sync;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * Watches the world-map trees for freshly-written region files.
 *
 * Every directory is registered non-recursively (new dirs picked up via their
 * parent's create event); a changed file is uploaded only after its events
 * have settled for the configured number of seconds, so the game's temp +
 * rename saves are read whole. Roots that do not exist yet (Xaero has not
 * mapped anything) are re-probed periodically.
 */
public class RegionWatcher implements Runnable {
    /** The mod's own version-backup snapshots: {@code <version>_backup_<n>}. */
    private static final Pattern BACKUP_DIR = Pattern.compile(".*_backup_\\d+");

    private final List<Path> roots;
    private final IntSupplier settleSeconds;
    private final Consumer<RegionRef> out;

    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Map<Path, Long> pending = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private WatchService ws;
    private long lastRootProbe;

    public RegionWatcher(List<Path> roots, IntSupplier settleSeconds, Consumer<RegionRef> out) {
        this.roots = roots;
        this.settleSeconds = settleSeconds;
        this.out = out;
    }

    public void stop() {
        running = false;
        try {
            if (ws != null) ws.close();
        } catch (IOException ignored) {}
    }

    public int watchedDirs() {
        synchronized (keys) {
            return keys.size();
        }
    }

    private static boolean skipDir(String name) {
        return name.startsWith("cache") || name.equals("XaeroPlus-db-backups") || BACKUP_DIR.matcher(name).matches();
    }

    private void registerTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (skipDir(d.getFileName().toString()) && !d.equals(dir)) return FileVisitResult.SKIP_SUBTREE;
                    WatchKey key = d.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                    synchronized (keys) {
                        keys.put(key, d);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A root that vanished mid-walk; the next probe retries.
        } catch (java.nio.file.ClosedWatchServiceException ignored) {
            // stop() closed the service mid-walk; the run loop exits next poll.
        }
    }

    private void probeRoots() {
        for (Path root : roots) {
            boolean known;
            synchronized (keys) {
                known = keys.containsValue(root);
            }
            if (!known && Files.isDirectory(root)) registerTree(root);
        }
        lastRootProbe = System.currentTimeMillis();
    }

    @Override
    public void run() {
        try {
            ws = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            return;
        }
        probeRoots();
        while (running) {
            WatchKey key;
            try {
                key = ws.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            if (key != null) {
                Path dir;
                synchronized (keys) {
                    dir = keys.get(key);
                }
                if (dir != null) {
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        Path child = dir.resolve((Path) ev.context());
                        if (Files.isDirectory(child)) {
                            if (!skipDir(child.getFileName().toString())) registerTree(child);
                        } else {
                            pending.put(child, System.currentTimeMillis());
                        }
                    }
                }
                if (!key.reset()) {
                    synchronized (keys) {
                        keys.remove(key);
                    }
                }
            }
            flushSettled();
            if (System.currentTimeMillis() - lastRootProbe > 30_000) probeRoots();
        }
    }

    private void flushSettled() {
        long cutoff = System.currentTimeMillis() - settleSeconds.getAsInt() * 1000L;
        for (Iterator<Map.Entry<Path, Long>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, Long> e = it.next();
            if (e.getValue() > cutoff) continue;
            it.remove();
            Path file = e.getKey();
            for (Path root : roots) {
                RegionRef ref = file.startsWith(root) ? RegionRef.parse(root, file) : null;
                if (ref != null) {
                    out.accept(ref);
                    break;
                }
            }
        }
    }
}
