package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the Nether floor by scanning down, which is cave mode.
 *
 * <p>Eden allows this in the Nether and nowhere else, so the rule is worth
 * having on its own where it can be read and tested rather than buried in a
 * loop that also needs a live world to run.
 */
class CaveScanTest {

    /** A column described by which y values hold air and which hold bedrock. */
    private static IntPredicate at(Set<Integer> ys) {
        return ys::contains;
    }

    private static final IntPredicate NO_BEDROCK = y -> false;

    @Test
    @DisplayName("the first solid block below the start is the floor")
    void findsTheFloor() {
        // Air from 126 down to 71, netherrack at 70.
        IntPredicate air = y -> y > 70;
        assertEquals(70, CaveScan.floorY(air, NO_BEDROCK, 126, 1));
    }

    @Test
    @DisplayName("the bedrock roof's underside is never the floor")
    void skipsTheRoof() {
        // Bedrock hanging down to 123, then air, then real ground at 64. A
        // scan that stopped at the first non-air block would report the roof
        // and map the whole Nether as a bedrock sheet — which is the reason
        // the heightmap cannot be used here in the first place.
        IntPredicate bedrock = at(Set.of(126, 125, 124, 123));
        IntPredicate air = y -> y < 123 && y > 64;
        assertEquals(64, CaveScan.floorY(air, bedrock, 126, 1));
    }

    @Test
    @DisplayName("lava counts as floor")
    void lavaIsFloor() {
        // The lava sea is the Nether's dominant landmark and its main hazard.
        // It is not air and not bedrock, so it stops the scan on its own — the
        // point of this test is that nothing special is needed to make that
        // happen, and nothing may be added that breaks it.
        IntPredicate air = y -> y > 31;
        assertEquals(31, CaveScan.floorY(air, NO_BEDROCK, 126, 1));
    }

    @Test
    @DisplayName("a column of nothing but air reports no floor")
    void emptyColumn() {
        assertTrue(CaveScan.floorY(y -> true, NO_BEDROCK, 126, 1) < 0);
    }

    @Test
    @DisplayName("a column of nothing but bedrock reports no floor")
    void allBedrock() {
        assertTrue(CaveScan.floorY(y -> false, y -> true, 126, 1) < 0);
    }

    @Test
    @DisplayName("the scan stops at the bottom and does not run away")
    void respectsTheBottom() {
        // Solid only below the floor of the search. Scanning past it would
        // read blocks the caller deliberately excluded.
        IntPredicate air = y -> y > 0;
        assertTrue(CaveScan.floorY(air, NO_BEDROCK, 126, 10) < 0);
    }

    @Test
    @DisplayName("a floor at the very first y is found")
    void floorAtTheTop() {
        assertEquals(126, CaveScan.floorY(y -> false, NO_BEDROCK, 126, 1));
    }

    @Test
    @DisplayName("solid, then air, then solid takes the highest")
    void takesTheHighestSurface() {
        // A bridge or platform above the ground is the surface as seen from
        // above, and a map drawn from above should show it.
        IntPredicate air = y -> y != 90 && y > 64;
        assertEquals(90, CaveScan.floorY(air, NO_BEDROCK, 126, 1));
    }
}
