package dev.xaerotools.companion.sync;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Serial region-upload worker plus fire-and-forget position pings.
 *
 * One background thread drains the queue, throttled by the configured delay
 * setting so a full-map sync never trips the server's rate limit. 429 backs
 * off and requeues; a truncated-region 400 (the game was mid-write) and any
 * 5xx wait and retry a few times; other rejections are dropped. Tokens go
 * only into the Authorization header — never into URLs or logs. With no token
 * configured the header is omitted entirely: a server on the same machine
 * accepts loopback clients tokenless, identified by the X-XT-Player header.
 */
public class Uploader {
    private static final int MAX_ATTEMPTS = 5;
    private static final int BODY_MAX = 32 << 20;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final LinkedBlockingDeque<RegionRef> queue = new LinkedBlockingDeque<>();
    private final Set<Path> queued = ConcurrentHashMap.newKeySet();
    private final Map<Path, Integer> attempts = new ConcurrentHashMap<>();
    public final AtomicInteger sent = new AtomicInteger();
    public final AtomicInteger dropped = new AtomicInteger();

    private final Supplier<String> baseUrl;
    private final Supplier<String> token;
    private final Supplier<String> playerName;
    private final IntSupplier delayMs;
    private final BooleanSupplier uploadCaves;
    private final Consumer<String> log;

    private volatile boolean running;
    private Thread thread;

