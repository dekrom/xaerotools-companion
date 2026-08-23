package dev.xaerotools.companion;

import dev.xaerotools.companion.sync.HighlightSync;
import dev.xaerotools.companion.sync.PreviewScanner;
import dev.xaerotools.companion.sync.RegionRef;
import dev.xaerotools.companion.sync.LoadedRegionPoller;
import dev.xaerotools.companion.sync.Uploader;
import dev.xaerotools.companion.sync.XaeroFlush;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The client half of the XaeroTools live-share seam (docs/INGEST.md):
 * position pings at ~1 Hz plus incremental upload of freshly-mapped regions.
 * A remote server authenticates with the per-player bearer token generated
 * on it (`xaerotools tokens generate <player>`); a server on this machine
 * needs no token — loopback clients just declare their player name.
 *
 * A Meteor System, not a module: it persists to xaerotools.nbt alongside
 * Meteor's own config and is edited from the XaeroTools tab in the GUI.
 */
public class XaeroTools extends meteordevelopment.meteorclient.systems.System<XaeroTools> {
    public final Settings settings = new Settings();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgConnection = settings.createGroup("Connection");
    private final SettingGroup sgPosition = settings.createGroup("Position");
    private final SettingGroup sgUpload = settings.createGroup("Map Upload");
    private final SettingGroup sgPreview = settings.createGroup("Live Preview");
    private final SettingGroup sgHighlights = settings.createGroup("Highlight Sync");

