package com.yeedar.update;

/**
 * Decides whether to tell the player about a newer release.
 *
 * <p>Deliberately free of Minecraft types so the rule can be tested on its
 * own. The caller owns the chat message; this owns the decision.
 */
public final class UpdateNotifier {

    private UpdateNotifier() {}

    /**
     * True when the player should be told, right now, about {@code latest}.
     *
     * <p>Once per version, per install. {@code lastNotified} is the tag this
     * install has already announced; when it matches, we stay quiet no matter
     * how many times the player rejoins. People relog constantly, and a
     * message that returns every session is one they stop reading — which
     * fails at the only job it has. {@code /yeedar status} covers anyone who
     * saw it and forgot.
     */
    public static boolean shouldNotify(String currentVersion,
                                       UpdateChecker.Release latest,
                                       String lastNotified,
                                       boolean enabled) {
        if (!enabled) return false;
        if (latest == null) return false;              // check hasn't landed, or never will
        if (currentVersion == null || currentVersion.isBlank()) return false;
        if (latest.version().equals(lastNotified)) return false;
        return VersionCompare.isNewer(latest.version(), currentVersion);
    }
}
