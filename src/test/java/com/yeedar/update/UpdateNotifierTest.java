package com.yeedar.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNotifierTest {

    private static UpdateChecker.Release rel(String tag) {
        return new UpdateChecker.Release(tag, "https://example.invalid/" + tag);
    }

    @Test
    @DisplayName("a newer release not yet announced is announced")
    void announcesNewRelease() {
        assertTrue(UpdateNotifier.shouldNotify("1.6.0", rel("v1.7.0"), "", true));
    }

    @Test
    @DisplayName("the same version is announced only once, ever")
    void announcesEachVersionOnce() {
        // The whole point of persisting lastNotifiedVersion. People relog
        // constantly on a Civ server, and a message that returns every session
        // is one they train themselves not to read.
        assertFalse(UpdateNotifier.shouldNotify("1.6.0", rel("v1.7.0"), "v1.7.0", true));
    }

    @Test
    @DisplayName("a further release after one already announced is announced")
    void announcesTheNextOneToo() {
        assertTrue(UpdateNotifier.shouldNotify("1.6.0", rel("v1.8.0"), "v1.7.0", true));
    }

    @Test
    @DisplayName("nothing is said when already up to date")
    void silentWhenCurrent() {
        assertFalse(UpdateNotifier.shouldNotify("1.7.0", rel("v1.7.0"), "", true));
    }

    @Test
    @DisplayName("a dev build ahead of the release stays silent")
    void silentWhenAhead() {
        assertFalse(UpdateNotifier.shouldNotify("1.8.0", rel("v1.7.0"), "", true));
    }

    @Test
    @DisplayName("nothing is said before the check has landed")
    void silentWhenNothingKnown() {
        // The check is async and the first world join can beat it. That join
        // is simply quiet; the next one picks it up.
        assertFalse(UpdateNotifier.shouldNotify("1.6.0", null, "", true));
    }

    @Test
    @DisplayName("the config flag suppresses everything")
    void silentWhenDisabled() {
        assertFalse(UpdateNotifier.shouldNotify("1.6.0", rel("v1.7.0"), "", false));
    }

    @Test
    @DisplayName("an unreadable current version stays silent")
    void silentWhenOwnVersionUnknown() {
        // If we cannot tell what we are running we cannot claim to be behind.
        assertFalse(UpdateNotifier.shouldNotify(null, rel("v1.7.0"), "", true));
        assertFalse(UpdateNotifier.shouldNotify("", rel("v1.7.0"), "", true));
    }
}
