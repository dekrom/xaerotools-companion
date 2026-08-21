package dev.xaerotools.companion.sync;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One live region file inside a world-map tree, resolved to the parameters
 * POST /ingest/v1/region expects. Mirrors the server's naming rules: only
 * {@code <world>/<dim>/<mw>/<rx>_<rz>.zip|.xaero} (optionally with a
 * {@code caves/<n>} level in between) is live data — cache dirs, the mod's
 * {@code <version>_backup_<n>} snapshots, temp files and sync conflicts all
 * fail to parse and are never uploaded.
 */
public record RegionRef(String world, String dim, String mw, Integer cave, int rx, int rz, Path file) {
    private static final Pattern REGION = Pattern.compile("(-?\\d+)_(-?\\d+)\\.(zip|xaero)");
    private static final Pattern LEGACY_MW = Pattern.compile("mw-?\\d+,-?\\d+,-?\\d+");

    public static boolean isMultiworldDir(String name) {
        return name.startsWith("mw$") || name.startsWith("cm$") || LEGACY_MW.matcher(name).matches();
    }

    /** World-map dimension folders: null/DIM0/DIM-1/DIM1 or an escaped custom id. */
    public static boolean isDimDir(String name) {
        return name.equals("null") || name.equals("DIM0") || name.equals("DIM-1") || name.equals("DIM1") || name.contains("$");
    }

    /**
     * Parses a file against a world-map root. Returns null for anything that
     * is not a live region file directly inside a layer dir.
     */
    public static RegionRef parse(Path worldMapRoot, Path file) {
        Path rel;
        try {
            rel = worldMapRoot.relativize(file);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int n = rel.getNameCount();
        if (n != 4 && n != 6) return null;
        String world = rel.getName(0).toString();
        String dim = rel.getName(1).toString();
        String mw = rel.getName(2).toString();
        if (world.startsWith(".") || !isDimDir(dim) || !isMultiworldDir(mw)) return null;
        Integer cave = null;
        if (n == 6) {
            if (!rel.getName(3).toString().equals("caves")) return null;
            try {
                cave = Integer.parseInt(rel.getName(4).toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Matcher m = REGION.matcher(rel.getName(n - 1).toString());
        if (!m.matches()) return null;
        return new RegionRef(world, dim, mw, cave, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), file);
    }
}
