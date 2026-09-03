package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JalistRefusalTest {

    @Test
    @DisplayName("JukeAlert's refusal is recognised")
    void recognisesTheRefusal() {
        assertTrue(JalistRefusal.isNoAccess(
                "You do not have access to any group's snitches"));
    }

    @Test
    @DisplayName("trailing punctuation and surrounding text do not matter")
    void toleratesPunctuationAndPrefixes() {
        // The exact line is the server's to change, and it may arrive wrapped
        // in a prefix. Matching the distinctive clause rather than the whole
        // sentence is what survives that.
        assertTrue(JalistRefusal.isNoAccess(
                "You do not have access to any group's snitches."));
        assertTrue(JalistRefusal.isNoAccess(
                "[JukeAlert] You do not have access to any group's snitches."));
    }

    @Test
    @DisplayName("colour codes are stripped before matching")
    void stripsFormatting() {
        // Server messages almost always arrive coloured, so an unstripped
        // match would work in a test and never once in the field.
        assertTrue(JalistRefusal.isNoAccess(
                "§cYou do not have access to any group's snitches"));
        assertTrue(JalistRefusal.isNoAccess(
                "§c§lYou §rdo not have access to any group's snitches"));
    }

    @Test
    @DisplayName("case does not matter")
    void ignoresCase() {
        assertTrue(JalistRefusal.isNoAccess(
                "YOU DO NOT HAVE ACCESS TO ANY GROUP'S SNITCHES"));
    }

    @Test
    @DisplayName("the contracted spelling is recognised too")
    void acceptsContraction() {
        // Unverified against the live server, so both spellings are accepted
        // rather than betting the fix on one of them.
        assertTrue(JalistRefusal.isNoAccess(
                "You don't have access to any group's snitches"));
    }

    @Test
    @DisplayName("ordinary jalist output is not a refusal")
    void doesNotFireOnNormalOutput() {
        assertFalse(JalistRefusal.isNoAccess("Snitches for yeet:"));
        assertFalse(JalistRefusal.isNoAccess("Vault at 100, 64, 200"));
        assertFalse(JalistRefusal.isNoAccess(""));
        assertFalse(JalistRefusal.isNoAccess(null));
    }

    @Test
    @DisplayName("merely mentioning access is not a refusal")
    void doesNotFireOnTheWordAccess() {
        // A false positive aborts a group the player CAN read, which is worse
        // than the ten seconds this fix exists to save.
        assertFalse(JalistRefusal.isNoAccess("Player granted access to yeet"));
        assertFalse(JalistRefusal.isNoAccess("You now have access to any group's snitches"));
    }

    // -- Named per-group refusal -------------------------------------------
    //
    // JukeAlert sends two lines when it will not list a group. The generic one
    // above is what the report was about; this one names the group, which is
    // strictly better because it can be attributed to the group being scanned
    // rather than merely to "something was refused just now".
    //
    // It also covers a case the generic handling misses entirely. For a group
    // the player is not in, no window opens and the arm timeout is watching.
    // For a group the player IS in but cannot list snitches for, an EMPTY
    // window opens, the scan leaves the armed phase, and the refusal was
    // ignored -- costing a pager timeout and then reporting a green tick for
    // "0 snitches" when the truth was "denied".

    @Test
    @DisplayName("the named refusal yields the group it is about")
    void namesTheGroup() {
        assertEquals("YEETaccess", JalistRefusal.noPermissionGroup(
                "You do not have permission to list snitches for the group YEETaccess"));
    }

    @Test
    @DisplayName("colour codes and trailing punctuation do not matter")
    void namedRefusalTolerantOfFormatting() {
        assertEquals("YEETaccess", JalistRefusal.noPermissionGroup(
                "\u00a7cYou do not have permission to list snitches for the group YEETaccess."));
    }

    @Test
    @DisplayName("the group is matched against the scan without regard to case")
    void namedRefusalMatchesCaseInsensitively() {
        // JukeAlert answers in the server's casing while the defaults are
        // stored lowercase -- the same trap the group filter hit.
        assertTrue(JalistRefusal.isNoPermissionFor(
                "You do not have permission to list snitches for the group YEETaccess",
                "yeetaccess"));
        assertFalse(JalistRefusal.isNoPermissionFor(
                "You do not have permission to list snitches for the group YEETaccess",
                "yeetborders"));
    }

    @Test
    @DisplayName("ordinary chat names no group")
    void namedRefusalIgnoresChatter() {
        assertNull(JalistRefusal.noPermissionGroup("You do not have access to any group's snitches."));
        assertNull(JalistRefusal.noPermissionGroup("Retrieving snitches for a total of 1 group instances."));
        assertNull(JalistRefusal.noPermissionGroup(""));
        assertNull(JalistRefusal.noPermissionGroup(null));
        assertFalse(JalistRefusal.isNoPermissionFor("hello", "yeet"));
        assertFalse(JalistRefusal.isNoPermissionFor(null, "yeet"));
    }
}
