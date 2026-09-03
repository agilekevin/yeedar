package com.yeedar.tracker;

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

    private JalistRefusal() {}

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
