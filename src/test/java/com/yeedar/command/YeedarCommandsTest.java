package com.yeedar.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
