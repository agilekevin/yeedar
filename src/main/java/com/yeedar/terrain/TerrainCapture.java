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
    /** Where the last sweep stopped, so successive sweeps cover the whole
     *  window instead of re-reading its first few chunks forever. */
    private int sweepCursor = 0;

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

    /**
     * Sample up to CHUNKS_PER_SWEEP chunks, walking the SAMPLE_RADIUS window
     * as a flat sequence from where the last sweep left off.
     *
     * The cap counts chunks actually passed to SurfaceSampler.sample(), not
     * chunks that turned out to have changed — an unchanged chunk still costs
     * a full 256-column read, and in the steady state (a player standing in
     * or moving through already-mapped territory) almost every chunk is
     * unchanged. Capping on offer()'s result would let the loop silently do
     * up to 169 full samples a sweep instead of 8. Resuming from a cursor
     * rather than always starting at the window's corner means the whole
     * window is covered over roughly 21 sweeps (~42 seconds), which is fine
     * because terrain is not time-critical — that latency is the thing being
     * traded for a flat cost on the tick.
     */
    private void sweep(MinecraftClient client) {
        ChunkPos centre = client.player.getChunkPos();
        int span = SAMPLE_RADIUS * 2 + 1;
        int total = span * span;
        int sampled = 0;
        int step = 0;

        for (; step < total && sampled < CHUNKS_PER_SWEEP; step++) {
            int i = (sweepCursor + step) % total;
            int dx = (i % span) - SAMPLE_RADIUS;
            int dz = (i / span) - SAMPLE_RADIUS;

            // Only chunks the vanilla client already has. Never ask for one
            // to be loaded — that would be reading terrain the player is not
            // actually being sent.
            WorldChunk chunk = client.world.getChunkManager()
                    .getWorldChunk(centre.x + dx, centre.z + dz, false);
            if (chunk == null) continue;   // not loaded: no work done, does not count

            try {
                ChunkPlanes planes = SurfaceSampler.sample(client.world, chunk);
                // Count the sample whatever it yielded: an unchanged chunk cost
                // a full 256-column read just the same. Counting only changes
                // would let the loop run the entire window every sweep.
                sampled++;
                if (planes != null) buffer.offer(planes);
            } catch (RuntimeException e) {
                // SurfaceSampler returns null for ordinary gaps but throws
                // when the world does not match what it assumes. On the
                // client tick that would escape into a render callback and
                // take the game down, so one bad chunk is logged and skipped.
                System.err.println("[Yeedar] skipping chunk "
                        + (centre.x + dx) + ", " + (centre.z + dz) + ": " + e.getMessage());
            }
        }
        sweepCursor = (sweepCursor + step) % total;
    }

    private void flush() {
        if (buffer.pending() == 0) return;
        try {
            List<ChunkPlanes> batch = buffer.drain(TerrainUploader.MAX_BATCH);
            TerrainUploader.upload(batch, buffer);
        } catch (RuntimeException e) {
            // uploadTerrain builds a URI from the configured API URL
            // synchronously; a malformed /yeedar api value throws before any
            // future is even created. That must not crash the game over a
            // typo — log it and try again next interval.
            System.err.println("[Yeedar] terrain upload failed: " + e.getMessage());
        }
    }
}
