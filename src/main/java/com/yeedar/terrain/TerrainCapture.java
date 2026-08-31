package com.yeedar.terrain;

import com.yeedar.config.YeedarConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;

/**
 * Samples the chunks around the player and uploads what has changed.
 *
 * Deliberately unhurried. Terrain is not time-critical — a chunk sampled a
 * minute late is no worse — so this trades latency for a flat cost on the
 * client tick, sampling a handful of chunks per sweep rather than a whole
 * render distance at once.
 */
public final class TerrainCapture {

    private static final TerrainCapture INSTANCE = new TerrainCapture();

    /** Ticks between sweeps. 20 ticks is a second. */
    private static final int SAMPLE_INTERVAL = 40;
    /** Ticks between upload attempts. */
    private static final int UPLOAD_INTERVAL = 200;
    /** Chunks sampled per sweep, so one tick never samples a whole view. */
    private static final int CHUNKS_PER_SWEEP = 8;
    /** How far out to sample, in chunks. Well inside a normal render distance. */
    private static final int SAMPLE_RADIUS = 6;

    private final TerrainBuffer buffer = new TerrainBuffer();
    private int sampleCounter = 0;
    private int uploadCounter = 0;
    private Object lastWorld = null;

    public static TerrainCapture getInstance() { return INSTANCE; }

    public int pending() { return buffer.pending(); }

    public void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            if (lastWorld != null) { buffer.reset(); lastWorld = null; }
            return;
        }
        // A dimension change or reconnect invalidates every hash we hold — the
        // same chunk coordinates mean somewhere else entirely.
        if (lastWorld != client.world) { buffer.reset(); lastWorld = client.world; }

        if (!YeedarConfig.getInstance().isMappingEnabled()) return;

        if (++sampleCounter >= SAMPLE_INTERVAL) {
            sampleCounter = 0;
            sweep(client);
        }
        if (++uploadCounter >= UPLOAD_INTERVAL) {
            uploadCounter = 0;
            flush();
        }
    }

    private void sweep(MinecraftClient client) {
        ChunkPos centre = client.player.getChunkPos();
        int sampled = 0;
        for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS && sampled < CHUNKS_PER_SWEEP; dz++) {
            for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS && sampled < CHUNKS_PER_SWEEP; dx++) {
                // Only chunks the vanilla client already has. Never ask for one
                // to be loaded — that would be reading terrain the player is not
                // actually being sent.
                WorldChunk chunk = client.world.getChunkManager()
                        .getWorldChunk(centre.x + dx, centre.z + dz, false);
                if (chunk == null) continue;

                try {
                    ChunkPlanes planes = SurfaceSampler.sample(client.world, chunk);
                    if (planes != null && buffer.offer(planes)) sampled++;
                } catch (RuntimeException e) {
                    // SurfaceSampler returns null for ordinary gaps but throws
                    // when the world does not match what it assumes. On the
                    // client tick that would escape into a render callback and
                    // take the game down, so one bad chunk is logged and skipped.
                    System.err.println("[Yeedar] skipping chunk "
                            + (centre.x + dx) + ", " + (centre.z + dz) + ": " + e.getMessage());
                }
            }
        }
    }

    private void flush() {
        if (buffer.pending() == 0) return;
        List<ChunkPlanes> batch = buffer.drain(TerrainUploader.MAX_BATCH);
        TerrainUploader.upload(batch, buffer);
    }
}
