package dev.xaerotools.companion.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Live terrain preview: renders a coarse 16x16 color summary of the chunks
 * around the player straight from the loaded world and posts changed ones to
 * POST /ingest/v1/preview — so the shared map shows what this client is seeing
 * seconds after it loads, long before Xaero writes the region to disk.
 *
 * Runs on the client tick with a per-tick chunk budget: one sweep over the
 * configured radius is spread across ticks, then rests for the configured
 * delay. A chunk is resent only when its computed pixels change, and sent
 * hashes are committed only after the server accepts the batch, so a dropped
 * or rate-limited upload retries on the next sweep instead of leaving holes.
 *
 * The pixels stay a simplification of what the server's renderer will make of
 * the uploaded region — vanilla map colors, no biome tint on land, no overlay
 * stack — but they borrow the two cues that carry that renderer's shape, so
 * the preview reads as the same map next to real tiles: northwest slope
 * relief, and water composited over the floor it covers rather than painted
 * as a flat surface of its own. Which column a pixel takes follows the local
 * map writer's own rules, XaeroPlus's nether handling included.
 *
 * Wire format (little-endian): "XTPV" u8 version=1 u16 count, then per chunk
 * { i32 cx, i32 cz, 512 bytes: 16x16 RGB565 row-major (index = z*16 + x) }.
 * Pixel value 0 means "nothing here" and is skipped by the server.
 */
public class PreviewScanner {
    /** Chunks rendered per tick — keeps the main-thread cost ~1 ms. */
    private static final int BUDGET_PER_TICK = 64;
    /**
     * The same millisecond spent in a roof-removed dimension: those columns
     * walk the gap under the ceiling block by block instead of reading one
     * heightmap entry, which costs upwards of twenty times a surface column.
     */
    private static final int BUDGET_PER_TICK_ROOF = 16;
    /** Chunks per POST (matches the server's batch cap). */
    private static final int BATCH_MAX = 256;
    /** Forget sent hashes past this many chunks (memory bound). */
    private static final int HASH_CAP = 100_000;

    private static final String NETHER = "minecraft:the_nether";
    /** No column here (nothing visible, or the chunk is not loaded). */
    private static final int NO_COLUMN = Integer.MIN_VALUE;

    // The renderer's light model, mirrored so the relief matches the tiles.
    // Ambient is part colored — the sky's blue, or the nether's red glow, the
    // same split the server makes on a dimension's ambient light — and part
    // white, which the northwest direct term adds to.
    private static final float[] SHADOW_DEFAULT = {0.518f, 0.678f, 1.0f};
    private static final float[] SHADOW_NETHER = {1.0f, 0.0f, 0.0f};
    private static final float AMBIENT_COLORED = 0.2f;
    private static final float AMBIENT_WHITE = 0.5f;
    private static final float MAX_DIRECT = 0.6666667f;
    private static final float DIRECT_QUANTIZED = 0.88388f;
    /** Vanilla's NORMAL map brightness — flat ground's exposure here. */
    private static final float MAP_NORMAL = 220.0f / 255.0f;

    /** Water's transparency and the gray of its texture, from the server's
     *  color table, so a preview lake lands on the tile's own water color. */
    private static final float WATER_ALPHA = 180.0f / 255.0f;
    private static final int WATER_TEXTURE = 177;
    /** The map stores four bits of overlay opacity: fifteen blocks of fluid
     *  swallow all the daylight there is, and more changes nothing. */
    private static final int FLUID_OPACITY_MAX = 15;

    private final Uploader uploader;
    private final IntSupplier radius;
    private final IntSupplier passDelayTicks;
    private final XaeroPlusConfig xaeroPlus = new XaeroPlusConfig();

    private final Map<Long, Integer> sentHash = new HashMap<>();
    private Object levelKey;
    private int cursor = -1; // -1 = between sweeps
    private int cooldown;
    private int sweepCx;
    private int sweepCz;
    private int sweepSide;
    private boolean sweepRoof;
    private float[] sweepShadow = SHADOW_DEFAULT;
    private float sweepNorm = flatNorm(SHADOW_DEFAULT);

    private ByteArrayOutputStream batch;
    private int batchCount;
    private Map<Long, Integer> batchHashes = new HashMap<>();

    // Per-chunk scratch, reused: the client tick is the only caller.
    /** Column heights over [-1..15]^2 (index (z+1)*17 + x+1), so the north and
     *  northwest slope taps are right across a chunk edge. */
    private final int[] heights = new int[17 * 17];
    private final float[] shade = new float[3];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public PreviewScanner(Uploader uploader, IntSupplier radius, IntSupplier passDelayTicks) {
        this.uploader = uploader;
        this.radius = radius;
        this.passDelayTicks = passDelayTicks;
    }

