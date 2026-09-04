package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming dimensions the way YeetVis stores them.
 *
 * <p>Everything Yeedar uploaded before this existed claimed to be from the
 * overworld, because both upload paths wrote that string as a constant. On
 * Eden the Nether is 1:1 with the overworld, so those chunks landed exactly on
 * top of the real ones rather than somewhere obviously wrong.
 */
class DimensionsTest {

    @Test
    @DisplayName("the vanilla dimensions map to the names YeetVis stores")
    void mapsVanillaDimensions() {
        assertEquals("overworld", Dimensions.name("minecraft:overworld"));
        assertEquals("nether", Dimensions.name("minecraft:the_nether"));
        assertEquals("end", Dimensions.name("minecraft:the_end"));
    }

    @Test
    @DisplayName("an unnamespaced key is understood")
    void toleratesMissingNamespace() {
        assertEquals("nether", Dimensions.name("the_nether"));
        assertEquals("overworld", Dimensions.name("overworld"));
    }

    @Test
    @DisplayName("an unknown dimension keeps its own name rather than becoming overworld")
    void unknownDimensionsAreNotSilentlyOverworld() {
        // The whole bug was calling something the overworld when it was not.
        // A modded or renamed dimension should arrive as itself and be
        // recognisably unfamiliar, never as a plausible lie.
        assertEquals("someserver:mining", Dimensions.name("someserver:mining"));
    }

    @Test
    @DisplayName("nothing at all is unknown, not overworld")
    void nullIsUnknown() {
        assertEquals("unknown", Dimensions.name(null));
        assertEquals("unknown", Dimensions.name(""));
        assertEquals("unknown", Dimensions.name("   "));
    }

    @Test
    @DisplayName("only the Nether may be mapped in cave mode")
    void caveModeIsNetherOnly() {
        // Eden's rules: "Maps may use 'cave mode' in the nether only." This is
        // the gate, so it is asserted rather than assumed.
        assertTrue(Dimensions.allowsCaveMode("nether"));
        assertFalse(Dimensions.allowsCaveMode("overworld"));
        assertFalse(Dimensions.allowsCaveMode("end"));
        assertFalse(Dimensions.allowsCaveMode("someserver:mining"));
        assertFalse(Dimensions.allowsCaveMode("unknown"));
        assertFalse(Dimensions.allowsCaveMode(null));
    }

    @Test
    @DisplayName("mapping is refused in dimensions we cannot name")
    void unknownDimensionsAreNotMappable() {
        // Uploading chunks labelled "unknown" would poison a layer nobody can
        // later identify, which is the mess this whole change is cleaning up.
        assertTrue(Dimensions.isMappable("overworld"));
        assertTrue(Dimensions.isMappable("nether"));
        assertFalse(Dimensions.isMappable("unknown"));
        assertFalse(Dimensions.isMappable(null));
    }
}
