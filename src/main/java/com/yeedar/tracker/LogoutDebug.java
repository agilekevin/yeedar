package com.yeedar.tracker;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Narrates logout detection to the local chat, without uploading anything.
 *
 * <p>Exists because the interesting cases are hard to arrange on purpose. Deep
 * inside your own nation's borders every player who logs out nearby is an ally,
 * and an ally is exactly who {@link LogoutTracker} refuses to report — so the
 * one path you most want to watch working is the one that, correctly, produces
 * no evidence at all.
 *
 * <p>This runs a SECOND {@link LogoutDetector} over every nearby player,
 * unfiltered, and prints what it sees along with the verdict the real path
 * would have reached. The real path is not touched and not consulted: nothing
 * here can cause an upload, and nothing here can suppress one. Watching the
 * word SUPPRESSED appear next to an ally's name is the point — it is the only
 * way to see the privacy rule hold, rather than infer it from silence.
 *
 * <p>Gated on a marker file rather than a command, so it adds no surface to
 * {@code /yeedar} and there is nothing to remove afterwards:
 *
 * <pre>config/yeedar-debug-logouts</pre>
 *
 * <p>Checked on every scan rather than once at startup, so creating or deleting
 * the file takes effect without restarting the game — the whole point is to
 * turn this on mid-session when somebody is about to log out.
 */
public final class LogoutDebug {

    /** Marker file name. Presence enables the narration; contents are ignored. */
    private static final String MARKER_NAME = "yeedar-debug-logouts";

    private final LogoutDetector shadow = new LogoutDetector();

    /** Last known marker state, so the on/off notice is printed once per flip. */
    private boolean announced = false;

    /**
     * The tab list as of the previous narrated scan, for transition logging.
     *
     * <p>Held here rather than read from {@link LogoutDetector}, which keeps its
     * own copy private on purpose — this is a diagnostic and has no business
     * reaching into the class whose correctness it is meant to observe.
     */
    private Set<UUID> previousOnline = null;

    /**
     * Everyone seen nearby this session, so transitions can be narrated for
     * players we actually care about instead of all ~150 on the server.
     *
     * <p>Never pruned. Bounded in practice by how many distinct players come
     * within detection range in one sitting, which is small; and this only
     * accumulates at all while the marker file is present.
     */
    private final Map<UUID, String> seenNearby = new HashMap<>();

    /**
     * Whether the marker file is present right now.
     *
     * <p>The path is resolved here rather than in a static field on purpose.
     * {@code FabricLoader.getInstance()} does not exist in a plain JVM, and
     * LogoutTracker holds a static INSTANCE — so resolving at class-init time
     * made merely calling the static {@code isReportable} throw
     * ExceptionInInitializerError, taking out the whole LogoutTrackerTest
     * suite. The privacy rule has to stay testable without a running game;
     * a debug affordance must not be what breaks that.
     */
    public boolean isEnabled() {
        try {
            Path marker = FabricLoader.getInstance().getConfigDir().resolve(MARKER_NAME);
            return Files.exists(marker);
        } catch (Throwable t) {
            // No Fabric (unit tests), or a filesystem that will not answer.
            // Neither is a reason to break the tick — this is the least
            // important thing happening here.
            return false;
        }
    }

    /**
     * Say so, once, whenever the marker appears or disappears.
     *
     * <p>Separate from the narration itself because a line that repeats every
     * second is a line people stop reading — the same reasoning the 426 notice
     * and UpdateNotifier both use.
     */
    public void announceIfChanged(boolean enabled) {
        if (enabled == announced) return;
        announced = enabled;
        chat(enabled
                ? "§e[Yeedar] Logout debug ON §7(config/yeedar-debug-logouts). "
                        + "Detections are printed here only — nothing extra is uploaded."
                : "§7[Yeedar] Logout debug off.");
        if (!enabled) {
            // Drop the baseline with the feature, so re-enabling mid-session
            // does not diff against a tab list from before it was switched off
            // and narrate a burst of logouts that never happened.
            shadow.reset();
        }
    }

    /**
     * Forget the baseline. Mirrors the real tracker's reset points.
     *
     * <p>The transition baseline goes too. Without that, a reconnect would
     * narrate every player seen nearby as having LEFT the tab list at once —
     * which is exactly the false alarm the real path's reset exists to prevent,
     * and a debug view that fakes it is worse than no debug view.
     */
    public void reset() {
        shadow.reset();
        previousOnline = null;
    }

    /**
     * Fold one unfiltered scan in and narrate whatever it reveals.
     *
     * <p>Verdicts are keyed by name rather than UUID because {@link
     * LogoutDetector.Logout} deliberately carries only what gets published —
     * name and position — and widening that record to carry a UUID purely so
     * this debug view could join on it would mean editing the privacy-critical
     * class for the convenience of a diagnostic. Two players with the same
     * display name would confuse the verdict here; nothing else.
     *
     * @param nearbyAll every nearby player, INCLUDING the ones the real path
     *                  filters out — that is the whole value of this view.
     * @param online    the same tab-list snapshot the real path uses.
     * @param verdicts  per player name, why the real path would or would not
     *                  report them, already rendered for a human.
     */
    public void scanAndNarrate(List<LogoutDetector.Sighting> nearbyAll,
                               Set<UUID> online,
                               Map<String, String> verdicts) {
        for (LogoutDetector.Sighting s : nearbyAll) {
            seenNearby.put(s.id(), s.name());
        }
        narrateTabTransitions(online);

        for (LogoutDetector.Logout logout : shadow.scan(nearbyAll, online)) {
            String verdict = verdicts.getOrDefault(logout.name(), "§7no longer nearby, verdict unknown");
            chat(String.format("§e[Yeedar] logout: §f%s §7at §f%.0f, %.0f, %.0f §7— %s",
                    logout.name(), logout.x(), logout.y(), logout.z(), verdict));
        }
    }

    /**
     * Print every tab-list arrival and departure for players seen nearby.
     *
     * <p>Added to answer a specific question the logout lines alone could not:
     * one deliberate log-out produced TWO detections five seconds apart at the
     * same block. Either the player relogged that fast, or the tab list drops
     * them, briefly re-adds them, and drops them again — and a detection is
     * emitted each time, because the detector has no memory of having already
     * reported someone. These lines show which, by making the underlying
     * transitions visible instead of only their consequence.
     *
     * <p>The total is printed alongside, so a bulk change (a server hiccup
     * rewriting the whole list) is distinguishable from one player flickering.
     */
    private void narrateTabTransitions(Set<UUID> online) {
        if (previousOnline != null) {
            for (UUID id : seenNearby.keySet()) {
                boolean was = previousOnline.contains(id);
                boolean now = online.contains(id);
                if (was == now) continue;
                chat(String.format("§8[Yeedar] tab: §7%s §8%s §8(%d online)",
                        seenNearby.get(id), now ? "JOINED" : "LEFT", online.size()));
            }
        }
        previousOnline = Set.copyOf(online);
    }

    private static void chat(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(message), false);
            }
        });
    }
}
