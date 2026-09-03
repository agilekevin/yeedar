package com.yeedar.tracker;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The namelayers a player belongs to, read from
 * {@code /namelayer:listgroups}.
 *
 * <p>Exists so a scan stops asking JukeAlert for groups the player cannot
 * read. Those refusals cost a round trip each and print "You do not have
 * access to any group's snitches" — a message about one group, phrased as
 * though the whole scan had failed.
 *
 * <p>The output is paginated and looks like this, verbatim:
 *
 * <pre>
 *   Page 1 of 2.
 *   ! : (MEMBERS)
 *   YEET : (MODS)
 *   YEETborders : (MODS)
 * </pre>
 *
 * <p>Two properties drive the design. Names come back in the server's casing
 * ("YEETborders") while the shared defaults are stored lowercase, so matching
 * is case-insensitive. And the listing spans pages, so a partial reading is
 * worse than none: acting on page one alone would conclude the player is not
 * in the groups listed on page two and silently stop scanning them, which
 * trades a confusing message for missing data and no message at all. Hence
 * {@link #isComplete()} — callers must not filter unless it is true.
 */
public class GroupList {

    private static final Pattern HEADER = Pattern.compile("^Page (\\d+) of (\\d+)\\.?\\s*$");
    // "<name> : (RANK)". Anchored at both ends and the rank restricted to
    // upper case, so a chat line that happens to contain " : (" cannot inject
    // a group — a false entry here would filter out a real namelayer.
    private static final Pattern ENTRY = Pattern.compile("^(\\S+) : \\(([A-Z_]+)\\)$");

    /** Which page of how many. */
    public record Header(int page, int total) {}

    private final Set<String> groups = new LinkedHashSet<>();
    private final Set<Integer> pagesSeen = new LinkedHashSet<>();
    private int expectedPages = 0;

    public static Header parseHeader(String line) {
        if (line == null) return null;
        Matcher m = HEADER.matcher(strip(line));
        if (!m.matches()) return null;
        return new Header(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** The group named on this line, or null if it is not an entry. */
    public static String parseGroup(String line) {
        if (line == null) return null;
        Matcher m = ENTRY.matcher(strip(line));
        return m.matches() ? m.group(1) : null;
    }

    /** Feed one line of server chat. Anything unrecognised is ignored. */
    public void accept(String line) {
        Header h = parseHeader(line);
        if (h != null) {
            expectedPages = h.total();
            pagesSeen.add(h.page());
            return;
        }
        String group = parseGroup(line);
        if (group != null) groups.add(group);
    }

    /**
     * True only when every page has been seen.
     *
     * <p>Deliberately strict: with no header we do not know how many pages
     * exist, so we cannot know the list is whole, and a caller must fall back
     * to scanning everything rather than filtering on a guess.
     */
    public boolean isComplete() {
        return expectedPages > 0 && pagesSeen.size() >= expectedPages;
    }

    public int expectedPages() {
        return expectedPages;
    }

    public int pagesSeen() {
        return pagesSeen.size();
    }

    /** The next page not yet seen, or 0 when none is outstanding. */
    public int nextMissingPage() {
        for (int p = 1; p <= expectedPages; p++) {
            if (!pagesSeen.contains(p)) return p;
        }
        return 0;
    }

    /** Group names as the server spelled them. */
    public Set<String> groups() {
        return groups;
    }

    /** Whether the player is in this namelayer, ignoring case. */
    public boolean contains(String name) {
        if (name == null) return false;
        for (String g : groups) {
            if (g.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String strip(String line) {
        return line.replaceAll("§.", "").trim();
    }
}
