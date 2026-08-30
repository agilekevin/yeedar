package com.yeedar.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YeedarCommandsTest {

    @Test
    @DisplayName("accepts namelayers separated by spaces, commas, or both")
    void parsesSeparators() {
        assertEquals(List.of("yeet"), YeedarCommands.parseGroups("yeet"));
        assertEquals(List.of("yeet", "yeetistan"), YeedarCommands.parseGroups("yeet yeetistan"));
        assertEquals(List.of("yeet", "yeetistan"), YeedarCommands.parseGroups("yeet,yeetistan"));
        assertEquals(List.of("yeet", "yeetistan"), YeedarCommands.parseGroups("yeet, yeetistan"));
    }

    @Test
    @DisplayName("normalises case and tolerates messy spacing")
    void normalises() {
        // JukeAlert group names are case-insensitive, and a trailing comma is
        // the kind of thing anyone types.
        assertEquals(List.of("yeet", "yeetistan"),
                YeedarCommands.parseGroups("  YEET ,, yeetistan,  "));
    }

    @Test
    @DisplayName("drops duplicates so a group is never scanned twice")
    void deduplicates() {
        assertEquals(List.of("yeet", "yeetistan"),
                YeedarCommands.parseGroups("yeet yeetistan YEET"));
    }

    @Test
    @DisplayName("empty input means scan everything, not scan nothing named ''")
    void emptyInput() {
        assertEquals(List.of(), YeedarCommands.parseGroups("   "));
        assertEquals(List.of(), YeedarCommands.parseGroups(","));
    }

    // ── /yeedar ignore ──────────────────────────────────────────────
    //
    // These mirror YeedarConfig.isIgnored, which matches with
    // equalsIgnoreCase. A list that stores case variants separately would
    // grow entries that can never match anything the plain one does not.

    @Test
    @DisplayName("adds a name and reports that the list changed")
    void addsName() {
        List<String> names = new ArrayList<>(List.of("FreeCam"));
        assertTrue(YeedarCommands.addIgnored(names, "Yeeter"));
        assertEquals(List.of("FreeCam", "Yeeter"), names);
    }

    @Test
    @DisplayName("refuses a case variant of a name already listed")
    void addIsCaseInsensitive() {
        List<String> names = new ArrayList<>(List.of("FreeCam"));
        assertFalse(YeedarCommands.addIgnored(names, "freecam"));
        assertFalse(YeedarCommands.addIgnored(names, "FREECAM"));
        assertEquals(List.of("FreeCam"), names);
    }

    @Test
    @DisplayName("trims surrounding whitespace rather than storing it")
    void addTrims() {
        List<String> names = new ArrayList<>();
        assertTrue(YeedarCommands.addIgnored(names, "  Yeeter  "));
        assertEquals(List.of("Yeeter"), names);
        // and the trimmed form is what duplicate detection sees
        assertFalse(YeedarCommands.addIgnored(names, "yeeter"));
    }

    @Test
    @DisplayName("ignores empty or null input instead of listing a blank name")
    void addRejectsBlank() {
        List<String> names = new ArrayList<>();
        assertFalse(YeedarCommands.addIgnored(names, "   "));
        assertFalse(YeedarCommands.addIgnored(names, ""));
        assertFalse(YeedarCommands.addIgnored(names, null));
        assertEquals(List.of(), names);
    }

    @Test
    @DisplayName("removes regardless of the case typed")
    void removeIsCaseInsensitive() {
        List<String> names = new ArrayList<>(List.of("FreeCam", "FreeCamera"));
        assertTrue(YeedarCommands.removeIgnored(names, "freecam"));
        assertEquals(List.of("FreeCamera"), names);
    }

    @Test
    @DisplayName("reports no change when the name was not listed")
    void removeMissing() {
        List<String> names = new ArrayList<>(List.of("FreeCam"));
        assertFalse(YeedarCommands.removeIgnored(names, "Yeeter"));
        assertEquals(List.of("FreeCam"), names);
    }

    @Test
    @DisplayName("clears every duplicate, so a hand-edited config cannot keep a name ignored")
    void removeClearsDuplicates() {
        // Nothing stops someone adding both by hand before this command existed.
        List<String> names = new ArrayList<>(List.of("FreeCam", "freecam", "Yeeter"));
        assertTrue(YeedarCommands.removeIgnored(names, "FREECAM"));
        assertEquals(List.of("Yeeter"), names);
    }
}