    public Uploader(Supplier<String> baseUrl, Supplier<String> token, Supplier<String> playerName,
                    IntSupplier delayMs, BooleanSupplier uploadCaves, Consumer<String> log) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.playerName = playerName;
        this.delayMs = delayMs;
        this.uploadCaves = uploadCaves;
        this.log = log;
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "xt-uploader");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
        queue.clear();
        queued.clear();
        attempts.clear();
        // A new Uploader is built on every enable, so the client goes with the
        // old one or its selector thread lives until GC. shutdownNow, not
        // close, which waits: the preview and highlight posts still in flight
        // are cancelled, and both callers treat that as a plain failure.
        http.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    /** No-op once stopped, so racing producers can't refill a dead queue. */
    public void enqueue(RegionRef ref) {
        if (!running) return;
        if (ref.cave() != null && !uploadCaves.getAsBoolean()) return;
        if (queued.add(ref.file())) queue.addLast(ref);
    }

    public int queueSize() {
        return queue.size();
    }

    private void run() {
        while (running) {
            RegionRef ref;
            try {
                ref = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (ref == null) continue;
            queued.remove(ref.file());
            try {
                uploadOne(ref);
                Thread.sleep(Math.max(0, delayMs.getAsInt()));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void uploadOne(RegionRef ref) throws InterruptedException {
        byte[] bytes;
        long mtime;
        try {
            bytes = Files.readAllBytes(ref.file());
            mtime = Files.getLastModifiedTime(ref.file()).toMillis();
        } catch (IOException e) {
            // The game may have just moved the file aside; treat like transient.
            retry(ref, "read failed: " + e.getMessage());
            return;
        }
        if (bytes.length == 0 || bytes.length > BODY_MAX) {
            drop(ref, "unusable size " + bytes.length);
            return;
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(regionUrl(ref)))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/octet-stream");
        // The name identifies us on the tokenless loopback path; the server
        // ignores it whenever a valid token names the player.
        String name = playerName.get();
        if (!name.isEmpty()) b.header("X-XT-Player", name);
        // When Xaero wrote the file: the server stamps the backup with it and
        // lets a newer merged region keep its tiles over an older upload.
        if (mtime > 0) b.header("X-XT-Mtime", Long.toString(mtime));
        authorize(b);
        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 204) {
                sent.incrementAndGet();
                attempts.remove(ref.file());
            } else if (code == 429) {
                Thread.sleep(2000);
                if (queued.add(ref.file())) queue.addFirst(ref);
            } else if (code == 400 && resp.body().contains("truncated")) {
                Thread.sleep(3000);
                retry(ref, "caught mid-write");
            } else if (code == 401 || code == 403) {
                drop(ref, "auth rejected (" + code + ") — check the token and player name");
            } else if (code >= 500 || code == 408) {
                // The server's problem, not the file's: a full disk or a
                // wedged store clears up, so this region must not be lost
                // for the rest of the session.
                Thread.sleep(3000);
                retry(ref, "server error " + code);
            } else {
                drop(ref, code + " " + resp.body());
            }
        } catch (IOException e) {
            retry(ref, "server unreachable");
            Thread.sleep(5000);
        }
    }

    private void retry(RegionRef ref, String why) {
        int n = attempts.merge(ref.file(), 1, Integer::sum);
        if (n > MAX_ATTEMPTS) {
            drop(ref, why + " (gave up after " + MAX_ATTEMPTS + " tries)");
        } else if (queued.add(ref.file())) {
            queue.addLast(ref);
        }
    }

    private void drop(RegionRef ref, String why) {
        dropped.incrementAndGet();
        attempts.remove(ref.file());
        log.accept("region " + ref.rx() + "_" + ref.rz() + " (" + ref.world() + "/" + ref.dim() + "): " + why);
    }

    private String regionUrl(RegionRef r) {
        StringBuilder sb = new StringBuilder(trimmedBase())
            .append("/ingest/v1/region?world=").append(enc(r.world()))
            .append("&dim=").append(enc(r.dim()))
            .append("&mw=").append(enc(r.mw()))
            .append("&rx=").append(r.rx())
            .append("&rz=").append(r.rz());
        if (r.cave() != null) sb.append("&cave=").append(r.cave());
        return sb.toString();
    }

    /** Async preview-batch upload. `onOk` runs only when the server accepted
     *  the batch (204), so callers can commit their sent-state safely. */
    public void postPreview(String dim, byte[] body, Runnable onOk) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(trimmedBase() + "/ingest/v1/preview?dim=" + enc(dim)))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/octet-stream");
        String name = playerName.get();
        if (!name.isEmpty()) b.header("X-XT-Player", name);
        authorize(b);
        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(resp -> {
                if (resp.statusCode() == 204 && onOk != null) onOk.run();
            });
    }

    /**
     * Chunk-highlight rows for one (world, database, dimension). Accepted
     * batches call onOk, anything else calls onFail with the status (0 for a
     * transport error) — the caller retries those rows rather than skipping
     * them, and gives up on a status that says the server will never take
     * them.
     */
    public void postHighlights(String world, String db, String dim, byte[] body,
                               Runnable onOk, java.util.function.IntConsumer onFail) {
        String url = trimmedBase() + "/ingest/v1/highlights?world=" + enc(world)
            + "&db=" + enc(db) + "&dim=" + enc(dim);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/octet-stream");
        String name = playerName.get();
        if (!name.isEmpty()) b.header("X-XT-Player", name);
        authorize(b);
        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                if (err == null && resp.statusCode() == 204) {
                    if (onOk != null) onOk.run();
                } else if (onFail != null) {
                    onFail.accept(err != null ? 0 : resp.statusCode());
                }
            });
    }

    /**
     * True when the configured server is not this machine — the highlight
     * sync is for remote servers only, since one running here already reads
     * the same XaeroPlus databases straight off disk. Literal loopback names
     * only: resolving a host would mean a DNS lookup on the game thread.
     */
    public boolean isRemoteServer() {
        try {
            String host = URI.create(trimmedBase()).getHost();
            if (host == null || host.isEmpty()) return false;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
            return !(h.equals("localhost") || h.endsWith(".localhost")
                || h.equals("::1") || h.equals("0.0.0.0") || h.startsWith("127."));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Fire-and-forget 1 Hz position ping; failures are silent by design. */
    public void postPosition(String player, String dim, double x, double y, double z, float yaw) {
        String body = String.format(java.util.Locale.ROOT,
            "{\"player\":%s,\"dim\":%s,\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"yaw\":%.1f}",
            jsonString(player), jsonString(dim), x, y, z, yaw);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(trimmedBase() + "/ingest/v1/position"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
        authorize(b);
        HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    /** Adds the bearer token when one is configured; tokenless is valid for loopback servers. */
    private void authorize(HttpRequest.Builder b) {
        String tok = token.get().trim();
        if (!tok.isEmpty()) b.header("Authorization", "Bearer " + tok);
    }

    private String trimmedBase() {
        String base = baseUrl.get().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
