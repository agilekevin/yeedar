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
    @DisplayName("auto stops widening where the map would start going patchy")
    void autoIsCappedAtTheSmoothLimit() {
        // A server sending more than the sweep can cover would otherwise
        // produce a bigger map with holes in it rather than a bigger map.
        int cap = TerrainCapture.smoothMaxRadius();
        assertEquals(cap, effectiveRadius(0, 32));
        assertEquals(cap, effectiveRadius(0, cap + 5));
        assertEquals(cap - 1, effectiveRadius(0, cap - 1));   // under it, follow
    }

    @Test
    @DisplayName("the smooth cap is exactly where a horse stops being trackable")
    void smoothCapIsTheHorseLimit() {
        int cap = TerrainCapture.smoothMaxRadius();
        assertTrue(TerrainCapture.keepsUpWith(cap) >= TerrainCapture.FAST_HORSE_BPS,
                "the cap itself must keep up");
        assertTrue(TerrainCapture.keepsUpWith(cap + 1) < TerrainCapture.FAST_HORSE_BPS,
                "one wider must not, or the cap is leaving coverage unused");
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
        assertEquals(TerrainCapture.smoothMaxRadius(), effectiveRadius(0, 999));
    }

    @Test
    @DisplayName("a wider window is slower, not heavier")
    void widerIsSlower() {
        // The point of the whole design: per-tick cost is fixed by
        // CHUNKS_PER_SWEEP, so radius only buys or costs coverage latency.
        assertTrue(fullPassSeconds(16) > fullPassSeconds(6),
                "wider window should take longer to cover");
    }

    @Test
    @DisplayName("the default render distance covers its window in well under a minute")
    void defaultIsReasonable() {
        // Vanilla's default render distance is 12.
        assertTrue(fullPassSeconds(12) < 60,
                "a full pass at radius 12 took " + fullPassSeconds(12) + "s");
    }

    // ── Keeping up with a moving player ─────────────────────────────────
    //
    // The sample rate exists to satisfy these, so they are the tests that
    // justify the constants. If CHUNKS_PER_SWEEP or SAMPLE_INTERVAL is ever
    // tuned for tick cost alone, these say what it costs in coverage.

    @Test
    @DisplayName("a window at any usable radius is fully sampled at horse speed")
    void keepsUpWithAHorse() {
        // 21 covers any render distance a client will actually be sent; the
        // vanilla maximum is 32, which no server grants in practice.
        for (int radius = TerrainCapture.MIN_RADIUS;
             radius <= TerrainCapture.smoothMaxRadius(); radius++) {
            assertTrue(TerrainCapture.keepsUpWith(radius) >= TerrainCapture.FAST_HORSE_BPS,
                    "radius " + radius + " only keeps up with "
                            + TerrainCapture.keepsUpWith(radius) + " b/s, slower than a horse");
        }
    }

    @Test
    @DisplayName("a wider window tracks a SLOWER player, which is why radius cannot fix this")
    void widerTracksSlower() {
        // The counter-intuitive part, and the reason raising the radius is the
        // wrong answer to "I outran the mapper": the same budget spread over
        // more chunks revisits each one less often.
        assertTrue(TerrainCapture.keepsUpWith(6) > TerrainCapture.keepsUpWith(12));
        assertTrue(TerrainCapture.keepsUpWith(12) > TerrainCapture.keepsUpWith(32));
    }
}
