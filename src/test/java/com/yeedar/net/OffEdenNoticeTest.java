package com.yeedar.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffEdenNoticeTest {

    @Test
    @DisplayName("a non-Eden server earns the notice")
    void otherServerIsTold() {
        assertTrue(OffEdenNotice.shouldNotify(true, false));
    }

    @Test
    @DisplayName("Eden itself says nothing")
    void edenIsQuiet() {
        // The notice explains an absence. On Eden there is no absence.
        assertFalse(OffEdenNotice.shouldNotify(true, true));
    }

    @Test
    @DisplayName("singleplayer and LAN say nothing")
    void localWorldsAreQuiet() {
        // Nobody loading their own world is waiting for it to reach Eden's
        // map, so the line would be pure nag on every test world.
        assertFalse(OffEdenNotice.shouldNotify(false, false));
    }

    @Test
    @DisplayName("a local world is quiet even if it somehow reads as Eden")
    void localOutranksTheAddress() {
        // A LAN world's entry is whatever the host last joined, so this
        // combination is reachable rather than hypothetical.
        assertFalse(OffEdenNotice.shouldNotify(false, true));
    }
}
