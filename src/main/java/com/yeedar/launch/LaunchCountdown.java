package com.yeedar.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat beats of a launch countdown.
 *
 * <p>Pure, and separated from the sending for one reason: the last beat has to
 * land at the exact second the server scheduled the impact for, because the map
 * animates against that same timestamp. If the two disagree the player watches
 * the strike happen twice, at two different moments, which is worse than having
 * no countdown at all.
 *
 * <p>The server owns the countdown length and can change it, so this takes it
 * as input rather than assuming 20 seconds.
 */
public final class LaunchCountdown {

    private LaunchCountdown() {}

    /** One line, and how many seconds after the command it should be said. */
    public record Beat(int atSecond, String text) {}

    /** Seconds before impact that get a line, longest first. */
    private static final int[] MARKS = {20, 10, 5, 3, 2, 1};

    public static List<Beat> forSeconds(int countdownSeconds) {
        List<Beat> beats = new ArrayList<>();
        if (countdownSeconds <= 0) {
            // No time at all: say the only thing that matters.
            beats.add(new Beat(0, "§c§lIMPACT."));
            return beats;
        }

        beats.add(new Beat(0, "§6§lLAUNCH AUTHORIZED. §7Silo doors open."));

        for (int mark : MARKS) {
            int at = countdownSeconds - mark;
            // Skip marks that would fall at or before the opening line — a
            // "T-20" printed after launch would be nonsense.
            if (at <= 0) continue;
            beats.add(new Beat(at, "§7T-minus §f" + mark + "§7..."));
        }

        beats.add(new Beat(countdownSeconds, "§c§lIMPACT."));
        return beats;
    }
}
