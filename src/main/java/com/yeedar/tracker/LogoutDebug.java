package com.yeedar.tracker;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /** Forget the baseline. Mirrors the real tracker's reset points. */
    public void reset() {
        shadow.reset();
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
        for (LogoutDetector.Logout logout : shadow.scan(nearbyAll, online)) {
            String verdict = verdicts.getOrDefault(logout.name(), "§7no longer nearby, verdict unknown");
            chat(String.format("§e[Yeedar] logout: §f%s §7at §f%.0f, %.0f, %.0f §7— %s",
                    logout.name(), logout.x(), logout.y(), logout.z(), verdict));
        }
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
