package com.yeedar.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * One chunk's surface, in the three planes the server accepts.
 *
 * Every value is validated as it is set rather than at upload time. The server
 * rejects a malformed chunk by failing the entire request, so a single bad
 * column would cost every other chunk batched with it — catching it here names
 * the offending column while the context still exists.
 *
 * Column order is row-major with z outermost: i = z * 16 + x. The server, the
 * tile renderer and this class must agree on that; disagreeing would render
 * every tile transposed with nothing failing anywhere.
 *
 * The world-shape constants below (MAX_MAP_COLOR, MIN_Y/MAX_Y, MIN_CHUNK/MAX_CHUNK)
 * are hand-duplicated against api/terrain.py in the yeetvis repo. Nothing enforces
 * agreement between the two; a future Minecraft version bump that touches one side
 * without the other would corrupt every tile silently.
 */
public final class ChunkPlanes {

    public static final int SIDE = 16;
    public static final int COLUMNS = SIDE * SIDE;
    /** MapColor has 62 entries in 1.21.8; ids run 0..61. */
    public static final int MAX_MAP_COLOR = 61;
    // 1.21 overworld build range. Heightmaps report the y *above* the surface, so
    // the ceiling is one past the top buildable block (matches api/terrain.py).
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;
    /** The mapped world is 20480 blocks square, so chunks run -640..639. */
    public static final int MIN_CHUNK = -640;
    public static final int MAX_CHUNK = 639;

    private final int cx;
    private final int cz;
    private final byte[] colors = new byte[COLUMNS];
    private final short[] floors = new short[COLUMNS];
    private final short[] tops = new short[COLUMNS];
    /** Which columns have been set. A counter alone would double-count a column
     *  written twice, letting requireComplete() pass over a genuine gap. */
    private final boolean[] written = new boolean[COLUMNS];
    private int filled = 0;
    /** Set once any encode succeeds. Blocks further set() so the three planes
     *  can never drift apart after publication, but never blocks re-encoding —
     *  the uploader must be able to re-encode the same object on a retry. */
    private boolean encoded = false;

    public ChunkPlanes(int cx, int cz) {
        if (cx < MIN_CHUNK || cx > MAX_CHUNK || cz < MIN_CHUNK || cz > MAX_CHUNK) {
            throw new IllegalArgumentException(
                    "chunk (" + cx + ", " + cz + ") is outside " + MIN_CHUNK + ".." + MAX_CHUNK);
        }
        this.cx = cx;
        this.cz = cz;
    }

    /** Row-major, z outermost. */
    public static int index(int x, int z) {
        return z * SIDE + x;
    }

    public void set(int index, int mapColor, int floor, int top) {
        if (index < 0 || index >= COLUMNS) {
            throw new IllegalArgumentException(
                    "column index " + index + " outside 0.." + (COLUMNS - 1));
        }
        if (encoded) {
            throw new IllegalStateException(
                    "chunk (" + cx + ", " + cz + ") was already encoded; mutating it now "
                    + "would make its three planes describe different states");
        }
        if (mapColor < 0 || mapColor > MAX_MAP_COLOR) {
            throw new IllegalArgumentException("column " + index + ": map colour " + mapColor);
        }
        if (floor < MIN_Y || floor > MAX_Y || top < MIN_Y || top > MAX_Y) {
            throw new IllegalArgumentException(
                    "column " + index + ": heights " + floor + "/" + top + " outside the world");
        }
        if (top < floor) {
            throw new IllegalArgumentException(
                    "column " + index + ": water top " + top + " below seabed " + floor);
        }
        if (!written[index]) { written[index] = true; filled++; }
        colors[index] = (byte) mapColor;
        floors[index] = (short) floor;
        tops[index] = (short) top;
    }

    public int cx() { return cx; }
    public int cz() { return cz; }

    private void requireComplete() {
        if (filled < COLUMNS) {
            throw new IllegalStateException(
                    "chunk (" + cx + ", " + cz + ") has " + filled + " of " + COLUMNS
                    + " columns; an unfilled column would upload as terrain at y=0");
        }
        // Freezes set(), not encode: the uploader must be able to re-encode the
        // same object any number of times when a batch is retried.
        encoded = true;
    }

    public String encodeColors() {
        requireComplete();
        return Base64.getEncoder().encodeToString(colors);
    }

    public String encodeFloors() { return encodeHeights(floors); }
    public String encodeTops() { return encodeHeights(tops); }

    private String encodeHeights(short[] plane) {
        requireComplete();
        ByteBuffer buffer = ByteBuffer.allocate(COLUMNS * 2).order(ByteOrder.BIG_ENDIAN);
        for (short value : plane) buffer.putShort(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * A stable digest of this chunk's bytes, position excluded.
     *
     * The buffer keeps this so an unchanged chunk is never re-uploaded. Position
     * is excluded because the key is stored separately — two identical chunks
     * genuinely have identical content.
     */
    public int contentHash() {
        int hash = 1;
        for (byte value : colors) hash = hash * 31 + value;
        for (short value : floors) hash = hash * 31 + value;
        for (short value : tops) hash = hash * 31 + value;
        return hash;
    }
}
