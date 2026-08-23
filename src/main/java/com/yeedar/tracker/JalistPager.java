package com.yeedar.tracker;

import java.util.HashSet;
import java.util.Set;

/**
 * The paging state machine for a {@code /jalist} scan, with no Minecraft types
 * in it so it can be tested directly.
 *
 * <p>This exists because getting the paging right took six attempts against a
 * live server, each with a multi-minute feedback loop, and every one of the
 * bugs was in this logic rather than in talking to Minecraft: counting the
 * client's predicted empty container as a page, stopping when the next-page
 * arrow disappeared, waiting instead of re-clicking a dropped click, and then
 * re-clicking so eagerly that the server ran ahead of what we had read.
 *
 * <p>The caller feeds it one observation per tick and performs whatever action
 * it returns. It never talks to the game itself.
 */
public final class JalistPager {

    /** What the caller should do this tick. */
    public enum Action {
        /** Nothing — still waiting for something to change. */
        IDLE,
        /** Read the page currently on screen, then keep polling. */
        READ,
        /** Click the next-page control. */
        CLICK,
        /** Stop: every page has been read. */
        DONE,
        /** Stop: a page never arrived despite retries. */
        GAVE_UP,
        /** Stop: the window closed and did not come back. */
        CLOSED,
    }

    /** What the caller can see this tick. */
    public static final class View {
        /** Null when no jalist window is open. */
        public final String fingerprint;
        /** Snitch items on screen; zero means the container is not populated. */
        public final int snitchCount;

        public View(String fingerprint, int snitchCount) {
            this.fingerprint = fingerprint;
            this.snitchCount = snitchCount;
        }

        public static View closed() {
            return new View(null, 0);
        }
    }

    private final int gapTicks;
    private final int waitTicks;
    private final int maxRetries;
    private final int reopenGraceTicks;
    private final int maxPages;

    private final Set<String> seenPages = new HashSet<>();
    private String lastRead = null;
    private int pagesRead = 0;
    private int retries = 0;
    private int sinceClick = 0;
    private int sinceRead = 0;
    private int closedTicks = 0;
    private boolean awaitingPage = false;
    private boolean finished = false;

    public JalistPager(int gapTicks, int waitTicks, int maxRetries,
                       int reopenGraceTicks, int maxPages) {
        this.gapTicks = gapTicks;
        this.waitTicks = waitTicks;
        this.maxRetries = maxRetries;
        this.reopenGraceTicks = reopenGraceTicks;
        this.maxPages = maxPages;
    }

    public int pagesRead() {
        return pagesRead;
    }

    public boolean finished() {
        return finished;
    }

    /** Advance one tick against what the caller can currently see. */
    public Action tick(View view) {
        if (finished) return Action.IDLE;

        // No window: requesting a page can close and reopen it, so a gap is
        // normal and only a sustained absence means the scan is over.
        if (view.fingerprint == null || view.snitchCount == 0) {
            if (++closedTicks > reopenGraceTicks) return finish(Action.CLOSED);
            if (awaitingPage && ++sinceClick > waitTicks) return retryOrGiveUp();
            return Action.IDLE;
        }
        closedTicks = 0;

        // Same contents as the page we last read: nothing has changed yet.
        if (view.fingerprint.equals(lastRead)) {
            if (awaitingPage && ++sinceClick > waitTicks) return retryOrGiveUp();
            if (!awaitingPage && ++sinceRead >= gapTicks) {
                sinceRead = 0;
                awaitingPage = true;
                sinceClick = 0;
                return Action.CLICK;
            }
            return Action.IDLE;
        }

        // Something new is on screen. If we have read it before, paging has
        // wrapped to the start and there is nothing further to fetch.
        //
        // Only trust that once no retry clicks are outstanding: extra clicks
        // can run the server past pages we never read, and the page that then
        // lands may well be one we have already seen. Treating that as a wrap
        // ended a scan early at page 7 of a longer list.
        if (seenPages.contains(view.fingerprint)) {
            if (retries > 0) {
                // Skipped ahead. Adopt this page as our position and carry on
                // clicking: the pages we missed are lost either way, but the
                // rest are not. Without adopting it, the very next tick sees
                // the same already-seen fingerprint with retries back at zero
                // and stops after all.
                lastRead = view.fingerprint;
                retries = 0;
                awaitingPage = false;
                sinceClick = 0;
                sinceRead = 0;
                return Action.IDLE;
            }
            return finish(Action.DONE);
        }

        seenPages.add(view.fingerprint);
        lastRead = view.fingerprint;
        pagesRead++;
        retries = 0;
        awaitingPage = false;
        sinceClick = 0;
        sinceRead = 0;
        if (pagesRead >= maxPages) return finish(Action.DONE);
        return Action.READ;
    }

    private Action retryOrGiveUp() {
        sinceClick = 0;
        if (retries >= maxRetries) return finish(Action.GAVE_UP);
        retries++;
        return Action.CLICK;
    }

    private Action finish(Action how) {
        finished = true;
        return how;
    }
}
