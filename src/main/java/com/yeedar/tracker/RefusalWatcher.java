package com.yeedar.tracker;

/**
 * Decides, from chat alone, whether the namelayer being scanned was refused.
 *
 * <p>Its own unit because getting this wrong is expensive and silent. An
 * earlier version attributed one group's refusal to the next group in the
 * queue and skipped yeetborders — all 2025 snitches of it — while reporting a
 * perfectly tidy scan.
 *
 * <p>JukeAlert refuses in a <em>pair</em>:
 *
 * <pre>
 *   You do not have permission to list snitches for the group YEETaccess
 *   You do not have access to any group's snitches.
 * </pre>
 *
 * <p>Only the first names a group. The second is the one players notice and
 * the one that reads as though the whole scan failed — and because it names
 * nobody, it can only be attributed to the group currently being waited on.
 * Acting on the first line advances to the next group within a tick, so the
 * second lands while that group is armed and gets blamed for a refusal that
 * was never about it. Hence swallowing exactly one trailing generic line: not
 * a time window, which would also eat the real refusal of a group that
 * genuinely follows a refused one.
 *
 * <p>There is no third state to model. A group the player cannot read either
 * opens no window at all, or opens an empty one and refuses — both are covered
 * by watching until the window opens.
 */
public class RefusalWatcher {

    private String group;
    private boolean armed;
    private boolean refused;
    /** True when a named refusal was handled and its generic tail is still
     *  expected. Cleared by that line or by a window opening, so it can never
     *  go stale and suppress a real refusal later. */
    private boolean swallowNextGeneric;

    /** Start waiting on a group's jalist window. */
    public void beginGroup(String group) {
        this.group = group;
        this.armed = true;
        this.refused = false;
    }

    /** The window arrived, so this group is readable. */
    public void windowOpened() {
        armed = false;
        swallowNextGeneric = false;
    }

    /** Feed one line of server chat. */
    public void onChat(String line) {
        if (group == null || line == null) return;

        // Names the group, so it is trustworthy whether or not a window opened
        // — and a window DOES open, empty, for a group the player belongs to
        // but cannot list snitches for, which is precisely when the generic
        // line is no longer being watched.
        if (JalistRefusal.isNoPermissionFor(line, group)) {
            refused = true;
            swallowNextGeneric = true;
            return;
        }

        if (JalistRefusal.isNoAccess(line)) {
            if (swallowNextGeneric) {
                swallowNextGeneric = false;   // the tail of a refusal already handled
                return;
            }
            if (armed) refused = true;
        }
    }

    public boolean isRefused() {
        return refused;
    }

    /** Forget everything, for the start of a scan. */
    public void reset() {
        group = null;
        armed = false;
        refused = false;
        swallowNextGeneric = false;
    }
}
