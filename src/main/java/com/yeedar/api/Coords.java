package com.yeedar.api;

/**
 * Turning an entity position into the block coordinate a player would read.
 *
 * <p>Its own class, free of Minecraft types, because the obvious spelling is
 * wrong in a way that hides: {@code (int) x} truncates toward zero, while a
 * block coordinate is {@code floor}. The two agree for positive numbers and
 * disagree by one for every negative one with a fractional part.
 *
 * <p>Found by asking players to announce their coordinates and comparing. Two
 * reports, both at negative z, both exactly one block too high:
 *
 * <pre>
 *   stated -3757  reported -3756
 *   stated -3721  reported -3720
 * </pre>
 *
 * <p>EdenMC's inhabited area runs to negative z, so this affected essentially
 * every position the mod had ever sent from there — sightings as well as
 * logouts. A whole-block error is small enough to look like rounding and large
 * enough to send somebody to the wrong block, which is why it survived so long.
 */
public final class Coords {

    private Coords() {}

    /**
     * The block containing this coordinate.
     *
     * @param v an entity coordinate, which may be negative and is rarely whole
     */
    public static int block(double v) {
        return (int) Math.floor(v);
    }
}
