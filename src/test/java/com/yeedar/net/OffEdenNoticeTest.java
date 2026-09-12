package com.yeedar.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffEdenNoticeTest {

    @Test
    @DisplayName("a non-Eden server earns the notice")
    void otherServerIsToldOnce() {
        assertTrue(OffEdenNotice.shouldNotify(true, false, false));
    }

    @Test
    @DisplayName("Eden itself says nothing")
    void edenIsQuiet() {
        // The notice explains an absence. On Eden there is no absence.
        assertFalse(OffEdenNotice.shouldNotify(true, true, false));
    }

    @Test
    @DisplayName("singleplayer and LAN say nothing")
    void localWorldsAreQuiet() {
        // Nobody loading their own world is waiting for it to reach Eden's
        // map, so the line would be pure nag on every test world.
        assertFalse(OffEdenNotice.shouldNotify(false, false, false));
    }

    @Test
    @DisplayName("it does not repeat once shown")
    void onlyOncePerLaunch() {
        // Same reasoning as the update notice: a line that returns every
        // rejoin is a line people stop reading, which fails its only job.
        assertFalse(OffEdenNotice.shouldNotify(true, false, true));
    }

    @Test
    @DisplayName("being shown already outranks every other reason")
    void shownWinsOverEverything() {
        assertFalse(OffEdenNotice.shouldNotify(true, true, true));
        assertFalse(OffEdenNotice.shouldNotify(false, false, true));
    }
}
