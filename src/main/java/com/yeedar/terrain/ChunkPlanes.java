package com.yeedar.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Columns carry a palette index, not a MapColor id. The palette pairs each
 * block with the MapColor vanilla gives it, so the server can render exactly
 * what it rendered before while still being able to tell blocks apart that
 * share a colour — mycelium and purple wool are both MapColor.PURPLE, and a
 * colour-only capture cannot distinguish them however the palette is tuned.
 * Sending the identity means a future colour change is a re-render rather than
 * a re-capture.
 *
 * A palette rather than a block id per column because it is strictly smaller:
 * a chunk's surface holds a median of 2 distinct blocks and at most a dozen,
 * so 256 one-byte indices plus a short table beats 256 two-byte ids.
 *
 * Palette entries are namespaced strings ("minecraft:mycelium"), never the
 * registry's numeric ids. Those are assigned at runtime and shift between
 * versions, so a numeric capture would silently misread itself after any
 * Minecraft update.
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
    /** A column's palette reference is one byte, so the palette cannot exceed
     *  256 entries. Real chunks hold a median of 2 and a measured maximum of
     *  11, so this is a guard against a bug rather than a real ceiling. */
    public static final int MAX_PALETTE = 256;
    /** Longest block id accepted. Vanilla's longest is well under this; the
     *  limit exists so a malformed id cannot inflate a request. */
    public static final int MAX_BLOCK_ID_LENGTH = 128;
    // 1.21 overworld build range. Heightmaps report the y *above* the surface, so
    // the ceiling is one past the top buildable block (matches api/terrain.py).
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;
    /** The mapped world is 20480 blocks square, so chunks run -640..639. */
    public static final int MIN_CHUNK = -640;
    public static final int MAX_CHUNK = 639;

    private final int cx;
    private final int cz;
    /** Palette index per column, not a MapColor id. See the class doc. */
    private final byte[] colors = new byte[COLUMNS];
    /** Block id -> palette index, in insertion order so the encoded palette
     *  and these indices always agree. */
    private final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
    /** Parallel to paletteIndex: the MapColor vanilla gives each block. */
    private final List<Integer> paletteColors = new ArrayList<>();
    private final List<String> paletteBlocks = new ArrayList<>();
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

    public void set(int index, String blockId, int mapColor, int floor, int top) {
        if (index < 0 || index >= COLUMNS) {
            throw new IllegalArgumentException(
                    "column index " + index + " outside 0.." + (COLUMNS - 1));
        }
        if (encoded) {
            throw new IllegalStateException(
                    "chunk (" + cx + ", " + cz + ") was already encoded; mutating it now "
                    + "would make its three planes describe different states");
        }
        if (blockId == null || blockId.isEmpty()) {
            throw new IllegalArgumentException("column " + index + ": missing block id");
        }
        if (blockId.length() > MAX_BLOCK_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "column " + index + ": block id longer than " + MAX_BLOCK_ID_LENGTH);
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
        Integer slot = paletteIndex.get(blockId);
        if (slot == null) {
            if (paletteIndex.size() >= MAX_PALETTE) {
                throw new IllegalStateException(
                        "chunk (" + cx + ", " + cz + ") needs more than " + MAX_PALETTE
                        + " distinct surface blocks; a column index is one byte");
            }
            slot = paletteIndex.size();
            paletteIndex.put(blockId, slot);
            paletteBlocks.add(blockId);
            paletteColors.add(mapColor);
        } else if (paletteColors.get(slot) != mapColor) {
            // The same block reporting two different MapColors within one chunk
            // means the colour is state-dependent in a way this format cannot
            // carry, and the palette would silently keep only the first.
            throw new IllegalArgumentException(
                    "column " + index + ": block " + blockId + " reported map colour "
                    + mapColor + " but " + paletteColors.get(slot) + " earlier in this chunk");
        }

        if (!written[index]) { written[index] = true; filled++; }
        colors[index] = (byte) (int) slot;
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

    /**
     * The palette as the server reads it: [["minecraft:mycelium", 24], ...],
     * ordered so entry n is what a column holding n refers to.
     *
     * Pairing the block with vanilla's MapColor rather than sending the block
     * alone means the server needs no block-to-colour table of its own: the
     * default render is byte-identical to a colour-only capture, and knowing
     * the block is what lets a specific one be overridden.
     */
    public List<Object> encodePalette() {
        requireComplete();
        List<Object> out = new ArrayList<>(paletteBlocks.size());
        for (int i = 0; i < paletteBlocks.size(); i++) {
            out.add(List.of(paletteBlocks.get(i), paletteColors.get(i)));
        }
        return out;
    }

    /** How many distinct surface blocks this chunk holds. */
    public int paletteSize() { return paletteBlocks.size(); }

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
        // The palette is part of the content. Without it two chunks whose
        // columns happen to hold the same indices over different palettes
        // would hash alike, and the second would never upload.
        for (int i = 0; i < paletteBlocks.size(); i++) {
            for (byte b : paletteBlocks.get(i).getBytes(StandardCharsets.UTF_8)) {
                hash = hash * 31 + b;
            }
            hash = hash * 31 + paletteColors.get(i);
        }
        for (byte value : colors) hash = hash * 31 + value;
        for (short value : floors) hash = hash * 31 + value;
        for (short value : tops) hash = hash * 31 + value;
        return hash;
    }
}
