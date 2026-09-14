package com.yeedar.tracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides who just logged out, and where they were standing when they did.
 *
 * <p>Mirrors what Combat Radar does, which is the behaviour we were given
 * permission to copy. Two facts per scan: who is loaded nearby and where, and
 * who the server still lists as online. A player who leaves the second set is a
 * logout; their coordinates come from the first, as of the PREVIOUS scan —
 * their entity is already gone by the time the tab list drops them, so the
 * current scan cannot have seen them.
 *
 * <p>A logout we have no previous sighting for reports nothing at all, rather
 * than a logout with no position. That is the privacy property the whole
 * feature rests on: a precise position is published only for someone we
 * actually watched, in range, in the tick before they vanished. It is also why
 * a coordinate-less event is not worth sending — YeetVis would show it as a
 * player parked nowhere.
 *
 * <p>Free of Minecraft types so the rule can be tested on its own. The caller
 * owns reading the world and sending the result; this owns the decision.
 *
 * <p>Not thread-safe. {@link #scan} mutates {@link #previousNearby} and
 * {@link #previousOnline} with no synchronisation whatsoever, so it must be
 * driven from a single thread — the client tick thread. {@link
 * com.yeedar.tracker.FriendlyTracker} needed a {@code volatile} field for
 * exactly this reason, because an HTTP callback thread reached into it; the
 * caller this class is built for also does async HTTP, so the same mistake is
 * one careless wiring away. Do not call {@code scan()} from a callback.
 */
public final class LogoutDetector {

    /** One player, seen at a precise position, in range, this scan. */
    public record Sighting(UUID id, String name, double x, double y, double z) {}

    /** One logout worth reporting, with the position it happened at. */
    public record Logout(String name, double x, double y, double z) {}

    private Map<UUID, Sighting> previousNearby = new HashMap<>();

    /**
     * The tab list as of the previous scan, or null for "no baseline yet".
     *
     * <p>Null is the whole reconnect defence. The tab list empties on
     * disconnect and refills on join, so the first reading after any
     * (re)connection would otherwise diff against a stale set and report every
     * player on the server as having logged out, each at their last known
     * position. A null baseline reports nothing and simply becomes the baseline.
     */
    private Set<UUID> previousOnline = null;

    /**
     * Forget everything. The next scan re-establishes the baseline and reports
     * nothing.
     *
     * <p>Called on world change, on disconnect, and whenever the client stops
     * being somewhere we report from.
     */
    /**
     * Scans to ignore a repeat logout for the same player.
     *
     * <p>Measured in scans rather than seconds so this class keeps no clock and
     * stays testable as pure logic; the caller ticks it once a second, so this
     * is roughly fifteen seconds.
     *
     * <p>Sized from what was actually observed on EdenMC: a player's tab-list
     * entry gets rewritten rather than removed — dropping and reappearing with
     * the online total unchanged — and every drop looked like a fresh logout.
     * Repeats came 3 and 8 seconds apart, so the window needs real margin over
     * that. The cost is that somebody who genuinely logs out, back in, and out
     * again inside fifteen seconds is reported once; that is rare, and one
     * report of a spot they are standing at anyway loses nothing.
     */
    private static final int REPEAT_SUPPRESSION_SCANS = 15;

    /**
     * Scans remaining before each player may be reported again.
     *
     * <p>Keyed by UUID rather than name: a rename between the two halves of a
     * flicker would otherwise slip past the suppression entirely.
     */
    private final Map<UUID, Integer> suppressedFor = new HashMap<>();

    public void reset() {
        previousNearby = new HashMap<>();
        previousOnline = null;
        // Suppression goes too. It exists to collapse one disconnect's worth of
        // flicker, and a reset means the next scan is a fresh baseline that
        // cannot report anything anyway — carrying stale counters across a
        // reconnect would only suppress the first real logout afterwards.
        suppressedFor.clear();
    }

    /**
     * Fold one scan in, and return the logouts it revealed.
     *
     * @param nearbyNow every eligible player currently loaded and in range,
     *                  at their precise position. Filtering — friendlies,
     *                  ignored names, range — is the caller's job, so that this
     *                  class has one reason to change and the policy lives
     *                  where the config does.
     * @param onlineNow every UUID the server currently lists as online.
     */
    public List<Logout> scan(Collection<Sighting> nearbyNow, Set<UUID> onlineNow) {
        List<Logout> logouts = new ArrayList<>();

        // Age the suppression window first, so a count set on the scan that
        // reported someone covers the scans AFTER it rather than including it.
        suppressedFor.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });

        if (previousOnline != null) {
            for (UUID id : previousOnline) {
                if (onlineNow.contains(id)) continue;
                // Gone from the tab list. Report only if we were watching them
                // when it happened.
                Sighting last = previousNearby.get(id);
                if (last == null) continue;
                // ...and only if we have not just reported them. A tab entry
                // that is rewritten rather than removed leaves and returns
                // repeatedly, and each departure reads here as a fresh logout.
                if (suppressedFor.containsKey(id)) continue;
                suppressedFor.put(id, REPEAT_SUPPRESSION_SCANS);
                logouts.add(new Logout(last.name(), last.x(), last.y(), last.z()));
            }
        }

        Map<UUID, Sighting> nextNearby = new HashMap<>();
        for (Sighting sighting : nearbyNow) {
            nextNearby.put(sighting.id(), sighting);
        }
        previousNearby = nextNearby;
        previousOnline = Set.copyOf(onlineNow);

        return logouts;
    }
}
