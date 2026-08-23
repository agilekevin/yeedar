package com.yeedar.tracker;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One snitch parsed out of the JukeAlert {@code /jalist} GUI.
 *
 * <p>The GUI is an inventory of fake items; everything shown on hover is
 * already present in the item's display name and lore, so nothing needs to be
 * scraped off the screen. Each entry looks like:
 *
 * <pre>
 *   item      NOTE_BLOCK (snitch) or JUKEBOX (logging snitch)
 *   name      north gate
 *   lore[0]   Location: world 100 64 -200
 *   lore[1]   Group: yeet
 *   lore[2]   Will cull in 12h 30m 5s
 * </pre>
 *
 * <p>Two things matter about the timer. JukeAlert states only whichever event
 * comes next, so a reading yields a dormancy time or a cull time but never
 * both. And it is <em>relative</em>, so it is resolved to an absolute instant
 * here, at read time — doing it server-side would fold upload latency into the
 * value.
 *
 * <p>Format established by reading Gjum/SnitchMod (GPL-3.0). This is a
 * reimplementation, not a port: the regexes describe EdenMC's output, and
 * Yeedar is MIT and on Yarn mappings where SnitchMod is on Mojang.
 */
public class JalistEntry {

    /** Snitch lifetimes by block type, used only for display context. */
    public static final long JUKEBOX_LIFETIME_MS = 42L * 24 * 3600 * 1000;
    public static final long NOTEBLOCK_LIFETIME_MS = 28L * 24 * 3600 * 1000;

    private static final Pattern LOCATION =
            Pattern.compile("^Location: (?:([A-Za-z][^ ]*),? )?(-?\\d+),? (-?\\d+),? (-?\\d+)");
    private static final Pattern GROUP = Pattern.compile("^Group: (\\S+)");
    private static final Pattern LIFETIME = Pattern.compile(
            "^Will (cull|go dormant) in (?:(\\d+) ?h(?:our)?s? ?)?"
            + "(?:(\\d+) ?m(?:in)?(?:ute)?s? ?)?(?:(\\d+) ?s(?:ec)?(?:ond)?s?)?\\s*");

    public final String world;
    public final int x, y, z;
    public final String group;
    public final String name;
    public final String type;        // "noteblock" | "jukebox"
    /** Absolute epoch millis, or 0 when this reading did not state it. */
    public final long dormantTs;
    public final long cullTs;

    private JalistEntry(String world, int x, int y, int z, String group, String name,
                        String type, long dormantTs, long cullTs) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.group = group;
        this.name = name;
        this.type = type;
        this.dormantTs = dormantTs;
        this.cullTs = cullTs;
    }

    /** Position key — snitches get renamed, so the name is never the identity. */
    public String key() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /**
     * Parse one GUI slot, or return null if it is not a snitch entry (the
     * pagination arrow and filler panes land here too).
     */
    public static JalistEntry fromStack(ItemStack stack, long now) {
        if (stack == null || stack.isEmpty()) return null;

        String type;
        if (stack.isOf(Items.NOTE_BLOCK)) {
            type = "noteblock";
        } else if (stack.isOf(Items.JUKEBOX)) {
            type = "jukebox";
        } else {
            return null;
        }

        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        var loreComponent = stack.get(DataComponentTypes.LORE);
        if (customName == null || loreComponent == null) return null;

        List<Text> lore = loreComponent.lines();
        if (lore.size() < 3) return null;

        Matcher loc = LOCATION.matcher(strip(lore.get(0)));
        if (!loc.find()) return null;
        Matcher grp = GROUP.matcher(strip(lore.get(1)));
        if (!grp.find()) return null;
        Matcher life = LIFETIME.matcher(strip(lore.get(2)));
        if (!life.find()) return null;

        String world = loc.group(1) == null ? "overworld" : loc.group(1).toLowerCase();
        int x = Integer.parseInt(loc.group(2));
        int y = Integer.parseInt(loc.group(3));
        int z = Integer.parseInt(loc.group(4));

        long ms = 1000L * (3600L * num(life.group(2)) + 60L * num(life.group(3)) + num(life.group(4)));
        long dormantTs = 0, cullTs = 0;
        if ("go dormant".equals(life.group(1))) {
            dormantTs = now + ms;
        } else {
            cullTs = now + ms;
        }

        return new JalistEntry(world, x, y, z, grp.group(1).toLowerCase(),
                customName.getString(), type, dormantTs, cullTs);
    }

    private static long num(String s) {
        return s == null ? 0L : Long.parseLong(s);
    }

    private static String strip(Text text) {
        return text.getString().replaceAll("§.", "").trim();
    }
}
