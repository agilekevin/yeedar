package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link LogoutTracker#isReportable}, exhaustively.
 *
 * <p>This is the highest-stakes seam in the whole feature. {@link
 * LogoutDetector} deliberately does no filtering of its own — range,
 * friendlies and ignored names are the caller's job by design — so this rule
 * is the only thing standing between "non-friendly logout" and "anyone's
 * logout." All eight combinations of the original three booleans are covered
 * with the friendly list treated as loaded, so a later edit cannot loosen the
 * rule without a test noticing; a second set of cases pins the fourth
 * dimension — an unloaded friendly list — which must override every other
 * combination and make nothing reportable.
 */
class LogoutTrackerTest {

    @Test
    @DisplayName("in range, not ignored, not friendly, and the friendly list loaded is reportable")
    void inRangeUnignoredUnfriendlyLoadedIsReportable() {
        assertTrue(LogoutTracker.isReportable(true, false, false, true));
    }

    @Test
    @DisplayName("a friendly player's logout is never reportable, even in range, not ignored, and loaded")
    void friendlyIsNeverReportable() {
        // The one case that matters most. This rule is the entire reason
        // LogoutTracker exists as its own class: getting it wrong publishes an
        // ally's exact logout position, which is precisely the harm
        // "non-friendly only" was built to prevent, and LogoutDetector has no
        // way to catch the mistake because filtering is deliberately left to
        // the caller.
        assertFalse(LogoutTracker.isReportable(true, false, true, true));
    }

    @Test
    @DisplayName("an ignored player's logout is never reportable, even in range, not friendly, and loaded")
    void ignoredIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(true, true, false, true));
    }

    @Test
    @DisplayName("an ignored, friendly player's logout is not reportable, even loaded")
    void ignoredAndFriendlyIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(true, true, true, true));
    }

    @Test
    @DisplayName("out of range is never reportable, even when not ignored, not friendly, and loaded")
    void outOfRangeIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, false, false, true));
    }

    @Test
    @DisplayName("out of range and friendly is not reportable, even loaded")
    void outOfRangeAndFriendlyIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, false, true, true));
    }

    @Test
    @DisplayName("out of range and ignored is not reportable, even loaded")
    void outOfRangeAndIgnoredIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, true, false, true));
    }

    @Test
    @DisplayName("out of range, ignored, and friendly is not reportable, even loaded")
    void outOfRangeIgnoredAndFriendlyIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, true, true, true));
    }

    @Test
    @DisplayName("an otherwise-reportable logout is not reportable while the friendly list has never loaded")
    void unloadedFriendlyListBlocksAnOtherwiseReportableLogout() {
        // The cold-launch window: in range, not ignored, and FriendlyTracker
        // currently says "not friendly" only because it has never heard from
        // the server, not because it has confirmed anything. Reporting here
        // would publish an ally's exact position on the strength of a guess.
        assertFalse(LogoutTracker.isReportable(true, false, false, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks a friendly logout too")
    void unloadedFriendlyListBlocksAFriendlyLogout() {
        assertFalse(LogoutTracker.isReportable(true, false, true, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks an ignored player's logout too")
    void unloadedFriendlyListBlocksAnIgnoredLogout() {
        assertFalse(LogoutTracker.isReportable(true, true, false, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks an ignored, friendly logout too")
    void unloadedFriendlyListBlocksAnIgnoredFriendlyLogout() {
        assertFalse(LogoutTracker.isReportable(true, true, true, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks an out-of-range logout too")
    void unloadedFriendlyListBlocksAnOutOfRangeLogout() {
        assertFalse(LogoutTracker.isReportable(false, false, false, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks an out-of-range, friendly logout too")
    void unloadedFriendlyListBlocksAnOutOfRangeFriendlyLogout() {
        assertFalse(LogoutTracker.isReportable(false, false, true, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks an out-of-range, ignored logout too")
    void unloadedFriendlyListBlocksAnOutOfRangeIgnoredLogout() {
        assertFalse(LogoutTracker.isReportable(false, true, false, false));
    }

    @Test
    @DisplayName("an unloaded friendly list blocks an out-of-range, ignored, friendly logout too")
    void unloadedFriendlyListBlocksEverythingElseToo() {
        assertFalse(LogoutTracker.isReportable(false, true, true, false));
    }
}
