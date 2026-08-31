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
    /** Smallest useful window: the 3x3 the player is standing in. */
    public static final int MIN_RADIUS = 1;
    /** Vanilla's maximum render distance, and so the most chunks a client can
     *  ever hold. A larger radius would only walk coordinates that are always
     *  unloaded, slowing the sweep's return to real chunks for nothing. */
    public static final int MAX_RADIUS = 32;

    private final TerrainBuffer buffer = new TerrainBuffer();
    private final UploadBackoff backoff = new UploadBackoff();
    private int sampleCounter = 0;
    private int uploadCounter = 0;
    private Object lastWorld = null;
    /** Where the last sweep stopped, so successive sweeps cover the whole
     *  window instead of re-reading its first few chunks forever. */
    private int sweepCursor = 0;
    /** The radius the cursor is an index into. When the radius changes the
     *  window is a different size and the old position means somewhere else,
     *  so the walk restarts rather than resuming into the wrong chunk. */
    private int cursorRadius = 0;

    public static TerrainCapture getInstance() { return INSTANCE; }

    public int pending() { return buffer.pending(); }

    /** Upload intervals left before the next attempt; 0 when uploads are
     *  flowing. Surfaced by /yeedar mapping so a stalled upload is visible in
     *  game rather than only in the log. */
    public int uploadSkipsRemaining() { return backoff.skipsRemaining(); }

    /** Seconds between upload attempts, for describing the wait to the player. */
    public static int uploadIntervalSeconds() { return UPLOAD_INTERVAL / 20; }

    /**
     * The radius a sweep will actually use, in chunks.
     *
     * `configured` is the player's setting, where 0 means follow the render
     * distance. `viewDistance` is the client's clamped view distance — what
     * the server is really sending, which is the only terrain we are allowed
     * to read and therefore the only terrain worth walking.
     *
     * Clamped at both ends so a hand-edited config cannot ask for a window of
     * nothing or one that is mostly unloadable coordinates.
     */
    public static int effectiveRadius(int configured, int viewDistance) {
        int radius = configured > 0 ? configured : viewDistance;
        return Math.max(MIN_RADIUS, Math.min(radius, MAX_RADIUS));
    }

    /** How long one full pass over a window of this radius takes, in seconds.
     *  Sweeps are a fixed size, so a wider window is slower rather than
     *  heavier — that is the trade being made, and the player should see it. */
    public static int fullPassSeconds(int radius) {
        int span = radius * 2 + 1;
        int sweeps = (span * span + CHUNKS_PER_SWEEP - 1) / CHUNKS_PER_SWEEP;
        return sweeps * SAMPLE_INTERVAL / 20;
    }

    /** The radius in force right now, for the status readout. */
    public int currentRadius(MinecraftClient client) {
        return effectiveRadius(YeedarConfig.getInstance().getMappingRadius(),
                client.options.getClampedViewDistance());
    }

    public void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            if (lastWorld != null) { buffer.reset(); lastWorld = null; }
            return;
        }
        // A dimension change or reconnect invalidates every hash we hold — the
        // same chunk coordinates mean somewhere else entirely.
        if (lastWorld != client.world) {
            buffer.reset();
            // A new world is a new attempt: whatever was failing was about
            // somewhere else, and making the player wait out an inherited
            // hour-long penalty would look like the feature is broken.
            backoff.succeeded();
            lastWorld = client.world;
        }

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
     * Sample up to CHUNKS_PER_SWEEP chunks, walking the current radius'
     * window as a flat sequence from where the last sweep left off.
     *
     * The cap counts chunks actually passed to SurfaceSampler.sample(), not
     * chunks that turned out to have changed — an unchanged chunk still costs
     * a full 256-column read, and in the steady state (a player standing in
     * or moving through already-mapped territory) almost every chunk is
     * unchanged. Capping on offer()'s result would let the loop silently do
     * up to a whole window of full samples a sweep instead of 8. Resuming
     * from a cursor rather than always starting at the window's corner means
     * the whole window is covered eventually — see fullPassSeconds — which is
     * fine because terrain is not time-critical. That latency is the thing
     * being traded for a flat cost on the tick, and it is why a wider radius
     * is slower rather than more expensive per tick.
     */
    private void sweep(MinecraftClient client) {
        ChunkPos centre = client.player.getChunkPos();
        // Read every sweep: the player can change render distance, and the
        // server can change what it sends, at any time.
        int radius = effectiveRadius(YeedarConfig.getInstance().getMappingRadius(),
                client.options.getClampedViewDistance());
        if (radius != cursorRadius) {
            sweepCursor = 0;
            cursorRadius = radius;
        }
        int span = radius * 2 + 1;
        int total = span * span;
        int sampled = 0;
        int step = 0;

        for (; step < total && sampled < CHUNKS_PER_SWEEP; step++) {
            int i = (sweepCursor + step) % total;
            int dx = (i % span) - radius;
            int dz = (i / span) - radius;

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
        // Ask the backoff only when there is something to send, so sitting in
        // already-mapped terrain with an empty queue does not burn down a
        // penalty that was never served.
        if (!backoff.allow()) return;
        try {
            List<ChunkPlanes> batch = buffer.drain(TerrainUploader.MAX_BATCH);
            TerrainUploader.upload(batch, buffer,
                    ok -> { if (ok) backoff.succeeded(); else backoff.failed(); });
        } catch (RuntimeException e) {
            backoff.failed();
            // uploadTerrain builds a URI from the configured API URL
            // synchronously; a malformed /yeedar api value throws before any
            // future is even created. That must not crash the game over a
            // typo — log it and try again next interval.
            System.err.println("[Yeedar] terrain upload failed: " + e.getMessage());
        }
    }
}
