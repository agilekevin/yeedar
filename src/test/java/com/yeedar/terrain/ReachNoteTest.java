package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.yeedar.terrain.TerrainCapture.reachNote;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a too-wide radius is told about why.
 *
 * The old message reported only the effective distance and called it "your
 * render distance". When a server sends less than the player has set, that is
 * a number they never chose, and it reads as the mod having ignored the
 * setting and defaulted — which is exactly how it was reported.
 */
class ReachNoteTest {

    @Test
    @DisplayName("a radius within reach says nothing")
    void withinReach() {
        assertNull(reachNote(8, 32, 16));
        assertNull(reachNote(16, 32, 16));   // exactly the server's limit
        assertNull(reachNote(12, 12, 0));    // server has not declared one
    }

    @Test
    @DisplayName("a server limit below the player's setting names both numbers")
    void serverIsTheLimit() {
        String note = reachNote(20, 32, 8);
        assertTrue(note.contains("server only sends you 8"), note);
        assertTrue(note.contains("set to 32"), note);
        // The point of the change: it must not present 8 as the player's own.
        assertTrue(!note.contains("your render distance of 8"), note);
    }

    @Test
    @DisplayName("with no server limit it is the player's own setting")
    void clientIsTheLimit() {
        String note = reachNote(20, 12, 0);
        assertTrue(note.contains("your render distance of 12"), note);
    }

    @Test
    @DisplayName("a server sending more than the player renders is not the limit")
    void serverAboveClient() {
        // The player set 8 and the server offers 32; 8 is genuinely theirs.
        String note = reachNote(20, 8, 32);
        assertTrue(note.contains("your render distance of 8"), note);
    }
}
