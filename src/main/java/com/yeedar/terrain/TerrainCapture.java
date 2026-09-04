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

    /** Ticks between sweeps. 20 ticks is a second.
     *
     *  Every tick, rather than in a burst every two seconds. The burst was the
     *  worse shape twice over: it put 2048 column reads into one tick — double
     *  what vanilla's own map item sustains — and then idled for 39, which is
     *  both the stutter risk and a throughput ceiling. Spread out, the same
     *  work costs a quarter of that per tick and can be raised well past what
     *  the burst could afford. */
    private static final int SAMPLE_INTERVAL = 1;
    /** Ticks between upload attempts. */
    private static final int UPLOAD_INTERVAL = 200;
    /** Chunks sampled per sweep, so one tick never samples a whole view.
     *
     *  Two per tick is 40 chunks/s, which keeps a window out to radius 21
     *  fully sampled at horse speed (see keepsUpWith). It costs 512 column
     *  reads per tick — half of what vanilla's map item does every tick for a
     *  player holding a map, and a quarter of the old burst's peak. */
    private static final int CHUNKS_PER_SWEEP = 2;
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
    /** The dimension the buffered chunks were sampled in. Held rather than
     *  read at upload time: the player may have walked through a portal since,
     *  and asking "where am I now" would relabel them, which is the bug this
     *  change exists to fix. */
    private String sampledDimension = Dimensions.UNKNOWN;
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
        if (configured > 0) {
            // An explicit setting is allowed to go patchy. Someone who wants
            // coverage over fidelity, or who never travels fast, should be
            // able to ask for it — they are told what it costs when they do.
            return Math.max(MIN_RADIUS, Math.min(configured, MAX_RADIUS));
        }
        int radius = Math.min(viewDistance, smoothMaxRadius());
        return Math.max(MIN_RADIUS, Math.min(radius, MAX_RADIUS));
    }

    /**
     * The widest window the sample rate can keep fully covered at horse speed.
     *
     * This is the ceiling on the automatic radius. Following the render
     * distance is the right default only up to the point where the sweep can
     * still get round the window before the player leaves it; past that, a
     * wider setting does not map more, it maps the same amount with holes in
     * it, because the cursor is spread too thin to return to any given chunk
     * in time. Better to map a smaller area completely.
     *
     * Derived rather than written down, so that tuning the rate moves the
     * ceiling with it instead of leaving a stale constant behind.
     */
    public static int smoothMaxRadius() {
        double chunksPerSecond = CHUNKS_PER_SWEEP * 20.0 / SAMPLE_INTERVAL;
        int radius = (int) Math.floor((16.0 * chunksPerSecond / FAST_HORSE_BPS - 1) / 2);
        return Math.max(MIN_RADIUS, Math.min(radius, MAX_RADIUS));
    }

    /**
     * The fastest a player can travel, in blocks per second, and still have
     * every chunk sampled before it falls out the back of the window.
     *
     * A chunk stays in the window while the player crosses it, so its time
     * there is span*16/v seconds. The cursor walks uniformly at
     * CHUNKS_PER_SWEEP per SAMPLE_INTERVAL over span^2 positions, so it
     * revisits any one of them every span^2/rate seconds. Setting those equal
     * and solving for v gives 16*rate/span.
     *
     * Note the direction: this falls as the radius grows. A wider window
     * spreads the same budget over more chunks, so it tracks a SLOWER player,
     * not a faster one — widening the radius cannot fix outrunning the sweep,
     * only raising the rate can.
     *
     * An approximation: the real cursor is a raster walk over a window that is
     * itself moving, so coverage is better or worse than uniform depending on
     * how the two line up. It is the right order of magnitude and the right
     * shape, which is what picking the constants needs.
     */
    public static double keepsUpWith(int radius) {
        int span = radius * 2 + 1;
        double chunksPerSecond = CHUNKS_PER_SWEEP * 20.0 / SAMPLE_INTERVAL;
        return 16.0 * chunksPerSecond / span;
    }

    /** Top speed of a maxed-out horse, in blocks per second. Minecraft rolls a
     *  horse's movement speed as (0.45 + 3 * rand * 0.3) * 0.25, so 0.3375 is
     *  the ceiling; that measures at about this. The fastest thing a player is
     *  likely to be mapping from, and so what the sample rate is sized for. */
    public static final double FAST_HORSE_BPS = 14.5;

    /** How long one full pass over a window of this radius takes, in seconds.
     *  Sweeps are a fixed size, so a wider window is slower rather than
     *  heavier — that is the trade being made, and the player should see it. */
    public static int fullPassSeconds(int radius) {
        int span = radius * 2 + 1;
        int sweeps = (span * span + CHUNKS_PER_SWEEP - 1) / CHUNKS_PER_SWEEP;
        return Math.max(1, sweeps * SAMPLE_INTERVAL / 20);
    }

    /**
     * Why a requested radius cannot be fully mapped, or null if it can.
     *
     * `clientView` is the player's own render-distance setting; `serverView` is
     * what the server actually sends, which is what bounds us. Reporting only
     * the effective number called it "your render distance", which reads as a
     * bug when the server's limit is lower than the setting — the player sees a
     * number they never chose and reasonably concludes we defaulted.
     */
    public static String reachNote(int requested, int clientView, int serverView) {
        int effective = serverView > 0 ? Math.min(clientView, serverView) : clientView;
        if (requested <= effective) return null;
        if (serverView > 0 && serverView < clientView) {
            return "The server only sends you " + serverView + " chunks (your render "
                    + "distance is set to " + clientView + "), so chunks past "
                    + serverView + " never load and cannot be mapped.";
        }
        return "Beyond your render distance of " + clientView
                + ", so those chunks never load and cannot be mapped.";
    }

    /** What the server is actually sending, or 0 when it has not said. */
    public static int serverViewDistance(MinecraftClient client) {
        int clamped = client.options.getClampedViewDistance();
        int mine = client.options.getViewDistance().getValue();
        // getClampedViewDistance is min(mine, server) when the server declared
        // one, so a clamp below the setting is the server's number.
        return clamped < mine ? clamped : 0;
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
            sampledDimension = Dimensions.of(client.world);
        }

        if (!YeedarConfig.getInstance().isMappingEnabled()) return;

        // Somewhere we cannot name is somewhere we must not file chunks under.
        // Storing them as "unknown" would poison a layer nobody could identify
        // later, which is exactly the cleanup this change follows.
        if (!Dimensions.isMappable(sampledDimension)) return;

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
            TerrainUploader.upload(sampledDimension, batch, buffer,
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
