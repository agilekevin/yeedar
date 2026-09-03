package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding, from chat alone, whether the group being scanned was refused.
 *
 * <p>Every line here is verbatim from a real session. This logic exists as its
 * own unit because getting it wrong is expensive and silent: an earlier version
 * attributed one group's refusal to the next group in the queue and skipped
 * yeetborders, all 2025 snitches of it, while reporting a tidy scan.
 */
class RefusalWatcherTest {

    private static final String NAMED_ACCESS =
            "You do not have permission to list snitches for the group YEETaccess";
    private static final String GENERIC =
            "You do not have access to any group's snitches.";

    @Test
    @DisplayName("a named refusal refuses the group it names")
    void namedRefusal() {
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("yeetaccess");
        w.onChat(NAMED_ACCESS);
        assertTrue(w.isRefused());
    }

    @Test
    @DisplayName("the trailing generic line does not refuse the NEXT group")
    void trailingGenericDoesNotLeak() {
        // The regression, exactly as it happened. JukeAlert refuses in a pair:
        // the named line, then the generic one. Acting on the named line
        // advances to the next group within a tick, so the generic line lands
        // while that group is armed — and it names nobody.
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("yeetaccess");
        w.onChat(NAMED_ACCESS);
        assertTrue(w.isRefused());

        w.beginGroup("yeetborders");     // scanner moves on immediately
        w.onChat(GENERIC);               // ...and the second line arrives now
        assertFalse(w.isRefused(), "yeetborders was never refused");
    }

    @Test
    @DisplayName("a generic refusal with no named line still refuses")
    void genericOnlyRefusal() {
        // Groups the player is not in at all: no window opens and the generic
        // line is the only signal there is.
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("beans");
        w.onChat(GENERIC);
        assertTrue(w.isRefused());
    }

    @Test
    @DisplayName("consecutive generic-only refusals each refuse their own group")
    void backToBackGenericRefusals() {
        // beans, then y_defend. Suppressing generics for a time window rather
        // than for exactly one expected line would have broken this.
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("beans");
        w.onChat(GENERIC);
        assertTrue(w.isRefused());

        w.beginGroup("y_defend");
        w.onChat(GENERIC);
        assertTrue(w.isRefused());
    }

    @Test
    @DisplayName("a refusal naming another group is not ours")
    void namedRefusalForSomeoneElse() {
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("yeetborders");
        w.onChat(NAMED_ACCESS);
        assertFalse(w.isRefused());
    }

    @Test
    @DisplayName("a generic line after the window opened is not a refusal")
    void genericAfterWindowOpened() {
        // Once the container is up the group is readable; a refusal arriving
        // then belongs to something else the player typed.
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("yeetborders");
        w.windowOpened();
        w.onChat(GENERIC);
        assertFalse(w.isRefused());
    }

    @Test
    @DisplayName("a window opening clears a pending swallow")
    void windowOpeningClearsTheSwallow() {
        // Otherwise a flag left standing from an earlier refusal could eat a
        // real one much later, which is the same class of bug in reverse.
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("yeetaccess");
        w.onChat(NAMED_ACCESS);

        w.beginGroup("yeetborders");
        w.windowOpened();                // read fine, swallow no longer owed
        w.beginGroup("yeetsecure");
        w.onChat(GENERIC);
        assertTrue(w.isRefused(), "a real refusal must not be swallowed");
    }

    @Test
    @DisplayName("ordinary chat refuses nothing")
    void chatterIsNotRefusal() {
        RefusalWatcher w = new RefusalWatcher();
        w.beginGroup("yeetborders");
        w.onChat("[yeetistan] zipwow: walking the border now");
        w.onChat("Retrieving snitches for a total of 1 group instances.");
        w.onChat(null);
        assertFalse(w.isRefused());
    }
}
