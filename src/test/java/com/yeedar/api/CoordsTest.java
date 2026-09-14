package com.yeedar.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoordsTest {

    @Test
    @DisplayName("a negative coordinate floors rather than truncating toward zero")
    void negativeCoordinateFloors() {
        // The actual bug, from two players who announced their positions:
        // standing in block -3757, reported as -3756.
        assertEquals(-3757, Coords.block(-3756.5));
        assertEquals(-3721, Coords.block(-3720.2));
        // (int) would give -3756 and -3720 here. That is the whole point.
    }

    @Test
    @DisplayName("a positive coordinate is unchanged, which is why this hid so long")
    void positiveCoordinateUnchanged() {
        assertEquals(7977, Coords.block(7977.4));
        assertEquals(8045, Coords.block(8045.9));
    }

    @Test
    @DisplayName("whole numbers are themselves, negative included")
    void wholeNumbersAreExact() {
        // A standing player's y is whole, which is why y always looked right
        // even when x and z did not.
        assertEquals(-17, Coords.block(-17.0));
        assertEquals(64, Coords.block(64.0));
        assertEquals(0, Coords.block(0.0));
    }

    @Test
    @DisplayName("either side of zero behaves like the block grid, not like rounding")
    void aroundZero() {
        assertEquals(-1, Coords.block(-0.5));
        assertEquals(-1, Coords.block(-0.01));
        assertEquals(0, Coords.block(0.5));
        assertEquals(0, Coords.block(0.99));
    }
}
