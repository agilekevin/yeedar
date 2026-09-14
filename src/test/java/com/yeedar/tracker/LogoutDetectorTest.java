package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogoutDetectorTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static LogoutDetector.Sighting alice(double x, double y, double z) {
        return new LogoutDetector.Sighting(ALICE, "Alice", x, y, z);
    }

    /** Establish a baseline, so the first-scan suppression is out of the way. */
    private static LogoutDetector primed(Set<UUID> online,
                                         List<LogoutDetector.Sighting> nearby) {
        LogoutDetector detector = new LogoutDetector();
        assertTrue(detector.scan(nearby, online).isEmpty(),
                "the first scan establishes a baseline and reports nothing");
        return detector;
    }

    @Test
    @DisplayName("a player who leaves the tab list while in range is reported at their last position")
    void logoutInRangeIsReported() {
        LogoutDetector detector = primed(Set.of(ALICE, BOB),
                List.of(alice(100, 64, -200)));

        List<LogoutDetector.Logout> out = detector.scan(List.of(), Set.of(BOB));

        assertEquals(1, out.size());
        LogoutDetector.Logout logout = out.get(0);
        assertEquals("Alice", logout.name());
        assertEquals(100, logout.x());
        assertEquals(64, logout.y());
        assertEquals(-200, logout.z());
    }

    @Test
    @DisplayName("the FIRST scan never reports a logout")
    void firstScanEstablishesABaseline() {
        // Without this, the first reading after any connection diffs against an
        // empty tab list and publishes everyone on the server at once.
        LogoutDetector detector = new LogoutDetector();
        assertTrue(detector.scan(List.of(alice(1, 2, 3)), Set.of(ALICE)).isEmpty());
    }

    @Test
    @DisplayName("a player who walked out of range and logged out later is not reported")
    void logoutAfterLeavingRangeCarriesNoPosition() {
        // The privacy property: a position is published only when we actually
        // watched them at it in the tick before they vanished.
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(100, 64, -200)));

        // Still online, but out of range now - no sighting this tick.
        assertTrue(detector.scan(List.of(), Set.of(ALICE)).isEmpty());
        // Now they log out, from wherever they went.
        assertTrue(detector.scan(List.of(), Set.of()).isEmpty());
    }

    @Test
    @DisplayName("a player who never came into range is not reported")
    void neverInRangeIsNeverReported() {
        LogoutDetector detector = primed(Set.of(ALICE, BOB), List.of());

        assertTrue(detector.scan(List.of(), Set.of(ALICE)).isEmpty());
    }

    @Test
    @DisplayName("reset suppresses the next diff, so a reconnect reports nothing")
    void resetSuppressesTheNextDiff() {
        // The reconnect storm. The tab list empties on disconnect and refills on
        // join; diffed naively that is every player on the server logging out at
        // the same instant, each at their last known position.
        LogoutDetector detector = primed(Set.of(ALICE, BOB), List.of(alice(100, 64, -200)));

        detector.reset();

        assertTrue(detector.scan(List.of(), Set.of()).isEmpty(),
                "the first scan after a reset re-establishes the baseline");
    }

    @Test
    @DisplayName("after a reset the detector still works")
    void detectionResumesAfterAReset() {
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(1, 2, 3)));
        detector.reset();

        // Re-baseline, then a genuine logout.
        assertTrue(detector.scan(List.of(alice(50, 70, 50)), Set.of(ALICE)).isEmpty());
        List<LogoutDetector.Logout> out = detector.scan(List.of(), Set.of());

        assertEquals(1, out.size());
        assertEquals(50, out.get(0).x());
    }

    @Test
    @DisplayName("two players logging out at once are both reported")
    void simultaneousLogoutsAreBothReported() {
        LogoutDetector detector = primed(Set.of(ALICE, BOB),
                List.of(alice(10, 20, 30),
                        new LogoutDetector.Sighting(BOB, "Bob", 40, 50, 60)));

        List<LogoutDetector.Logout> out = detector.scan(List.of(), Set.of());

        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("a player still online is not reported, however far they moved")
    void stillOnlineIsNeverALogout() {
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(0, 0, 0)));

        assertTrue(detector.scan(List.of(alice(9999, 0, 9999)), Set.of(ALICE)).isEmpty());
    }

    @Test
    @DisplayName("a player seen before the tab list lists them is never reported")
    void nearbyButNeverOnlineIsNeverReported() {
        // The tab list can lag entity spawn, so a player can be loaded and in
        // range before the server has listed them. The diff only ever walks
        // UUIDs that were previously ONLINE, so this sighting can never become
        // a logout on its own. Dropping a report is the right failure here -
        // the alternative is publishing a position for somebody whose presence
        // we never actually confirmed.
        LogoutDetector detector = new LogoutDetector();
        // Baseline: Alice is loaded nearby, but not yet in the tab list.
        assertTrue(detector.scan(List.of(alice(10, 20, 30)), Set.of()).isEmpty());
        // She is gone next scan, still never having been listed.
        assertTrue(detector.scan(List.of(), Set.of()).isEmpty());
    }

    @Test
    @DisplayName("the position reported is the one from the tick before they vanished")
    void positionComesFromThePreviousTick() {
        // By the time the tab list drops a player their entity is already gone,
        // so the current tick cannot have them. Reporting anything but the
        // previous snapshot would mean reporting nothing at all.
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(1, 1, 1)));
        assertTrue(detector.scan(List.of(alice(2, 2, 2)), Set.of(ALICE)).isEmpty());

        List<LogoutDetector.Logout> out = detector.scan(List.of(), Set.of());

        assertEquals(1, out.size());
        assertEquals(2, out.get(0).x(), "the last position actually observed");
    }

    @Test
    @DisplayName("a tab entry that flickers is reported once, not once per drop")
    void flickerIsReportedOnce() {
        // Observed on EdenMC: a player's tab entry was rewritten rather than
        // removed, so it dropped and reappeared repeatedly with the online
        // total unchanged, and every drop read as a fresh logout. One real
        // disconnect produced four identical reports at the same block.
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(10, 20, 30)));

        assertEquals(1, detector.scan(List.of(alice(10, 20, 30)), Set.of()).size(),
                "the first drop is the real one and is reported");
        assertTrue(detector.scan(List.of(alice(10, 20, 30)), Set.of(ALICE)).isEmpty());
        assertTrue(detector.scan(List.of(alice(10, 20, 30)), Set.of()).isEmpty(),
                "the second drop is the same disconnect and must not report again");
        assertTrue(detector.scan(List.of(alice(10, 20, 30)), Set.of(ALICE)).isEmpty());
        assertTrue(detector.scan(List.of(alice(10, 20, 30)), Set.of()).isEmpty());
    }

    @Test
    @DisplayName("a genuine logout well after the first is reported again")
    void suppressionExpires() {
        // The window collapses one disconnect's flicker, not a whole session.
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(1, 1, 1)));
        assertEquals(1, detector.scan(List.of(alice(1, 1, 1)), Set.of()).size());

        for (int i = 0; i < 20; i++) {
            detector.scan(List.of(alice(5, 5, 5)), Set.of(ALICE));
        }

        List<LogoutDetector.Logout> out = detector.scan(List.of(alice(5, 5, 5)), Set.of());
        assertEquals(1, out.size(), "a later, separate logout still reports");
        assertEquals(5, out.get(0).x());
    }

    @Test
    @DisplayName("suppressing one player does not suppress another")
    void suppressionIsPerPlayer() {
        LogoutDetector.Sighting bob = new LogoutDetector.Sighting(BOB, "Bob", 4, 5, 6);
        LogoutDetector detector = primed(Set.of(ALICE, BOB), List.of(alice(1, 2, 3), bob));

        assertEquals(1, detector.scan(List.of(alice(1, 2, 3), bob), Set.of(BOB)).size(),
                "Alice dropped");

        List<LogoutDetector.Logout> out =
                detector.scan(List.of(alice(1, 2, 3), bob), Set.of());
        assertEquals(1, out.size(), "Bob dropping is his own logout, not Alice's repeat");
        assertEquals("Bob", out.get(0).name());
    }

    @Test
    @DisplayName("reset clears suppression, so the next real logout is not swallowed")
    void resetClearsSuppression() {
        LogoutDetector detector = primed(Set.of(ALICE), List.of(alice(1, 1, 1)));
        assertEquals(1, detector.scan(List.of(alice(1, 1, 1)), Set.of()).size());

        detector.reset();

        assertTrue(detector.scan(List.of(alice(7, 7, 7)), Set.of(ALICE)).isEmpty(),
                "re-baseline");
        assertEquals(1, detector.scan(List.of(alice(7, 7, 7)), Set.of()).size(),
                "the first logout after a reset is reported");
    }
}
