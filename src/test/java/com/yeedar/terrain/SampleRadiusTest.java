package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.yeedar.terrain.TerrainCapture.effectiveRadius;
import static com.yeedar.terrain.TerrainCapture.fullPassSeconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How wide a sweep is, and what that costs in coverage time. */
class SampleRadiusTest {

    @Test
    @DisplayName("unset follows the render distance, because that is all we may read")
    void autoFollowsViewDistance() {
        assertEquals(8, effectiveRadius(0, 8));
        assertEquals(16, effectiveRadius(0, 16));
        assertEquals(12, effectiveRadius(0, 12));
    }

    @Test
    @DisplayName("an explicit setting wins over the render distance, either way")
    void explicitOverrides() {
        assertEquals(4, effectiveRadius(4, 32));    // map less than you render
        assertEquals(20, effectiveRadius(20, 8));   // clamping is the sweep's job,
                                                    // not this function's: unloaded
                                                    // chunks are skipped for free
    }

    @Test
    @DisplayName("clamped at both ends, so a hand-edited config cannot break the sweep")
    void clamps() {
        // A radius of 0 from an explicit setting would mean "follow the render
        // distance"; a negative one is nonsense and would make span negative.
        assertEquals(TerrainCapture.MIN_RADIUS, effectiveRadius(-5, 0));
        assertEquals(TerrainCapture.MIN_RADIUS, effectiveRadius(0, 0));
        assertEquals(TerrainCapture.MAX_RADIUS, effectiveRadius(999, 8));
        assertEquals(TerrainCapture.MAX_RADIUS, effectiveRadius(0, 999));
    }

    @Test
    @DisplayName("a wider window is slower, not heavier")
    void widerIsSlower() {
        // The point of the whole design: per-tick cost is fixed by
        // CHUNKS_PER_SWEEP, so radius only buys or costs coverage latency.
        int narrow = fullPassSeconds(6);
        int wide = fullPassSeconds(16);
        assertTrue(wide > narrow, "wider window should take longer to cover");
        // Sanity on the actual numbers a player will be shown.
        assertEquals(44, narrow);    // 13x13 = 169 chunks, 22 sweeps, 2s each
        assertEquals(274, wide);     // 33x33 = 1089 chunks, 137 sweeps
    }

    @Test
    @DisplayName("the default render distance covers its window in a couple of minutes")
    void defaultIsReasonable() {
        // Vanilla's default render distance is 12; nothing here should imply
        // an unusably slow first pass at the setting most players run.
        assertTrue(fullPassSeconds(12) < 300,
                "a full pass at radius 12 took " + fullPassSeconds(12) + "s");
    }
}
