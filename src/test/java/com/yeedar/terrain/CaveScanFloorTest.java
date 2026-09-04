package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the floor people actually stand on.
 *
 * <p>The first version took the first non-air, non-bedrock block below the
 * ceiling, which sounded right and was not. Real captured chunks came back
 * with every column between y=121 and y=126: below the bedrock roof the Nether
 * is solid netherrack for a good depth, so "first solid" is the underside of
 * the roof mass, and the map would have rendered as a flat sheet just under
 * the ceiling.
 *
 * <p>Cave mode has to punch through that mass to the first open space and take
 * the floor of it.
 */
class CaveScanFloorTest {

    private static final IntPredicate NO_BEDROCK = y -> false;

    @Test
    @DisplayName("the roof mass is passed through, not landed on")
    void passesThroughTheRoofMass() {
        // Bedrock 127-124, solid netherrack 123-100, open cavern 99-65,
        // ground at 64. The old scan stopped at 123.
        IntPredicate bedrock = y -> y >= 124;
        IntPredicate air = y -> y < 100 && y > 64;
        assertEquals(64, CaveScan.floorY(air, bedrock, 126, 1));
    }

    @Test
    @DisplayName("captured chunks stop hugging the ceiling")
    void reproducesTheObservedFailure() {
        // The shape the live data actually had: solid from the roof straight
        // down, then a cavern, then the floor. Anything at 120+ here means the
        // scan is back to reporting the roof underside.
        IntPredicate air = y -> y < 121 && y > 40;
        int floor = CaveScan.floorY(air, NO_BEDROCK, 126, 1);
        assertEquals(40, floor);
        assertTrue(floor < 100, "a floor up at " + floor + " is the roof mass again");
    }

    @Test
    @DisplayName("an open Nether column still finds its floor")
    void openColumn() {
        // Nothing solid below the ceiling until the ground: the case the first
        // version did handle, and which must keep working.
        IntPredicate air = y -> y > 70;
        assertEquals(70, CaveScan.floorY(air, NO_BEDROCK, 126, 1));
    }

    @Test
    @DisplayName("lava is the floor when it is the first thing under the gap")
    void lavaSeaIsTheFloor() {
        IntPredicate air = y -> y < 120 && y > 31;
        assertEquals(31, CaveScan.floorY(air, NO_BEDROCK, 126, 1));
    }

    @Test
    @DisplayName("solid all the way down reports no floor")
    void noOpenSpaceAtAll() {
        // Nowhere to stand and nothing a map of the Nether should show.
        assertTrue(CaveScan.floorY(y -> false, NO_BEDROCK, 126, 1) == CaveScan.NONE);
    }

    @Test
    @DisplayName("the highest floor under the first gap wins, not a deeper one")
    void takesTheFirstOpenSpace() {
        // Roof mass, cavern with a floor at 90, more rock, a deeper cavern
        // with a floor at 40. Looking down, you see the 90.
        IntPredicate air = y -> (y < 121 && y > 90) || (y < 70 && y > 40);
        assertEquals(90, CaveScan.floorY(air, NO_BEDROCK, 126, 1));
    }

    @Test
    @DisplayName("a column that is open the whole way reports no floor")
    void allAir() {
        assertTrue(CaveScan.floorY(y -> true, NO_BEDROCK, 126, 1) == CaveScan.NONE);
    }
}
