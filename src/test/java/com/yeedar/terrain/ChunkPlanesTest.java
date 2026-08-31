package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ChunkPlanesTest {

    private ChunkPlanes flat() {
        ChunkPlanes planes = new ChunkPlanes(3, -4);
        for (int i = 0; i < ChunkPlanes.COLUMNS; i++) planes.set(i, 1, 64, 64);
        return planes;
    }

    @Test
    @DisplayName("index is row-major with z outermost, matching the server")
    void indexOrder() {
        // i = z * 16 + x. The server and the tile renderer both assume this;
        // transposing it would render every tile rotated with nothing failing.
        assertEquals(0, ChunkPlanes.index(0, 0));
        assertEquals(1, ChunkPlanes.index(1, 0));
        assertEquals(16, ChunkPlanes.index(0, 1));
        assertEquals(255, ChunkPlanes.index(15, 15));
    }

    @Test
    @DisplayName("colours encode to exactly 256 bytes")
    void coloursLength() {
        byte[] raw = Base64.getDecoder().decode(flat().encodeColors());
        assertEquals(256, raw.length);
        assertEquals(1, raw[0]);
    }

    @Test
    @DisplayName("heights encode to 512 bytes, big-endian signed")
    void heightsAreBigEndianInt16() {
        ChunkPlanes planes = new ChunkPlanes(0, 0);
        for (int i = 0; i < ChunkPlanes.COLUMNS; i++) planes.set(i, 1, -64, 300);

        byte[] floors = Base64.getDecoder().decode(planes.encodeFloors());
        assertEquals(512, floors.length);
        // -64 as big-endian int16 is 0xFF 0xC0
        assertEquals((byte) 0xFF, floors[0]);
        assertEquals((byte) 0xC0, floors[1]);

        byte[] tops = Base64.getDecoder().decode(planes.encodeTops());
        // 300 is 0x01 0x2C
        assertEquals((byte) 0x01, tops[0]);
        assertEquals((byte) 0x2C, tops[1]);
    }

    @Test
    @DisplayName("a colour above the palette is rejected, not truncated")
    void rejectsUnknownColour() {
        ChunkPlanes planes = new ChunkPlanes(0, 0);
        // The server rejects anything over 61 and drops the whole batch with it,
        // so catching it here saves every other chunk in the request.
        assertThrows(IllegalArgumentException.class,
                () -> planes.set(0, ChunkPlanes.MAX_MAP_COLOR + 1, 64, 64));
    }

    @Test
    @DisplayName("a height outside the world is rejected")
    void rejectsImpossibleHeight() {
        ChunkPlanes planes = new ChunkPlanes(0, 0);
        assertThrows(IllegalArgumentException.class, () -> planes.set(0, 1, -65, 64));
        assertThrows(IllegalArgumentException.class, () -> planes.set(0, 1, 64, 321));
    }

    @Test
    @DisplayName("water below the seabed is rejected as physically impossible")
    void rejectsTopBelowFloor() {
        ChunkPlanes planes = new ChunkPlanes(0, 0);
        assertThrows(IllegalArgumentException.class, () -> planes.set(0, 1, 80, 70));
    }

    @Test
    @DisplayName("chunk coordinates outside the mapped world are rejected")
    void rejectsOutOfRangeChunk() {
        // -640..639. The server enforces this too; failing here names the chunk
        // instead of failing a whole batch on the wire.
        assertThrows(IllegalArgumentException.class, () -> new ChunkPlanes(640, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPlanes(0, -641));
        assertDoesNotThrow(() -> new ChunkPlanes(639, -640));
    }

    @Test
    @DisplayName("identical content hashes identically, different content does not")
    void contentHash() {
        assertEquals(flat().contentHash(), flat().contentHash());

        ChunkPlanes other = flat();
        other.set(0, 2, 64, 64);
        assertNotEquals(flat().contentHash(), other.contentHash());
    }

    @Test
    @DisplayName("the hash covers content only, not position")
    void hashIgnoresPosition() {
        ChunkPlanes here = new ChunkPlanes(1, 1);
        ChunkPlanes there = new ChunkPlanes(9, 9);
        for (int i = 0; i < ChunkPlanes.COLUMNS; i++) {
            here.set(i, 1, 64, 64);
            there.set(i, 1, 64, 64);
        }
        assertEquals(here.contentHash(), there.contentHash());
    }

    @Test
    @DisplayName("an incompletely filled chunk cannot be encoded")
    void rejectsPartialFill() {
        // Every column must be sampled. A half-filled chunk would upload zeroed
        // columns as if they were observed terrain at y=0.
        ChunkPlanes planes = new ChunkPlanes(0, 0);
        planes.set(0, 1, 64, 64);
        assertThrows(IllegalStateException.class, planes::encodeColors);
    }

    @Test
    @DisplayName("a column written twice still counts once toward completeness")
    void rewritingAColumnDoesNotFakeCompleteness() {
        // A naive counter would increment on every set(), so re-sampling one
        // column 256 times would look like a full chunk and upload 255 zeroed
        // columns as terrain at y=0.
        ChunkPlanes planes = new ChunkPlanes(0, 0);
        for (int i = 0; i < ChunkPlanes.COLUMNS; i++) planes.set(0, 1, 64, 64);
        assertThrows(IllegalStateException.class, planes::encodeColors);
    }
}
