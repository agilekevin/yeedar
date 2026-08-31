package com.yeedar.terrain;

/**
 * How long to wait before retrying an upload that did not land.
 *
 * Without this, a failure is a busy loop: TerrainBuffer.failed() puts the
 * batch straight back, and the next upload interval sends the same ~1.8MB
 * again ten seconds later, for as long as the client is open. Every failure
 * mode shares that shape — no token, a revoked token, a server mid-deploy,
 * the hourly rate cap — so they share one remedy rather than four.
 *
 * Counted in upload intervals rather than milliseconds because that is the
 * only clock the caller has: it is driven from the client tick, and a tick
 * count is what it can hand us. Doubling from a single interval means a
 * server that blinks during a deploy is retried almost at once, while a
 * client that will never succeed backs off to hourly and stays there.
 */
public final class UploadBackoff {

    /** Ceiling, in upload intervals. At 200 ticks each, 360 is about an hour —
     *  the window the server's rate cap is measured over, so a client that hit
     *  the cap wakes up roughly when it clears. */
    public static final int MAX_SKIPS = 360;

    private int skips = 0;    // intervals still to sit out
    private int penalty = 0;  // length of the next one

    /**
     * Whether this interval may upload. Counts down when it may not, so it
     * must be called exactly once per interval and only when there is
     * something to send.
     */
    public boolean allow() {
        if (skips > 0) {
            skips--;
            return false;
        }
        return true;
    }

    /** The upload landed: forget the penalty entirely, don't merely halve it. */
    public void succeeded() {
        skips = 0;
        penalty = 0;
    }

    /** The upload did not land: wait, then wait twice as long, up to the cap. */
    public void failed() {
        penalty = penalty == 0 ? 1 : Math.min(penalty * 2, MAX_SKIPS);
        skips = penalty;
    }

    /** Intervals left before the next attempt. 0 means the next one goes. */
    public int skipsRemaining() {
        return skips;
    }
}
