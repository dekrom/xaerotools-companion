package dev.xaerotools.companion.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

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
 * Wire format (little-endian): "XTPV" u8 version=1 u16 count, then per chunk
 * { i32 cx, i32 cz, 512 bytes: 16x16 RGB565 row-major (index = z*16 + x) }.
 * Pixel value 0 means "nothing here" and is skipped by the server.
 */
public class PreviewScanner {
    /** Chunks rendered per tick — keeps the main-thread cost ~1 ms. */
    private static final int BUDGET_PER_TICK = 96;
    /** Chunks per POST (matches the server's batch cap). */
    private static final int BATCH_MAX = 256;
    /** Forget sent hashes past this many chunks (memory bound). */
    private static final int HASH_CAP = 100_000;

    private final Uploader uploader;
    private final IntSupplier radius;
    private final IntSupplier passDelayTicks;

    private final Map<Long, Integer> sentHash = new HashMap<>();
    private Object levelKey;
    private int cursor = -1; // -1 = between sweeps
    private int cooldown;
    private int sweepCx;
    private int sweepCz;
    private int sweepSide;

    private ByteArrayOutputStream batch;
    private int batchCount;
    private Map<Long, Integer> batchHashes = new HashMap<>();

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
            startSweep();
        }
        int budget = BUDGET_PER_TICK;
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

    private void startSweep() {
        sweepCx = mc.player.blockPosition().getX() >> 4;
        sweepCz = mc.player.blockPosition().getZ() >> 4;
        sweepSide = 2 * Math.max(1, radius.getAsInt()) + 1;
        cursor = 0;
        if (sentHash.size() > HASH_CAP) sentHash.clear();
    }

    private void scanChunk(int cx, int cz, String dim) {
        LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return;
        byte[] pix = renderChunk(chunk);
        if (pix == null) return; // nothing visible in it
        int hash = Arrays.hashCode(pix);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Integer prev = sentHash.get(key);
        if (prev != null && prev == hash) return;
        appendToBatch(cx, cz, pix, key, hash, dim);
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
        boolean ceiling = mc.level.dimensionType().hasCeiling();
        int anchorY = mc.player.blockPosition().getY() + 8;
        // Highway players sit just under the roof (y≈120): +8 would anchor in
        // the open air above the bedrock band (logicalHeight-5..-1) and the
        // descent would stop ON the roof, painting every chunk bedrock-gray.
        // Clamp below the band; on-roof players get the under-roof view too,
        // matching how the world map itself writes the nether.
        if (ceiling) anchorY = Math.min(anchorY, mc.level.dimensionType().logicalHeight() - 6);
        byte[] out = new byte[512];
        boolean any = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int[] prevRow = new int[16];
        Arrays.fill(prevRow, Integer.MIN_VALUE);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int y = ceiling
                    ? ceilingSurface(chunk, pos, wx, anchorY, wz)
                    : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int packed = columnColor(chunk, pos, wx, y, wz, prevRow[x]);
                prevRow[x] = y;
                if (packed != 0) any = true;
                int i = (z * 16 + x) * 2;
                out[i] = (byte) packed;
                out[i + 1] = (byte) (packed >> 8);
            }
        }
        return any ? out : null;
    }

    /** Under a ceiling (nether): the first floor below the player's level. */
    private int ceilingSurface(LevelChunk chunk, BlockPos.MutableBlockPos pos, int wx, int startY, int wz) {
        int y = startY;
        int floor = startY - 120;
        // Inside solid ground/roof at the anchor: drop to the first air gap.
        pos.set(wx, y, wz);
        int guard = 0;
        while (guard++ < 64 && y > floor && !chunk.getBlockState(pos).isAir()) {
            pos.setY(--y);
        }
        // Then the first non-air below is the visible surface.
        while (y > floor && chunk.getBlockState(pos).isAir()) {
            pos.setY(--y);
        }
        return y;
    }

    /** RGB565 of one column's visible surface; 0 = nothing there. */
    private int columnColor(LevelChunk chunk, BlockPos.MutableBlockPos pos, int wx, int y, int wz, int northY) {
        if (y <= chunk.getMinY()) return 0;
        pos.set(wx, y, wz);
        BlockState state = chunk.getBlockState(pos);
        int rgb;
        if (!state.getFluidState().isEmpty()) {
            // Water/lava: fluid color shaded by depth, like the vanilla map.
            int depth = 0;
            while (depth < 10 && y - depth - 1 > chunk.getMinY()) {
                pos.setY(y - depth - 1);
                if (chunk.getBlockState(pos).getFluidState().isEmpty()) break;
                depth++;
            }
            pos.setY(y);
            rgb = state.getMapColor(mc.level, pos).col;
            float f = depth <= 2 ? 1.0f : depth <= 5 ? 0.85f : 0.7f;
            rgb = scaleRgb(rgb, f);
        } else {
            MapColor color = state.getMapColor(mc.level, pos);
            int steps = 0;
            // Skip colorless tops (grass overlays, glass, air pockets).
            while (color == MapColor.NONE && steps++ < 8 && y - 1 > chunk.getMinY()) {
                pos.setY(--y);
                state = chunk.getBlockState(pos);
                color = state.getMapColor(mc.level, pos);
            }
            if (color == MapColor.NONE) return 0;
            rgb = color.col;
            // Vanilla-style three-tier slope shading against the column north.
            if (northY != Integer.MIN_VALUE) {
                if (y > northY) rgb = scaleRgb(rgb, 1.12f);
                else if (y < northY) rgb = scaleRgb(rgb, 0.85f);
            }
        }
        int packed = ((rgb >> 19) & 0x1F) << 11 | ((rgb >> 10) & 0x3F) << 5 | ((rgb >> 3) & 0x1F);
        // 0 is the "empty" sentinel; nudge legitimately-black pixels off it.
        return packed == 0 ? 0x0841 : packed;
    }

    private static int scaleRgb(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }
}
