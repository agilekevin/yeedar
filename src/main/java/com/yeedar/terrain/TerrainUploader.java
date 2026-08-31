package com.yeedar.terrain;

import com.yeedar.api.YeetVisClient;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns a buffer's worth of sampled chunks into requests the server will accept.
 */
public final class TerrainUploader {

    /** The server's TERRAIN_MAX_BATCH. Beyond it the whole request is a 422. */
    public static final int MAX_BATCH = 1024;

    private TerrainUploader() {}

    /**
     * Send one batch, returning the chunks to the buffer if it did not land.
     *
     * The outcome is applied on the client thread: TerrainBuffer is documented
     * as not thread-safe, and the HTTP response arrives on a pool thread.
     * `onOutcome` runs there too, for the same reason — it drives the caller's
     * retry backoff, which is read from the tick.
     */
    public static void upload(List<ChunkPlanes> batch, TerrainBuffer buffer,
                              Consumer<Boolean> onOutcome) {
        YeetVisClient.uploadTerrain(batch).thenAccept(ok ->
                MinecraftClient.getInstance().execute(() -> {
                    if (ok) buffer.sent(batch);
                    else buffer.failed(batch);
                    onOutcome.accept(ok);
                }));
    }
}
