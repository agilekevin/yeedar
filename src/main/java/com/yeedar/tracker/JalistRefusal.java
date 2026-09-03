package com.yeedar.tracker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises JukeAlert telling us we cannot read a namelayer's snitches.
 *
 * <p>Without this the scanner asks for a group, gets refused immediately, and
 * then sits out the full arm timeout waiting for a window the server has
 * already said it will not open. The refusal is information we were given at
 * once and were throwing away.
 *
 * <p>The bias is deliberately toward missing a refusal rather than inventing
 * one. A false negative costs the ten seconds this class exists to save; a
 * false positive abandons a group the player really can read, and they would
 * have no idea why their snitches went missing. Hence matching on two
 * independent parts of the sentence rather than one loose keyword.
 */
public final class JalistRefusal {

    /**
     * JukeAlert's other refusal, which names the group.
     *
     * <p>Better than the generic line in two ways. It can be attributed to the
     * group being scanned, rather than to "something was refused just now".
     * And it is the only signal available in the case that costs the most: a
     * group the player belongs to but cannot list snitches for gets an EMPTY
     * window rather than none, so the scan leaves its armed phase and the
     * generic refusal goes unwatched — which burned a pager timeout and then
     * reported a green tick for "0 snitches" when the truth was "denied".
     */
    private static final Pattern NO_PERMISSION = Pattern.compile(
            "permission to list snitches for the group (\\S+?)\\.?$");

    private JalistRefusal() {}

    /** The group a named refusal is about, or null if this is not one. */
    public static String noPermissionGroup(String line) {
        if (line == null) return null;
        Matcher m = NO_PERMISSION.matcher(stripCodes(line));
        return m.find() ? m.group(1) : null;
    }

    /** Whether this line refuses the named group, ignoring case. */
    public static boolean isNoPermissionFor(String line, String group) {
        if (group == null) return false;
        String named = noPermissionGroup(line);
        return named != null && named.equalsIgnoreCase(group);
    }

    private static String stripCodes(String line) {
        return line.replaceAll("§.", "").trim();
    }

    public static boolean isNoAccess(String line) {
        if (line == null) return false;
        String text = normalize(line);
        boolean denied = text.contains("do not have access")
                || text.contains("dont have access");
        // "snitch" as well, so an unrelated permissions message elsewhere in
        // chat cannot abort a scan that was working.
        return denied && text.contains("snitch");
    }

    /** Strip Minecraft formatting, fold case, and flatten curly apostrophes. */
    private static String normalize(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§') {
                i++;            // and the format character that follows it
                continue;
            }
            // "don't" and "don’t" must compare equal; dropping the apostrophe
            // entirely handles both without caring which arrived.
            if (c == '\'' || c == '’') continue;
            out.append(c);
        }
        return out.toString().toLowerCase();
    }
}
