package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainBufferTest {

    // The buffer only cares whether content differs, so the block id varies
    // with the colour to keep the two consistent — a chunk claiming one block
    // with two colours is rejected at set().
    private ChunkPlanes chunk(int cx, int cz, int colour) {
        ChunkPlanes planes = new ChunkPlanes(cx, cz);
        String block = "minecraft:test_block_" + colour;
        for (int i = 0; i < ChunkPlanes.COLUMNS; i++) planes.set(i, block, colour, 64, 64);
        return planes;
    }

    @Test
    @DisplayName("a new chunk is held for upload")
    void acceptsNew() {
        TerrainBuffer buffer = new TerrainBuffer();
        assertTrue(buffer.offer(chunk(0, 0, 1)));
        assertEquals(1, buffer.pending());
    }

    @Test
    @DisplayName("re-offering identical content is dropped")
    void dropsUnchanged() {
        // A player standing still re-samples the same chunks forever. Without
        // this the table would grow with time spent, not terrain changed.
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.offer(chunk(0, 0, 1));
        buffer.sent(buffer.drain(10));

        assertFalse(buffer.offer(chunk(0, 0, 1)));
        assertEquals(0, buffer.pending());
    }

    @Test
    @DisplayName("changed content for a known chunk is accepted again")
    void acceptsChanged() {
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.offer(chunk(0, 0, 1));
        buffer.sent(buffer.drain(10));

        assertTrue(buffer.offer(chunk(0, 0, 2)));
    }

    @Test
    @DisplayName("re-offering a chunk still pending replaces it rather than queuing twice")
    void replacesPending() {
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.offer(chunk(0, 0, 1));
        buffer.offer(chunk(0, 0, 2));

        assertEquals(1, buffer.pending());
        assertEquals(1, buffer.drain(10).size());
    }

    @Test
    @DisplayName("drain hands back at most the requested count")
    void drainRespectsLimit() {
        TerrainBuffer buffer = new TerrainBuffer();
        for (int i = 0; i < 5; i++) buffer.offer(chunk(i, 0, 1));

        List<ChunkPlanes> batch = buffer.drain(2);
        assertEquals(2, batch.size());
        assertEquals(3, buffer.pending());
    }

    @Test
    @DisplayName("a failed upload returns its chunks to the queue")
    void failedDrainCanBeRetried() {
        // If drain() alone marked a chunk sent, a failed batch would be
        // silently lost and that terrain would never be re-offered until it
        // changed again.
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.offer(chunk(0, 0, 1));
        List<ChunkPlanes> batch = buffer.drain(10);

        buffer.failed(batch);
        assertEquals(1, buffer.pending());
    }

    @Test
    @DisplayName("a confirmed chunk is not offered again")
    void confirmedIsRemembered() {
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.offer(chunk(0, 0, 1));
        buffer.sent(buffer.drain(10));

        assertFalse(buffer.offer(chunk(0, 0, 1)));
    }

    @Test
    @DisplayName("draining alone does not mark anything sent")
    void drainDoesNotConfirm() {
        // The gap between drain() and sent() is where an in-flight upload
        // lives. Confirming on drain would lose whatever fails.
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.offer(chunk(0, 0, 1));
        buffer.drain(10);

        assertTrue(buffer.offer(chunk(0, 0, 1)),
                "an unconfirmed chunk must still be re-offerable");
    }

    @Test
    @DisplayName("the pending set is bounded and drops oldest first")
    void boundedPending() {
        // If uploads are failing, this must not grow without limit inside a
        // running game.
        TerrainBuffer buffer = new TerrainBuffer(3);
        for (int i = 0; i < 5; i++) buffer.offer(chunk(i, 0, 1));

        assertEquals(3, buffer.pending());
        List<ChunkPlanes> batch = buffer.drain(10);
        assertEquals(2, batch.get(0).cx(), "oldest should have been dropped");
    }

    @Test
    @DisplayName("reset clears both the queue and the memory of what was sent")
    void resetClearsEverything() {
        // Changing dimension or server invalidates every hash we hold: the same
        // chunk coordinates mean somewhere else entirely.
        TerrainBuffer buffer = new TerrainBuffer();
        buffer.sent(List.of(chunk(0, 0, 1)));
        buffer.offer(chunk(1, 1, 1));
        buffer.reset();

        assertEquals(0, buffer.pending());
        assertTrue(buffer.offer(chunk(0, 0, 1)));
    }

    @Test
    @DisplayName("negative chunk coordinates key distinctly")
    void negativeCoordinatesDoNotCollide() {
        // Most of the mapped territory is at negative z. A key that packed the
        // two ints carelessly could alias (-1, 0) onto (0, -1) and silently
        // drop half the map.
        TerrainBuffer buffer = new TerrainBuffer();
        assertTrue(buffer.offer(chunk(-1, 0, 1)));
        assertTrue(buffer.offer(chunk(0, -1, 1)));
        assertTrue(buffer.offer(chunk(-1, -1, 1)));
        assertTrue(buffer.offer(chunk(1, -1, 1)));

        assertEquals(4, buffer.pending());
    }
}
