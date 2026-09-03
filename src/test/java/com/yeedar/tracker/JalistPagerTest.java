package com.yeedar.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.yeedar.tracker.JalistPager.Action;
import static com.yeedar.tracker.JalistPager.View;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every case here is a failure we actually hit against the live server. They
 * took six round trips to find at a few minutes each; they run in milliseconds.
 */
class JalistPagerTest {

    private static final int GAP = 2, WAIT = 3, RETRIES = 3, GRACE = 5, MAX_PAGES = 50;

    /** A fake JukeAlert that only turns the page when it receives a click. */
    private static final class FakeServer {
        private final int pages;
        private final boolean wraps;
        /** Clicks that vanish, by 1-based click number. */
        private final List<Integer> dropClicks = new ArrayList<>();
        int current = 1;
        int clicks = 0;

        FakeServer(int pages, boolean wraps) {
            this.pages = pages;
            this.wraps = wraps;
        }

        FakeServer dropping(int... clickNumbers) {
            for (int n : clickNumbers) dropClicks.add(n);
            return this;
        }

        void click() {
            clicks++;
            if (dropClicks.contains(clicks)) return;   // silently ignored
            if (current < pages) current++;
            else if (wraps) current = 1;
        }

        /** Items on the final page; 45 (a full page) unless set. */
        int lastPageItems = 45;

        FakeServer lastPageHolding(int items) {
            this.lastPageItems = items;
            return this;
        }

        View view() {
            int items = (current == pages) ? lastPageItems : 45;
            return new View("page-" + current, items);
        }
    }

    /** Drive the pager against a fake server until it stops. */
    private static Result run(JalistPager pager, FakeServer server, int maxTicks) {
        Result r = new Result();
        for (int i = 0; i < maxTicks && !pager.finished(); i++) {
            Action a = pager.tick(server.view());
            r.actions.add(a);
            if (a == Action.READ) r.read.add(server.current);
            if (a == Action.CLICK) server.click();
            if (a == Action.DONE || a == Action.GAVE_UP || a == Action.CLOSED) r.ending = a;
        }
        return r;
    }

    private static final class Result {
        final List<Action> actions = new ArrayList<>();
        final List<Integer> read = new ArrayList<>();
        Action ending = null;
    }

