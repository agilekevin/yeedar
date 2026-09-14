package com.yeedar.tracker;

import com.yeedar.api.YeetVisClient;
import com.yeedar.config.YeedarConfig;
import com.yeedar.net.EdenServer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Feeds {@link LogoutDetector} from the running client, once a second.
 *
 * <p>Kept apart from PlayerTracker on purpose. PlayerTracker reports the
 * observer's position and must keep doing exactly that; this one reports the
 * observed player's. Two position policies in one class is how the privacy rule
 * gets broken by a later edit that only meant to tidy something up.
 *
 * <p>Everything here is adapter work — read the world, apply the eligibility
 * rules, hand two plain collections to the detector. The rule itself lives in
 * the detector, where it is tested.
 */
public final class LogoutTracker {

    private static final LogoutTracker INSTANCE = new LogoutTracker();
    private static final int CHECK_INTERVAL = 20; // ticks (1 second)

    private final LogoutDetector detector = new LogoutDetector();
    /** Local-only narration, off unless its marker file exists. Never uploads. */
    private final LogoutDebug debug = new LogoutDebug();
    private int tickCounter = 0;
    private Object lastWorld = null;

    /**
     * Which gate stopped a nearby player being reportable, for the debug view.
     *
     * <p>Checked in the order that matters to a reader: an unloaded friendly
     * list is a client-state problem and explains everything else, so it is
     * named first rather than being masked by a per-player reason.
     */
    static String suppressionReason(boolean ignored, boolean friendly,
                                    boolean friendlyListLoaded) {
        if (!friendlyListLoaded) return "friendly list not loaded yet";
        if (friendly) return "friendly";
        if (ignored) return "ignored name";
        return "not in range";
    }

    public static LogoutTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Whether a nearby player's logout is ours to publish.
     *
     * <p>Its own method, and package-visible, so the privacy rule is pinned by
     * a test rather than buried in a loop. Getting this wrong publishes an
     * ally's exact position, and {@link LogoutDetector} cannot catch it —
     * filtering is deliberately the caller's job.
     *
     * <p>{@code friendlyListLoaded} exists because {@link FriendlyTracker}
     * cannot distinguish "confirmed not friendly" from "hasn't heard yet": on a
     * cold launch, or after any failed refresh, {@link FriendlyTracker#isFriendly}
     * reads false for everyone, allies included. Until the list has loaded at
     * least once, "not friendly" is a guess rather than a fact, and guessing
     * wrong here publishes an ally's exact position — so an unloaded list makes
     * nothing reportable, regardless of the other three conditions.
     */
    static boolean isReportable(boolean inRange, boolean ignored,
                                 boolean friendly, boolean friendlyListLoaded) {
        return inRange && !ignored && !friendly && friendlyListLoaded;
    }

    public void tick(MinecraftClient client) {
        tickCounter++;

        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (client.world == null || client.player == null || network == null) {
            detector.reset();
            debug.reset();
            lastWorld = null;
            return;
        }

        // Dimension switch or reconnect. Both empty and refill the tab list, and
        // a diff across one would report everybody as having logged out. The
        // reset makes the next scan a fresh baseline, and returning here — like
        // the other three reset paths in this method — keeps that guarantee
        // local to tick() instead of resting on how LogoutDetector happens to
        // treat a null baseline today. Falling through to build nearby/online
        // and scan() in the same tick is currently harmless, but only because
        // of internals owned by a different class; a later edit there could
        // silently remove the property this path would otherwise depend on,
        // and the failure mode is publishing everyone's coordinates after a
        // nether portal.
        if (lastWorld != client.world) {
            detector.reset();
            debug.reset();
            lastWorld = client.world;
            return;
        }

        if (tickCounter % CHECK_INTERVAL != 0) return;

        YeedarConfig config = YeedarConfig.getInstance();
        if (!config.isTrackingEnabled() || !EdenServer.connected()) {
            // Not reporting from here. Drop the baseline too, so coming back
            // does not diff against a tab list from somewhere else entirely.
            detector.reset();
            debug.reset();
            return;
        }

        double range = config.getDetectionRange();
        double rangeSq = range * range;
        FriendlyTracker friendlyTracker = FriendlyTracker.getInstance();

        // Off unless the marker file exists, so the extra bookkeeping below
        // costs a normal client one stat() per second and nothing else.
        boolean debugging = debug.isEnabled();
        debug.announceIfChanged(debugging);
        List<LogoutDetector.Sighting> nearbyAll = debugging ? new ArrayList<>() : null;
        Map<String, String> verdicts = debugging ? new HashMap<>() : null;

        List<LogoutDetector.Sighting> nearby = new ArrayList<>();
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;

            String name = player.getName().getString();
            boolean inRange = client.player.squaredDistanceTo(player) <= rangeSq;
            boolean ignored = config.isIgnored(name);
            boolean friendly = friendlyTracker.isFriendly(name);
            boolean friendlyListLoaded = friendlyTracker.isLoaded();
            boolean reportable = isReportable(inRange, ignored, friendly, friendlyListLoaded);

            if (debugging && inRange) {
                // The shadow view sees everyone in range, reportable or not.
                // Watching an ally come back SUPPRESSED is the only way to
                // observe the privacy rule holding — otherwise it is
                // indistinguishable from the feature being broken.
                nearbyAll.add(new LogoutDetector.Sighting(
                        player.getUuid(), name, player.getX(), player.getY(), player.getZ()));
                verdicts.put(name, reportable ? "§awould report"
                        : "§cSUPPRESSED §7(" + suppressionReason(ignored, friendly, friendlyListLoaded) + ")");
            }

            if (!reportable) continue;

            nearby.add(new LogoutDetector.Sighting(
                    player.getUuid(), name, player.getX(), player.getY(), player.getZ()));
        }

        // Every UUID the server lists, not just the ones shown in the tab list:
        // a server that hides a player by clearing their "listed" flag would
        // otherwise read as that player logging out.
        Set<UUID> online = Set.copyOf(network.getPlayerUuids());

        for (LogoutDetector.Logout logout : detector.scan(nearby, online)) {
            YeetVisClient.sendLogoutEvent(
                    logout.name(), logout.x(), logout.y(), logout.z());
        }

        // Last, and on its own detector. Narration must never be able to change
        // what was uploaded above — it reads the same world and the same tab
        // list, reaches its own conclusions, and prints them.
        if (debugging) {
            debug.scanAndNarrate(nearbyAll, online, verdicts);
        }
    }
}
