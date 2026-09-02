package com.yeedar.update;

/**
 * Numeric comparison of two mod version strings.
 *
 * <p>Deliberately not a full semver implementation. Release tags carry a "v"
 * prefix, {@code mod_version} does not, and dev builds pick up suffixes like
 * "-SNAPSHOT" or "+1.21.8" — so the job is to reduce both sides to
 * major/minor/patch and compare those. Ordering prerelease suffixes is
 * semver's hardest corner and would buy nothing: a suffixed build is the same
 * release as the one it is built from, for the only question asked here.
 */
public final class VersionCompare {

    private VersionCompare() {}

    /**
     * True when {@code candidate} is strictly newer than {@code current}.
     *
     * <p>Anything unparseable on either side returns false. A malformed tag is
     * not worth a wrong message, and the caller's only response to true is to
     * tell the player to go update — so silence is the safe default.
     */
    public static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** Major/minor/patch, or null if there is no leading number to read. */
    private static int[] parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);

        // Keep the leading run of digits and dots; drop any suffix from the
        // first character that is neither.
        int end = 0;
        while (end < s.length()
                && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        s = s.substring(0, end);
        if (s.isEmpty()) return null;

        String[] parts = s.split("\\.", -1);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            // Missing trailing segments are zero, so "1.6" equals "1.6.0".
            if (i >= parts.length || parts[i].isEmpty()) continue;
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;   // e.g. a segment too large for an int
            }
        }
        return out;
    }
}
