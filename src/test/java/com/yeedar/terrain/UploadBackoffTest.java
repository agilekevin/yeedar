package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadBackoffTest {

    /** Run `n` intervals, returning how many of them were allowed to upload. */
    private static int allowedOver(UploadBackoff b, int n) {
        int allowed = 0;
        for (int i = 0; i < n; i++) if (b.allow()) allowed++;
        return allowed;
    }

    @Test
    @DisplayName("a fresh backoff never delays the first attempt")
    void startsOpen() {
        UploadBackoff b = new UploadBackoff();
        assertTrue(b.allow());
        assertTrue(b.allow());
        assertEquals(0, b.skipsRemaining());
    }

    @Test
    @DisplayName("one failure costs exactly one interval, not a whole cycle")
    void firstFailureIsShort() {
        // A server restarting mid-deploy is back within seconds; punishing it
        // with minutes would strand chunks for no reason.
        UploadBackoff b = new UploadBackoff();
        b.failed();
        assertFalse(b.allow());   // sat out
        assertTrue(b.allow());    // and straight back
    }

    @Test
    @DisplayName("repeated failure doubles the wait")
    void doubles() {
        UploadBackoff b = new UploadBackoff();
        b.failed();
        assertEquals(1, b.skipsRemaining());
        assertFalse(b.allow());
        assertTrue(b.allow());

        b.failed();
        assertEquals(2, b.skipsRemaining());
        assertEquals(0, allowedOver(b, 2));
        assertTrue(b.allow());

        b.failed();
        assertEquals(4, b.skipsRemaining());
    }

    @Test
    @DisplayName("the wait is capped, so a hopeless client retries hourly forever")
    void capped() {
        UploadBackoff b = new UploadBackoff();
        for (int i = 0; i < 100; i++) b.failed();
        assertEquals(UploadBackoff.MAX_SKIPS, b.skipsRemaining());
        // Still capped after many more, rather than overflowing to a negative
        // wait that would let it hammer again.
        for (int i = 0; i < 100; i++) b.failed();
        assertEquals(UploadBackoff.MAX_SKIPS, b.skipsRemaining());
    }

    @Test
    @DisplayName("success clears the penalty rather than halving it")
    void successResets() {
        UploadBackoff b = new UploadBackoff();
        b.failed(); b.failed(); b.failed();   // penalty now 4
        b.succeeded();
        assertEquals(0, b.skipsRemaining());
        assertTrue(b.allow());
        // The NEXT failure starts over at one interval, not back at four.
        b.failed();
        assertEquals(1, b.skipsRemaining());
    }

    @Test
    @DisplayName("a long outage costs far fewer attempts than no backoff at all")
    void boundsTheRetryLoop() {
        // The bug this exists to fix: 360 intervals of failure used to mean 360
        // uploads of ~1.8MB each. Doubling makes it single digits.
        UploadBackoff b = new UploadBackoff();
        int attempts = 0;
        for (int interval = 0; interval < UploadBackoff.MAX_SKIPS; interval++) {
            if (b.allow()) { attempts++; b.failed(); }
        }
        assertTrue(attempts <= 10, "expected a handful of attempts, got " + attempts);
    }
}
