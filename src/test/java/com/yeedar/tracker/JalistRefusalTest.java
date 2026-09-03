package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