    @Test
    @DisplayName("reads every page of a list that wraps at the end")
    void readsAllPagesThenStops() {
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(7, true);
        var r = run(pager, server, 500);

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), r.read);
        assertEquals(Action.DONE, r.ending);
        assertEquals(7, pager.pagesRead());
    }

    @Test
    @DisplayName("a dropped click is re-clicked, not waited out")
    void retriesADroppedClick() {
        // This is the page-7 stall: one click vanished and the scan sat idle
        // for thirty seconds because it preferred waiting to re-clicking.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(7, true).dropping(3);
        var r = run(pager, server, 500);

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), r.read);
        assertEquals(Action.DONE, r.ending);
    }

    @Test
    @DisplayName("survives several consecutive dropped clicks")
    void retriesRepeatedly() {
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(5, true).dropping(3, 4);
        var r = run(pager, server, 500);

        assertEquals(List.of(1, 2, 3, 4, 5), r.read);
        assertEquals(Action.DONE, r.ending);
    }

    @Test
    @DisplayName("gives up rather than looping when a page never arrives")
    void givesUpAfterMaxRetries() {
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(7, true).dropping(3, 4, 5, 6, 7, 8, 9, 10);
        var r = run(pager, server, 500);

        assertEquals(Action.GAVE_UP, r.ending);
        // Clicks 1 and 2 land before the drops start, so three pages are read
        // and kept — giving up must not discard what the scan already has.
        assertEquals(List.of(1, 2, 3), r.read);
    }

    @Test
    @DisplayName("the client's predicted empty container is not a page")
    void ignoresTransientEmptyContainer() {
        // Clicking empties the container client-side before the server's real
        // contents arrive. Counting that as a page produced a phantom page, a
        // second click per real page, and an empty fingerprint that later
        // matched and ended the scan early.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(3, true);
        var read = new ArrayList<Integer>();
        boolean blankNext = false;

        for (int i = 0; i < 500 && !pager.finished(); i++) {
            View v = blankNext ? new View("", 0) : server.view();
            blankNext = false;
            Action a = pager.tick(v);
            if (a == Action.READ) read.add(server.current);
            if (a == Action.CLICK) { server.click(); blankNext = true; }
        }
        assertEquals(List.of(1, 2, 3), read);
        assertEquals(3, pager.pagesRead());
    }

    @Test
    @DisplayName("tolerates the window briefly vanishing between pages")
    void toleratesTransientClose() {
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(3, true);
        var read = new ArrayList<Integer>();
        int gone = 0;

        for (int i = 0; i < 500 && !pager.finished(); i++) {
            // Vanish for two ticks after every click, well inside the grace.
            View v = gone > 0 ? View.closed() : server.view();
            if (gone > 0) gone--;
            Action a = pager.tick(v);
            if (a == Action.READ) read.add(server.current);
            if (a == Action.CLICK) { server.click(); gone = 2; }
        }
        assertEquals(List.of(1, 2, 3), read);
    }

    @Test
    @DisplayName("stops once the window stays shut")
    void stopsWhenWindowClosesForGood() {
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        Action last = Action.IDLE;
        for (int i = 0; i < 50 && !pager.finished(); i++) {
            last = pager.tick(View.closed());
        }
        assertEquals(Action.CLOSED, last);
    }

    @Test
    @DisplayName("a page seen again after retries means skipped pages, not the end")
    void repeatAfterRetriesIsNotTreatedAsTheEnd() {
        // The bug the retry fix introduced: extra clicks ran the server past
        // pages we never read, the page that landed was one already seen, and
        // wrap-detection stopped the scan at page 7 of a longer list.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);

        // Read page 1, then click.
        while (pager.tick(new View("page-1", 45)) != Action.CLICK) { /* settle */ }
        // Nothing arrives, so it retries — that retry click skips ahead.
        Action a;
        do { a = pager.tick(new View("page-1", 45)); } while (a != Action.CLICK);
        // What lands is page 1 again (the server wrapped past unread pages).
        pager.tick(new View("page-1", 45));

        assertTrue(!pager.finished(),
                "a repeat while retries are outstanding must not end the scan");

        // And it must keep going, not stall or quietly stop on the next tick:
        // the first version of this reset the retry counter but not our
        // position, so the very next tick saw the same seen-before page with
        // retries back at zero and finished anyway.
        Action next = Action.IDLE;
        for (int i = 0; i < 20 && !pager.finished(); i++) {
            next = pager.tick(new View("page-1", 45));
            if (next == Action.CLICK) break;
        }
        assertEquals(Action.CLICK, next, "should resume clicking from the page it landed on");
        assertTrue(!pager.finished(), "must not have stopped");
    }

    @Test
    @DisplayName("a page that is not full ends the scan, without clicking past it")
    void partialPageIsTheEndOfTheList() {
        // JukeAlert fills 45 slots a page, so a short page is the last one.
        // The pager used to click "next" from there, wait out every retry on a
        // page that does not exist, and report "Page N never arrived" — the
        // normal end of a list rendered as a failure, at about twelve seconds
        // and one alarming message per group.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(5, false).lastPageHolding(34);
        var r = run(pager, server, 500);

        assertEquals(List.of(1, 2, 3, 4, 5), r.read);
        assertEquals(Action.DONE, r.ending);
        assertEquals(5, pager.pagesRead());
    }

    @Test
    @DisplayName("the short final page is still read, not discarded")
    void partialPageIsStillRead() {
        // Ending early must not cost the 34 snitches that page was carrying.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(3, false).lastPageHolding(1);
        var r = run(pager, server, 500);

        assertTrue(r.read.contains(3), "the final page must be read before stopping");
        assertEquals(Action.DONE, r.ending);
    }

    @Test
    @DisplayName("a full final page still ends by wrapping, as before")
    void fullFinalPageStillWraps() {
        // The old path has to keep working: when the last page happens to be
        // exactly full there is nothing to notice, and the scan ends by seeing
        // the list wrap around to page one.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(4, true);   // every page full
        var r = run(pager, server, 500);

        assertEquals(List.of(1, 2, 3, 4), r.read);
        assertEquals(Action.DONE, r.ending);
    }

    @Test
    @DisplayName("an empty page is not mistaken for a short one")
    void emptyPageIsNotAPartialPage() {
        // Zero items means the container is not populated yet, which the
        // closed-window path already handles. Treating it as "a short page,
        // therefore the end" would stop a scan before it started.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(3, false).lastPageHolding(0);
        var r = run(pager, server, 500);

        assertTrue(r.read.contains(1), "the scan must still read real pages");
    }

    @Test
    @DisplayName("a full last page on a non-wrapping list still gives up, as before")
    void fullLastPageOnANonWrappingListStillGivesUp() {
        // The remaining gap, stated rather than pretended away: when the final
        // page happens to hold exactly 45 there is nothing to notice, so the
        // scan still ends by exhausting its retries. Everything read is kept.
        var pager = new JalistPager(GAP, WAIT, RETRIES, GRACE, MAX_PAGES);
        var server = new FakeServer(3, false);   // every page full, no wrap
        var r = run(pager, server, 500);

        assertEquals(List.of(1, 2, 3), r.read);
        assertEquals(Action.GAVE_UP, r.ending);
    }
}
