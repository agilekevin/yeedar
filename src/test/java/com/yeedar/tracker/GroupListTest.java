package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing NameLayer's /namelayer:listgroups output.
 *
 * <p>Every line quoted here was captured verbatim from a real EdenMC session,
 * rather than invented. The last time this codebase guessed at a server's
 * output format it produced a parser that silently returned zero and stamped
 * thousands of snitches as expiring immediately.
 */
class GroupListTest {

    // ── Header ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the page header gives current and total")
    void parsesHeader() {
        GroupList.Header h = GroupList.parseHeader("Page 1 of 2.");
        assertEquals(1, h.page());
        assertEquals(2, h.total());
    }

    @Test
    @DisplayName("a single-page listing is recognised")
    void parsesSinglePageHeader() {
        GroupList.Header h = GroupList.parseHeader("Page 1 of 1.");
        assertEquals(1, h.total());
    }

    @Test
    @DisplayName("non-headers are not headers")
    void rejectsNonHeaders() {
        assertNull(GroupList.parseHeader("YEET : (MODS)"));
        assertNull(GroupList.parseHeader("Page of pages"));
        assertNull(GroupList.parseHeader(""));
        assertNull(GroupList.parseHeader(null));
    }

    // ── Entries ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a group line yields the group name")
    void parsesGroupLines() {
        assertEquals("YEET", GroupList.parseGroup("YEET : (MODS)"));
        assertEquals("yeetistan", GroupList.parseGroup("yeetistan : (MODS)"));
        assertEquals("YEETaccess", GroupList.parseGroup("YEETaccess : (MEMBERS)"));
        assertEquals("zipwow", GroupList.parseGroup("zipwow : (OWNER)"));
        assertEquals("factoryaccess", GroupList.parseGroup("factoryaccess : (ADMINS)"));
    }

    @Test
    @DisplayName("underscores, digits and punctuation survive in names")
    void toleratesRealGroupNames() {
        assertEquals("BedEx_Entry", GroupList.parseGroup("BedEx_Entry : (MEMBERS)"));
        // A group really is named "!" on this server.
        assertEquals("!", GroupList.parseGroup("! : (MEMBERS)"));
    }

    @Test
    @DisplayName("colour codes are stripped before matching")
    void stripsFormatting() {
        assertEquals("YEET", GroupList.parseGroup("§aYEET §f: §7(MODS)"));
    }

    @Test
    @DisplayName("ordinary chat is never mistaken for a group")
    void ignoresChatter() {
        // All captured from the same log as the real listing. A false positive
        // here injects a fake group and would filter out a real one.
        assertNull(GroupList.parseGroup("[yeetistan] zipwow: probaby just a zombie"));
        assertNull(GroupList.parseGroup("You have engaged in combat. Type /ct to check your timer."));
        assertNull(GroupList.parseGroup("Spruce Sapling will grow here within 90 h"));
        assertNull(GroupList.parseGroup("Page 1 of 2."));
        assertNull(GroupList.parseGroup("Logging out safely in 10 seconds..."));
        assertNull(GroupList.parseGroup(""));
        assertNull(GroupList.parseGroup(null));
    }

    // ── Collection ────────────────────────────────────────────────────────

    /** Page 1 exactly as EdenMC sent it. */
    private static void feedPageOne(GroupList list) {
        list.accept("Page 1 of 2.");
        list.accept("! : (MEMBERS)");
        list.accept("YEET : (MODS)");
        list.accept("yeetistan : (MODS)");
        list.accept("YEETFACTORIES : (MEMBERS)");
        list.accept("BedEx_Entry : (MEMBERS)");
        list.accept("yeetLibraryCard : (MEMBERS)");
        list.accept("zipwow : (OWNER)");
        list.accept("factoryaccess : (ADMINS)");
        list.accept("HoneyHurler : (OWNER)");
        list.accept("YEETborders : (MODS)");
    }

    private static void feedPageTwo(GroupList list) {
        list.accept("Page 2 of 2.");
        list.accept("YEETaccess : (MEMBERS)");
    }

    @Test
    @DisplayName("one page of a two-page listing is not complete")
    void incompleteUntilEveryPageArrives() {
        // The failure that matters. Acting on a partial list would decide the
        // player is not in YEETaccess and silently stop scanning it — trading
        // a confusing message for missing data and no message at all, which is
        // strictly worse than doing nothing.
        GroupList list = new GroupList();
        feedPageOne(list);
        assertFalse(list.isComplete());
        assertEquals(2, list.expectedPages());
        assertEquals(1, list.pagesSeen());
    }

    @Test
    @DisplayName("both pages together make a complete listing")
    void completeWhenAllPagesArrive() {
        GroupList list = new GroupList();
        feedPageOne(list);
        feedPageTwo(list);
        assertTrue(list.isComplete());
        assertEquals(11, list.groups().size());
    }

    @Test
    @DisplayName("membership is matched without regard to case")
    void matchesCaseInsensitively() {
        // The defaults are stored lowercase ("yeetborders") and NameLayer
        // answers "YEETborders". An exact match would filter out almost
        // everything the player can actually read.
        GroupList list = new GroupList();
        feedPageOne(list);
        feedPageTwo(list);
        assertTrue(list.contains("yeetborders"));
        assertTrue(list.contains("YEETBORDERS"));
        assertTrue(list.contains("yeet"));
        assertTrue(list.contains("yeetaccess"));
        assertFalse(list.contains("beans"));
        assertFalse(list.contains("yeetsecure"));
    }

    @Test
    @DisplayName("the real defaults filter down to exactly what was readable")
    void reproducesTheObservedScan() {
        // The 13 shared defaults against this player's real membership. Every
        // group that was refused in the captured scan is excluded, and every
        // group that returned snitches is kept.
        GroupList list = new GroupList();
        feedPageOne(list);
        feedPageTwo(list);

        var defaults = java.util.List.of(
                "beans", "y_defend", "y_secure", "y_vault", "ydef", "yeet",
                "yeetaccess", "yeetborders", "yeetexchange", "yeetistan",
                "yeetmelons", "yeetsecure", "yeetvsecure");

        var kept = defaults.stream().filter(list::contains).toList();
        assertEquals(java.util.List.of("yeet", "yeetaccess", "yeetborders", "yeetistan"), kept);

        for (String refused : java.util.List.of(
                "beans", "y_defend", "y_secure", "y_vault", "ydef",
                "yeetsecure", "yeetvsecure")) {
            assertFalse(list.contains(refused), refused + " should have been filtered out");
        }
    }

    @Test
    @DisplayName("a listing with no header is never complete")
    void noHeaderMeansNoConfidence() {
        // If the header never arrived we do not know how many pages there are,
        // so we cannot know the list is whole. Scan everything instead.
        GroupList list = new GroupList();
        list.accept("YEET : (MODS)");
        assertFalse(list.isComplete());
    }

    @Test
    @DisplayName("a repeated page does not count twice toward completeness")
    void repeatedPagesDoNotSatisfyTheCount() {
        GroupList list = new GroupList();
        feedPageOne(list);
        feedPageOne(list);
        assertFalse(list.isComplete());
    }
}