    public final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Master switch for the whole live-share link.")
        .defaultValue(false)
        .onChanged(on -> {
            if (on) start();
            else stop();
        })
        .build()
    );

    private final Setting<String> serverUrl = sgConnection.add(new StringSetting.Builder()
        .name("server-url")
        .description("Base URL of the XaeroTools server, e.g. http://192.0.2.10:45746.")
        .defaultValue("http://127.0.0.1:45746")
        .build()
    );

    private final Setting<String> token = sgConnection.add(new StringSetting.Builder()
        .name("token")
        .description("Ingest bearer token from `xaerotools tokens generate <player>`. Only needed for a remote server — leave empty when it runs on this machine.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> playerName = sgConnection.add(new StringSetting.Builder()
        .name("player-name")
        .description("Account name the token was generated for. Empty = current session name.")
        .defaultValue("")
        .build()
    );

    private final Setting<java.util.List<String>> accountTokens = sgConnection.add(new StringListSetting.Builder()
        .name("account-tokens")
        .description("NAME=TOKEN entries, one per account that uses this instance. The entry matching the logged-in account wins (its NAME spelling is used, since it must match the token's player) — one config then serves every alt. Falls back to the single token/player-name settings.")
        .defaultValue(java.util.List.of())
        .build()
    );

    private final Setting<Boolean> positionPing = sgPosition.add(new BoolSetting.Builder()
        .name("position-ping")
        .description("Report your position about once a second — the live marker on the shared map.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pingInterval = sgPosition.add(new IntSetting.Builder()
        .name("ping-interval-ticks")
        .description("Ticks between position reports (20 = once a second).")
        .defaultValue(20)
        .min(5)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> mapUpload = sgUpload.add(new BoolSetting.Builder()
        .name("map-upload")
        .description("Watch the local Xaero world-map folder and upload freshly-mapped regions.")
        .defaultValue(true)
        .onChanged(on -> {
            if (!isRunning()) return;
            if (on) startWatcher();
            else stopWatcher();
        })
        .build()
    );

    private final Setting<Boolean> uploadCaves = sgUpload.add(new BoolSetting.Builder()
        .name("upload-caves")
        .description("Also upload cave-mode layers (caves/<n>). Off = only the surface layer is shared, even when cave mode is toggled in game.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> settleSeconds = sgUpload.add(new IntSetting.Builder()
        .name("settle-seconds")
        .description("How long a changed region file must stay quiet before it is uploaded.")
        .defaultValue(5)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Integer> uploadDelay = sgUpload.add(new IntSetting.Builder()
        .name("upload-delay-ms")
        .description("Minimum delay between two region uploads (keeps full syncs under the server's rate limit).")
        .defaultValue(250)
        .min(50)
        .sliderMax(2000)
        .build()
    );

    private final Setting<Integer> forceSaveSeconds = sgUpload.add(new IntSetting.Builder()
        .name("force-save-seconds")
        .description("Nudge Xaero to write freshly-mapped regions this often, so the shared map's real data is never a minute behind (0 = let the game save on its own schedule, up to 60s). Costs a few extra region writes while mapping.")
        .defaultValue(15)
        .min(0)
        .sliderMax(120)
        .build()
    );

    private final Setting<Integer> uploadRadius = sgUpload.add(new IntSetting.Builder()
        .name("upload-radius")
        .description("How far around you, in 512-block regions, freshly-mapped files are watched for. Regions you have recently been near stay watched for ten minutes after you leave, so the save Xaero makes behind you is still picked up.")
        .defaultValue(2)
        .min(0)
        .sliderMax(8)
        .build()
    );

    private final Setting<Boolean> logFailures = sgUpload.add(new BoolSetting.Builder()
        .name("log-failures")
        .description("Report regions the server rejected in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> livePreview = sgPreview.add(new BoolSetting.Builder()
        .name("live-preview")
        .description("Stream a coarse color preview of the chunks around you the moment they load — the shared map shows terrain before Xaero even saves it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> previewRadius = sgPreview.add(new IntSetting.Builder()
        .name("preview-radius")
        .description("Chunk radius around you to scan for the live preview.")
        .defaultValue(8)
        .min(2)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> previewPassDelay = sgPreview.add(new IntSetting.Builder()
        .name("preview-pass-delay-ticks")
        .description("Rest between preview sweeps (20 = about one sweep per second plus scan time).")
        .defaultValue(20)
        .min(5)
        .sliderMax(200)
        .build()
    );

    private final Setting<Boolean> highlightSync = sgHighlights.add(new BoolSetting.Builder()
        .name("highlight-sync")
        .description("Share the chunks XaeroPlus finds (new chunks, old chunks, portals) with the server, which keeps its own database of them. Remote servers only — one running on this machine already reads these databases itself.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> highlightInterval = sgHighlights.add(new IntSetting.Builder()
        .name("highlight-interval-seconds")
        .description("Seconds between passes over XaeroPlus's find cache.")
        .defaultValue(5)
        .min(1)
        .sliderMax(60)
        .build()
    );

    // Read from the tick, watcher and full-sync threads; written on the main thread.
    private volatile Uploader uploader;
    private LoadedRegionPoller watcher;
    private Thread watcherThread;
    /** The world folder Xaero is writing, republished for the poller thread. */
    private volatile String currentWorldFolder;
    private int worldIdCounter;
    private PreviewScanner scanner;
    private HighlightSync highlights;
    private int tickCounter;
    private long lastForceSaveMs;

    public XaeroTools() {
        super("xaerotools");
    }

    public static XaeroTools get() {
        return Systems.get(XaeroTools.class);
    }

    public boolean isRunning() {
        return uploader != null;
    }

    private void start() {
        if (uploader != null) return;
        tickCounter = 0;
        uploader = new Uploader(serverUrl::get, this::resolvedToken, this::resolvedName, uploadDelay::get, uploadCaves::get, msg -> {
            if (logFailures.get()) mc.execute(() -> ChatUtils.warning("XaeroTools: " + msg));
        });
        uploader.start();
        scanner = new PreviewScanner(uploader, previewRadius::get, previewPassDelay::get);
        highlights = new HighlightSync(uploader, highlightInterval::get, highlightSync::get, msg -> {
            if (logFailures.get()) mc.execute(() -> ChatUtils.warning("XaeroTools: " + msg));
        });
        if (mapUpload.get()) startWatcher();
    }

    private void stop() {
        stopWatcher();
        scanner = null;
        if (highlights != null) {
            highlights.stop();
            highlights = null;
        }
        if (uploader != null) {
            uploader.stop();
            uploader = null;
        }
    }

    /**
     * The identity we act as: {name, token}. An account-tokens entry matching
     * the session (or pinned) name wins, and its own spelling is used — the
     * server compares the position body's player to the token's player
     * exactly, so the entry's NAME (as the token was generated) is the one
     * that authenticates. Otherwise the plain player-name/token settings.
     */
    private String[] identity() {
        String base = playerName.get().trim();
        if (base.isEmpty()) base = mc.getUser() == null ? "" : mc.getUser().getName();
        for (String entry : accountTokens.get()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String n = entry.substring(0, eq).trim();
            String t = entry.substring(eq + 1).trim();
            if (!t.isEmpty() && n.equalsIgnoreCase(base)) return new String[] {n, t};
        }
        return new String[] {base, token.get().trim()};
    }

    /** The name we act as (see {@link #identity()}). */
    private String resolvedName() {
        return identity()[0];
    }

    /** The bearer token for the acting account; empty = tokenless loopback. */
    private String resolvedToken() {
        return identity()[1];
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!enabled.get()) return;
        // Systems.load() flips `enabled` before the game is ready; the first
        // tick is the reliable point to bring the threads up.
        if (uploader == null) start();
        if (mc.level != null) {
            if (livePreview.get() && scanner != null) scanner.tick(currentDimId());
            if (highlights != null) highlights.tick();
            // The poller runs off-thread and must not touch the game or Xaero
            // itself: the position and the world folder are published to it
            // from here. The world id costs a reflection hop, so once a second.
            if (watcher != null && mc.player != null) {
                watcher.setPlayerBlock((int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getZ()));
                if (++worldIdCounter >= 20) {
                    worldIdCounter = 0;
                    currentWorldFolder = XaeroFlush.currentWorldId();
                }
            }
            int fs = forceSaveSeconds.get();
            if (fs > 0 && java.lang.System.currentTimeMillis() - lastForceSaveMs >= fs * 1000L) {
                lastForceSaveMs = java.lang.System.currentTimeMillis();
                XaeroFlush.flushDirtyRegions();
            }
        } else if (watcher != null) {
            // No world: stop producing candidates until the player rejoins,
            // so the poller idles instead of re-stat-ing the last position.
            watcher.clearPlayer();
        }
        if (!positionPing.get()) return;
        if (++tickCounter < pingInterval.get()) return;
        tickCounter = 0;
        sendPosition();
    }

    /** The current dimension's resource id, e.g. "minecraft:overworld". */
    public static String currentDimId() {
        //? if >=1.21.11 {
        return mc.level.dimension().identifier().toString();
        //?} else {
        /*return mc.level.dimension().location().toString();
        *///?}
    }

    public void sendPosition() {
        if (uploader == null || mc.player == null || mc.level == null) return;
        uploader.postPosition(resolvedName(), currentDimId(),
            mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot());
    }

    private void startWatcher() {
        if (watcher != null || uploader == null) return;
        watcher = new LoadedRegionPoller(worldMapRoots(), settleSeconds::get, uploadRadius::get,
            uploadCaves::get, () -> currentWorldFolder, ref -> uploader.enqueue(ref));
        watcherThread = new Thread(watcher, "xt-region-watch");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void stopWatcher() {
        if (watcher != null) {
            watcher.stop();
            watcher = null;
            watcherThread = null;
        }
    }

    /** Both places packs put the world-map tree; missing ones are probed later. */
    public static List<Path> worldMapRoots() {
        Path game = FabricLoader.getInstance().getGameDir();
        return List.of(
            game.resolve("xaero").resolve("world-map"),
            game.resolve("config").resolve("xaero").resolve("world-map")
        );
    }

    /**
     * Queues every region file on disk for upload — the initial whole-map
     * backup. Walks on a background thread; `worldFilter` (may be null)
     * restricts to one world folder name.
     */
    public void fullSync(String worldFilter) {
        // Captured once: the walker keeps using this instance, and aborts as
        // soon as a stop() (or stop+start) makes it stale — enqueueing into a
        // stopped Uploader is a no-op, so late stragglers are harmless too.
        Uploader up = uploader;
        if (up == null) return;
        Thread t = new Thread(() -> {
            int n = 0;
            for (Path root : worldMapRoots()) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> worlds = Files.list(root)) {
                    for (Path world : (Iterable<Path>) worlds::iterator) {
                        String id = world.getFileName().toString();
                        if (!Files.isDirectory(world) || id.startsWith(".")) continue;
                        if (worldFilter != null && !worldFilter.isEmpty() && !id.equals(worldFilter)) continue;
                        n += enqueueWorld(up, root, world);
                    }
                } catch (IOException ignored) {}
            }
            int total = n;
            mc.execute(() -> ChatUtils.info(up.isRunning()
                ? "XaeroTools: queued " + total + " region(s) for upload."
                : "XaeroTools: sync cancelled after " + total + " region(s) — live share was turned off."));
        }, "xt-full-sync");
        t.setDaemon(true);
        t.start();
    }

    private int enqueueWorld(Uploader up, Path root, Path world) {
        int n = 0;
        try (Stream<Path> dims = Files.list(world)) {
            for (Path dim : (Iterable<Path>) dims::iterator) {
                if (!Files.isDirectory(dim) || !RegionRef.isDimDir(dim.getFileName().toString())) continue;
                try (Stream<Path> mws = Files.list(dim)) {
                    for (Path mw : (Iterable<Path>) mws::iterator) {
                        if (!up.isRunning()) return n;
                        if (!Files.isDirectory(mw) || !RegionRef.isMultiworldDir(mw.getFileName().toString())) continue;
                        n += enqueueLayer(up, root, mw);
                        Path caves = mw.resolve("caves");
                        if (uploadCaves.get() && Files.isDirectory(caves)) {
                            try (Stream<Path> layers = Files.list(caves)) {
                                for (Path layer : (Iterable<Path>) layers::iterator) {
                                    if (Files.isDirectory(layer)) n += enqueueLayer(up, root, layer);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return n;
    }

    private int enqueueLayer(Uploader up, Path root, Path layer) {
        int n = 0;
        try (Stream<Path> files = Files.list(layer)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                RegionRef ref = RegionRef.parse(root, file);
                if (ref != null) {
                    up.enqueue(ref);
                    n++;
                }
            }
        } catch (IOException ignored) {}
        return n;
    }

    public String statusLine() {
        if (!enabled.get()) return "off";
        if (uploader == null) return "starting on next tick";
        return String.format("queue %d, sent %d, dropped %d, tracking %d region(s) in %d layer(s)",
            uploader.queueSize(), uploader.sent.get(), uploader.dropped.get(),
            watcher == null ? 0 : watcher.trackedCount(),
            watcher == null ? 0 : watcher.layerCount());
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("settings", settings.toTag());
        return tag;
    }

    @Override
    public XaeroTools fromTag(CompoundTag tag) {
        //? if >=1.21.5 {
        if (tag.contains("settings")) settings.fromTag(tag.getCompoundOrEmpty("settings"));
        //?} else {
        /*if (tag.contains("settings")) settings.fromTag(tag.getCompound("settings"));
        *///?}
        return this;
    }
}
