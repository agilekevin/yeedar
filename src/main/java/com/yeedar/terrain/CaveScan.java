package com.yeedar.terrain;

import java.util.function.IntPredicate;

/**
 * Finding the Nether floor by scanning downward — cave mode.
 *
 * <p>The overworld reads {@code Heightmap.WORLD_SURFACE}, which the server
 * sends to clients and which is what the terrain feature was cleared on. That
 * heightmap is useless in the Nether: it reports the bedrock ceiling almost
 * everywhere, so a map built from it is a uniform grey sheet. The roof is not
 * an alternative subject either, being inaccessible on Eden.
 *
 * <p>So the Nether is scanned instead. Eden's rules permit exactly this, in
 * exactly one place:
 *
 * <blockquote>Maps may use "cave mode" in the nether only.</blockquote>
 *
 * <p>Which is why the rule lives here as a pure function rather than inside a
 * sampling loop: the gate that keeps it in the Nether is worth being able to
 * read, and the scan itself is worth being able to test without a world.
 */
public final class CaveScan {

    /** Below the bedrock ceiling. The roof is solid at 127 and hangs lower in
     *  places, which {@code isBedrock} handles; starting here just avoids
     *  reading the guaranteed-bedrock layer on every column. */
    public static final int NETHER_TOP = 126;

    /** The Nether's own bedrock floor is 0, so there is nothing to learn below
     *  1 that is not bedrock. */
    public static final int NETHER_BOTTOM = 1;

    /** No floor found in the searched range. */
    public static final int NONE = Integer.MIN_VALUE;

    private CaveScan() {}

    /**
     * The highest non-air, non-bedrock block between {@code fromY} and
     * {@code toY} inclusive, scanning downward, or {@link #NONE}.
     *
     * <p>Bedrock is skipped rather than accepted because the roof's underside
     * is irregular: stopping at the first non-air block would report the
     * ceiling as the ground for most of the Nether.
     *
     * <p>Everything else stops the scan, lava included — it is the Nether's
     * biggest landmark and its main hazard, and nothing extra is needed to
     * record it, only nothing added that would skip it.
     *
     * <p>The highest surface wins, so a bridge over open ground is drawn
     * rather than the ground beneath it. A map made from above should show
     * what is visible from above.
     */
    public static int floorY(IntPredicate isAir, IntPredicate isBedrock, int fromY, int toY) {
        // Phase one: get out from under the ceiling. Below the bedrock roof the
        // Nether is solid netherrack for a good depth, so the first non-air
        // block is the underside of that mass rather than anywhere anyone
        // stands. The first version stopped there and captured chunks came back
        // with every column between 121 and 126 — a flat sheet just under the
        // roof, which is what sent this back for a rewrite.
        int y = fromY;
        while (y >= toY && !isAir.test(y)) y--;

        // Phase two: the floor of that open space. Bedrock is still skipped —
        // the roof hangs down irregularly and a stray block of it inside the
        // gap is not ground.
        while (y >= toY) {
            if (!isAir.test(y) && !isBedrock.test(y)) return y;
            y--;
        }
        return NONE;
    }
}
