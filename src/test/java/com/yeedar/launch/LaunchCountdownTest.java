package com.yeedar.launch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCountdownTest {

    @Test
    @DisplayName("the countdown ends exactly when the server said it would")
    void lastBeatIsTheImpact() {
        // The map animates at its own fire_at. If the client's last line lands
        // at a different moment, the player watches the impact twice at two
        // different times, which is worse than having no countdown at all.
        List<LaunchCountdown.Beat> beats = LaunchCountdown.forSeconds(20);
        LaunchCountdown.Beat last = beats.get(beats.size() - 1);
        assertEquals(20, last.atSecond());
    }

    @Test
    @DisplayName("beats run in order and never before now")
    void beatsAreOrderedAndNonNegative() {
        int previous = -1;
        for (LaunchCountdown.Beat b : LaunchCountdown.forSeconds(20)) {
            assertTrue(b.atSecond() > previous, "beats must strictly increase");
            assertTrue(b.atSecond() >= 0);
            previous = b.atSecond();
        }
    }

    @Test
    @DisplayName("every beat says something")
    void everyBeatHasText() {
        for (LaunchCountdown.Beat b : LaunchCountdown.forSeconds(20)) {
            assertFalse(b.text().isBlank());
        }
    }

    @Test
    @DisplayName("a short countdown drops the early beats rather than reordering")
    void shortCountdownStaysSane() {
        // The server owns the countdown length and may change it. A 5s window
        // must not produce a "T-10" that fires after launch.
        List<LaunchCountdown.Beat> beats = LaunchCountdown.forSeconds(5);
        assertEquals(5, beats.get(beats.size() - 1).atSecond());
        for (LaunchCountdown.Beat b : beats) {
            assertTrue(b.atSecond() <= 5);
        }
    }

    @Test
    @DisplayName("a nonsensical countdown still yields exactly one beat")
    void degenerateCountdown() {
        // Zero or negative means "no time at all" — say the one thing that
        // matters and nothing else.
        assertEquals(1, LaunchCountdown.forSeconds(0).size());
        assertEquals(1, LaunchCountdown.forSeconds(-5).size());
        assertEquals(0, LaunchCountdown.forSeconds(0).get(0).atSecond());
    }

    @Test
    @DisplayName("a long countdown does not spam a line per second")
    void longCountdownIsNotChatty() {
        assertTrue(LaunchCountdown.forSeconds(120).size() <= 10);
    }
}
