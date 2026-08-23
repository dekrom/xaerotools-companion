package dev.xaerotools.companion.sync;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The XaeroPlus settings the live preview has to follow, read from this
 * instance's own config so the preview paints the columns the local map
 * writer paints instead of a hardcoded guess.
 *
 * config/xaeroplus.txt is the seam on purpose: XaeroPlus's settings registry
 * is internal and moves between releases, while the file is a stable
 * "[XP] &lt;setting name&gt;:&lt;value&gt;" list. It is re-read whenever its
 * timestamp changes, so a setting toggled in game lands on the next sweep
 * rather than the next launch.
 */
public class XaeroPlusConfig {
    private static final String MOD_ID = "xaeroplus";
    private static final String FILE = "xaeroplus.txt";
    private static final String NETHER_CAVE_FIX = "Nether Cave Fix";
    /** XaeroPlus's own default, which is what runs until the file exists. */
    private static final boolean NETHER_CAVE_FIX_DEFAULT = true;

    private long stamp = Long.MIN_VALUE;
    private boolean netherCaveFix = NETHER_CAVE_FIX_DEFAULT;

    /**
     * Is XaeroPlus rewriting the nether as a full cave layer — the bedrock
     * roof entered and skipped, the first floor under it mapped? With no
     * XaeroPlus installed the nether is written like any other surface, which
     * means the roof itself.
     */
    public boolean netherCaveFix() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            // Never saved: XaeroPlus is running on its defaults. Forget the
            // stamp so a file written later is picked up.
            stamp = Long.MIN_VALUE;
            return NETHER_CAVE_FIX_DEFAULT;
        }
        if (mtime != stamp) {
            stamp = mtime;
            netherCaveFix = readBool(file, NETHER_CAVE_FIX, NETHER_CAVE_FIX_DEFAULT);
        }
        return netherCaveFix;
    }

    private static boolean readBool(Path file, String name, boolean fallback) {
        String prefix = "[XP] " + name + ":";
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith(prefix)) {
                    return Boolean.parseBoolean(line.substring(prefix.length()).trim());
                }
            }
        } catch (IOException ignored) {
            // Unreadable or half-written: keep the fallback for this pass.
        }
        return fallback;
    }
}