    /** One game tick. Call only while the link is enabled and preview is on. */
    public void tick(String dim) {
        if (mc.player == null || mc.level == null) {
            cursor = -1;
            return;
        }
        if (levelKey != mc.level) {
            // New world/dimension: everything must be re-sent.
            levelKey = mc.level;
            sentHash.clear();
            cursor = -1;
            cooldown = 0;
        }
        if (cursor < 0) {
            if (cooldown-- > 0) return;
            startSweep(dim);
        }
        int budget = sweepRoof ? BUDGET_PER_TICK_ROOF : BUDGET_PER_TICK;
        int total = sweepSide * sweepSide;
        while (budget-- > 0 && cursor < total) {
            int dx = cursor % sweepSide - (sweepSide - 1) / 2;
            int dz = cursor / sweepSide - (sweepSide - 1) / 2;
            cursor++;
            scanChunk(sweepCx + dx, sweepCz + dz, dim);
        }
        if (cursor >= total) {
            flush(dim);
            cursor = -1;
            cooldown = Math.max(1, passDelayTicks.getAsInt());
        }
    }

    private void startSweep(String dim) {
        sweepCx = mc.player.blockPosition().getX() >> 4;
        sweepCz = mc.player.blockPosition().getZ() >> 4;
        sweepSide = 2 * Math.max(1, radius.getAsInt()) + 1;
        // Resolved once per sweep, not once per chunk: it reaches for the
        // XaeroPlus config file, and a sweep that changed rules halfway
        // through would leave two different maps stitched together.
        boolean nether = NETHER.equals(dim);
        sweepRoof = nether && xaeroPlus.netherCaveFix();
        sweepShadow = nether ? SHADOW_NETHER : SHADOW_DEFAULT;
        sweepNorm = flatNorm(sweepShadow);
        cursor = 0;
        if (sentHash.size() > HASH_CAP) sentHash.clear();
    }

    private void scanChunk(int cx, int cz, String dim) {
        LevelChunk chunk = chunkAt(cx, cz);
        if (chunk == null) return;
        byte[] pix = renderChunk(chunk);
        if (pix == null) return; // nothing visible in it
        int hash = Arrays.hashCode(pix);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Integer prev = sentHash.get(key);
        if (prev != null && prev == hash) return;
        appendToBatch(cx, cz, pix, key, hash, dim);
    }

    private LevelChunk chunkAt(int cx, int cz) {
        return mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
    }

    private void appendToBatch(int cx, int cz, byte[] pix, long key, int hash, String dim) {
        if (batch == null) {
            batch = new ByteArrayOutputStream(7 + BATCH_MAX * 520);
            batch.writeBytes(new byte[] {'X', 'T', 'P', 'V', 1, 0, 0}); // count patched on flush
            batchCount = 0;
            batchHashes = new HashMap<>();
        }
        batch.writeBytes(new byte[] {
            (byte) cx, (byte) (cx >> 8), (byte) (cx >> 16), (byte) (cx >> 24),
            (byte) cz, (byte) (cz >> 8), (byte) (cz >> 16), (byte) (cz >> 24),
        });
        batch.writeBytes(pix);
        batchHashes.put(key, hash);
        if (++batchCount >= BATCH_MAX) flush(dim);
    }

    private void flush(String dim) {
        if (batch == null || batchCount == 0) {
            batch = null;
            return;
        }
        byte[] body = batch.toByteArray();
        body[5] = (byte) batchCount;
        body[6] = (byte) (batchCount >> 8);
        Map<Long, Integer> sent = batchHashes;
        batch = null;
        batchHashes = new HashMap<>();
        // Commit only on acceptance: a 429/failed batch recomputes and resends
        // on a later sweep instead of leaving permanent holes.
        uploader.postPreview(dim, body, () -> sentHash.putAll(sent));
    }

