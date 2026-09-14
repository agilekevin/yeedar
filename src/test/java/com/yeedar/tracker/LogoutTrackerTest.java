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
 * logout." All eight combinations of the three booleans are covered so a
 * later edit cannot loosen the rule without a test noticing.
 */
class LogoutTrackerTest {

    @Test
    @DisplayName("in range, not ignored, not friendly is reportable")
    void inRangeUnignoredUnfriendlyIsReportable() {
        assertTrue(LogoutTracker.isReportable(true, false, false));
    }

    @Test
    @DisplayName("a friendly player's logout is never reportable, even in range and not ignored")
    void friendlyIsNeverReportable() {
        // The one case that matters most. This rule is the entire reason
        // LogoutTracker exists as its own class: getting it wrong publishes an
        // ally's exact logout position, which is precisely the harm
        // "non-friendly only" was built to prevent, and LogoutDetector has no
        // way to catch the mistake because filtering is deliberately left to
        // the caller.
        assertFalse(LogoutTracker.isReportable(true, false, true));
    }

    @Test
    @DisplayName("an ignored player's logout is never reportable, even in range and not friendly")
    void ignoredIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(true, true, false));
    }

    @Test
    @DisplayName("an ignored, friendly player's logout is not reportable")
    void ignoredAndFriendlyIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(true, true, true));
    }

    @Test
    @DisplayName("out of range is never reportable, even when not ignored and not friendly")
    void outOfRangeIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, false, false));
    }

    @Test
    @DisplayName("out of range and friendly is not reportable")
    void outOfRangeAndFriendlyIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, false, true));
    }

    @Test
    @DisplayName("out of range and ignored is not reportable")
    void outOfRangeAndIgnoredIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, true, false));
    }

    @Test
    @DisplayName("out of range, ignored, and friendly is not reportable")
    void outOfRangeIgnoredAndFriendlyIsNeverReportable() {
        assertFalse(LogoutTracker.isReportable(false, true, true));
    }
}