    /** 16x16 RGB565 for one chunk, or null when it holds nothing visible. */
    private byte[] renderChunk(LevelChunk chunk) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int cx = baseX >> 4;
        int cz = baseZ >> 4;
        // The north and west neighbours carry the slope taps of the first row
        // and column. One that is not loaded leaves its columns unknown, and
        // the pixels reading them shade flat rather than invent a cliff along
        // the chunk edge.
        LevelChunk west = chunkAt(cx - 1, cz);
        LevelChunk north = chunkAt(cx, cz - 1);
        LevelChunk northWest = chunkAt(cx - 1, cz - 1);
        for (int dz = -1; dz < 16; dz++) {
            for (int dx = -1; dx < 16; dx++) {
                LevelChunk c = dx < 0 ? (dz < 0 ? northWest : west) : (dz < 0 ? north : chunk);
                heights[(dz + 1) * 17 + dx + 1] =
                    c == null ? NO_COLUMN : surfaceY(c, baseX + dx, baseZ + dz);
            }
        }
        byte[] out = new byte[512];
        boolean any = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = (z + 1) * 17 + x + 1;
                int packed = heights[i] == NO_COLUMN
                    ? 0
                    : columnColor(chunk, baseX + x, heights[i], baseZ + z, heights[i - 17], heights[i - 18]);
                if (packed != 0) any = true;
                int o = (z * 16 + x) * 2;
                out[o] = (byte) packed;
                out[o + 1] = (byte) (packed >> 8);
            }
        }
        return any ? out : null;
    }

    /**
     * The y whose block the pixel paints: the column's mapped surface, then
     * down through any fluid to the floor it covers — that floor is the height
     * the map stores for a wet column, and shading it is what gives lakes
     * their bottom relief — and past colorless tops like glass or grass
     * overlays. {@link #NO_COLUMN} when the column holds nothing to paint.
     */
    private int surfaceY(LevelChunk chunk, int wx, int wz) {
        int minY = chunk.getMinY();
        int y = sweepRoof
            ? roofRemovedSurface(chunk, wx, wz, minY)
            : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
        if (y <= minY) return NO_COLUMN;
        pos.set(wx, y, wz);
        while (y > minY && !chunk.getBlockState(pos).getFluidState().isEmpty()) {
            pos.setY(--y);
        }
        int steps = 0;
        while (chunk.getBlockState(pos).getMapColor(mc.level, pos) == MapColor.NONE) {
            if (y <= minY || ++steps > 8) return NO_COLUMN;
            pos.setY(--y);
        }
        return y;
    }

    /**
     * XaeroPlus's nether column, mirrored from its Nether Cave Fix: the fix
     * forces Xaero's writer into full-cave mode there, and that descent enters
     * the first solid block from the top — the bedrock roof — walks out the
     * far side of it, and maps the first floor under the gap below. The
     * player's altitude never enters into it, so the column is the same
     * wherever the client happens to be standing.
     */
    private int roofRemovedSurface(LevelChunk chunk, int wx, int wz, int minY) {
        boolean underAir = true;
        boolean entering = true;
        for (int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz); y >= minY; y--) {
            pos.set(wx, y, wz);
            BlockState state = chunk.getBlockState(pos);
            // Once inside the roof, a fluid surface (nether lava) is a floor
            // like any other; on the way in, it is not something to stop on.
            if (!entering && !state.getFluidState().isEmpty()) return y;
            if (state.isAir()) {
                underAir = true;
                continue;
            }
            if (!underAir) continue; // still inside the roof
            if (entering) {
                if (isGround(state)) {
                    underAir = false;
                    entering = false;
                }
                continue;
            }
            return y;
        }
        return minY - 1;
    }

    /**
     * What the writer accepts as the ground it has entered: solid and opaque,
     * and none of the things it refuses to stand on — burnable, replaceable,
     * or destroyed by pistons. Xaero asks its own translucency cache where
     * this asks {@link BlockState#canOcclude()}, which answers the same for
     * anything that can be a roof.
     */
    private static boolean isGround(BlockState state) {
        return state.canOcclude()
            && !state.canBeReplaced()
            && !state.ignitedByLava()
            && state.getPistonPushReaction() != PushReaction.DESTROY;
    }

    /** RGB565 of one column's visible surface; 0 = nothing there. */
    private int columnColor(LevelChunk chunk, int wx, int y, int wz, int northH, int nwH) {
        pos.set(wx, y, wz);
        MapColor color = chunk.getBlockState(pos).getMapColor(mc.level, pos);
        if (color == MapColor.NONE) return 0;
        slope(y, northH, nwH);
        // Terrain depth: the renderer lifts high ground within a narrow band.
        float depth = Math.min(1.0f, Math.max(0.9f, y / 63.0f));
        float r = ((color.col >> 16) & 0xFF) * shade[0] * depth;
        float g = ((color.col >> 8) & 0xFF) * shade[1] * depth;
        float b = (color.col & 0xFF) * shade[2] * depth;

        // A fluid over that floor is an overlay in the map's model, not a
        // surface of its own: it keeps its own full-daylight color and lets
        // the shaded floor through, dimmed by the light the column swallows on
        // the way down. That is where real tiles get their lake and sea depth.
        pos.setY(y + 1);
        BlockState above = chunk.getBlockState(pos);
        FluidState fluid = above.getFluidState();
        if (!fluid.isEmpty()) {
            int deep = 1;
            while (deep < FLUID_OPACITY_MAX) {
                pos.setY(y + 1 + deep);
                if (chunk.getBlockState(pos).getFluidState().isEmpty()) break;
                deep++;
            }
            int fc;
            float alpha;
            if (fluid.is(FluidTags.WATER)) {
                // The color the server's table gives water: its gray texture
                // average carrying this biome's water tint.
                int tint = chunk.getNoiseBiome(wx >> 2, (y + 1) >> 2, wz >> 2).value().getWaterColor();
                fc = (((tint >> 16) & 0xFF) * WATER_TEXTURE / 255) << 16
                    | (((tint >> 8) & 0xFF) * WATER_TEXTURE / 255) << 8
                    | ((tint & 0xFF) * WATER_TEXTURE / 255);
                alpha = WATER_ALPHA;
            } else {
                pos.setY(y + 1);
                fc = above.getMapColor(mc.level, pos).col;
                alpha = 1.0f; // lava and friends cover their floor outright
            }
            float through = (1.0f - alpha) * (9 + Math.max(0, 15 - deep)) / 24.0f;
            r = r * through + ((fc >> 16) & 0xFF) * alpha;
            g = g * through + ((fc >> 8) & 0xFF) * alpha;
            b = b * through + (fc & 0xFF) * alpha;
        }
        int packed = (byteOf(r) >> 3) << 11 | (byteOf(g) >> 2) << 5 | (byteOf(b) >> 3);
        // 0 is the "empty" sentinel; nudge legitimately-black pixels off it.
        return packed == 0 ? 0x0841 : packed;
    }

    /**
     * Northwest slope relief for one pixel, per channel, into {@link #shade}:
     * the cross product of the north and northwest height deltas, quantized
     * to tenths, exactly as the server's renderer does it — that quantization
     * is what gives the map its stepped terracing rather than a smooth ramp.
     *
     * Scaled so flat ground comes out at vanilla's NORMAL map brightness: the
     * colors here are vanilla map colors, which already carry the map's own
     * daylight, so what is borrowed from the renderer is the shape of the
     * relief and not its exposure. An unknown neighbour shades flat.
     */
    private void slope(int h, int northH, int nwH) {
        int vs = 0;
        int ds = 0;
        if (northH != NO_COLUMN && nwH != NO_COLUMN) {
            vs = Math.max(-128, Math.min(127, h - northH));
            ds = Math.max(-128, Math.min(127, h - nwH));
        }
        float cos = 0.0f;
        float crossZ = -vs;
        if (crossZ < 1.0f) {
            if (vs == 1 && ds == 1) {
                cos = 1.0f;
            } else {
                float crossX = vs - ds;
                float mag = (float) Math.sqrt(crossX * crossX + 1.0f + crossZ * crossZ);
                cos = (float) ((1.0f - crossZ) / mag / Math.sqrt(2.0));
            }
        }
        float direct = cos == 1.0f
            ? MAX_DIRECT
            : cos > 0.0f ? (float) Math.ceil(cos * 10.0f) / 10.0f * MAX_DIRECT * DIRECT_QUANTIZED : 0.0f;
        float white = AMBIENT_WHITE + direct;
        for (int c = 0; c < 3; c++) {
            shade[c] = (sweepShadow[c] * AMBIENT_COLORED + white) * sweepNorm;
        }
    }

    /** What {@link #slope} gives flat ground, averaged over the channels and
     *  inverted: the factor that puts flat ground on {@link #MAP_NORMAL}. */
    private static float flatNorm(float[] shadow) {
        float flat = AMBIENT_WHITE
            + (float) Math.ceil(10.0 / Math.sqrt(2.0)) / 10.0f * MAX_DIRECT * DIRECT_QUANTIZED;
        float ambient = (shadow[0] + shadow[1] + shadow[2]) / 3.0f * AMBIENT_COLORED;
        return MAP_NORMAL / (ambient + flat);
    }

    private static int byteOf(float v) {
        return v <= 0.0f ? 0 : v >= 255.0f ? 255 : (int) v;
    }
}
