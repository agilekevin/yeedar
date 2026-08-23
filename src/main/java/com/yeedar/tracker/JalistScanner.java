package com.yeedar.tracker;

import com.yeedar.api.YeetVisClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Drives a {@code /jalist} scan: runs the command, reads each page out of the
 * container the server sends, and uploads the snitches it finds.
 *
 * <p>All the paging decisions — when to click, when to retry, when the list has
 * ended — live in {@link JalistPager}, which has no Minecraft types in it and
 * is unit tested. This class is the part that has to talk to the game, and is
 * deliberately kept thin: the bugs that cost the most were in the state
 * machine, not in the I/O.
 *
 * <p>Reading a page needs no interaction at all. Item names and lore are
 * already in the container the server sent, so the hover tooltip is only a
 * client-side rendering of data that can be read directly. Only turning the
 * page requires a click.
 */
public class JalistScanner {

    private static final JalistScanner INSTANCE = new JalistScanner();

    private static final int NEXT_PAGE_SLOT = 53;   // bottom-right of a 54-slot container
    private static final int ARM_TIMEOUT_TICKS = 200;
    private static final int BATCH_SIZE = 400;

    // Pager tuning. Measured page latency is around 300ms; the wait is well
    // clear of that, and pacing turned out not to affect whether clicks land.
    private static final int GAP_TICKS = 14;         // ~700ms between pages
    private static final int WAIT_TICKS = 30;        // ~1.5s before re-clicking
    private static final int MAX_RETRIES = 6;
    private static final int REOPEN_GRACE_TICKS = 200;   // 10s
    private static final int MAX_PAGES = 200;

    private boolean active = false;
    private boolean armed = false;
    private int armedTicks = 0;
    private String scanGroup = null;
    private JalistPager pager;

    /** Positions seen this scan, so a repeat across pages is never re-sent. */
    private final Set<String> seenKeys = new HashSet<>();
    /** Read but not yet uploaded; flushed in batches so a long scan makes
     *  durable progress instead of risking everything on one final request. */
    private final List<JalistEntry> pending = new ArrayList<>();

    public static JalistScanner getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public void beginScan() {
        beginScan(null);
    }

    /**
     * Scan one namelayer group, or everything when group is null.
     *
     * <p>Per-group is the more reliable route for a large network: /jalist
     * &lt;group&gt; filters server-side, so each run has far fewer pages.
     */
    public void beginScan(String group) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (active) {
            feedback("§eScan already running.");
            return;
        }
        if (mc.player == null || mc.player.networkHandler == null) {
            feedback("§cNot connected to a server.");
            return;
        }
        active = true;
        armed = true;
        armedTicks = 0;
        scanGroup = group;
        pager = new JalistPager(GAP_TICKS, WAIT_TICKS, MAX_RETRIES,
                REOPEN_GRACE_TICKS, MAX_PAGES);
        seenKeys.clear();
        pending.clear();

        feedback(group == null ? "§7Running /jalist..." : "§7Running /jalist " + group + "...");
        mc.player.networkHandler.sendChatCommand(group == null ? "jalist" : "jalist " + group);
    }

    /** Called every client tick; a cheap no-op unless a scan is running. */
    public void tick(MinecraftClient mc) {
        if (!active) return;

        if (armed) {
            // Waiting for the window the command opens.
            if (isJalistOpen(mc)) {
                armed = false;
            } else if (++armedTicks > ARM_TIMEOUT_TICKS) {
                active = false;
                armed = false;
                feedback("§cJukeAlert never opened a window — are you on EdenMC?");
            }
            return;
        }

        List<ItemStack> stacks = currentStacks(mc);
        JalistPager.View view = stacks == null
                ? JalistPager.View.closed()
                : new JalistPager.View(fingerprint(stacks), countSnitchItems(stacks));

        switch (pager.tick(view)) {
            case READ -> {
                readPage(stacks);
                if (pending.size() >= BATCH_SIZE) flush();
                if (pager.pagesRead() % 5 == 0) {
                    feedback("§7" + pager.pagesRead() + " pages, "
                            + seenKeys.size() + " snitches...");
                }
            }
            case CLICK -> clickNextPage(mc);
            case DONE -> finish(null);
            case GAVE_UP -> finish("§ePage " + (pager.pagesRead() + 1)
                    + " never arrived — stopping with what was read.");
            case CLOSED -> finish("§eScan ended — the jalist window closed.");
            case IDLE -> { }
        }
    }

    private static List<ItemStack> currentStacks(MinecraftClient mc) {
        if (!isJalistOpen(mc)) return null;
        return ((HandledScreen<?>) mc.currentScreen).getScreenHandler().getStacks();
    }

    private void clickNextPage(MinecraftClient mc) {
        if (mc.interactionManager == null || mc.player == null
                || !(mc.currentScreen instanceof HandledScreen<?> screen)) {
            return;   // the pager will retry
        }
        mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, NEXT_PAGE_SLOT, 0,
                SlotActionType.PICKUP, mc.player);
    }

    private void readPage(List<ItemStack> stacks) {
        long now = System.currentTimeMillis();
        int candidates = 0, parsed = 0, added = 0;
        for (ItemStack stack : stacks) {
            if (stack.isOf(Items.NOTE_BLOCK) || stack.isOf(Items.JUKEBOX)) candidates++;
            JalistEntry entry = JalistEntry.fromStack(stack, now);
            if (entry != null) {
                parsed++;
                if (seenKeys.add(entry.key())) {
                    pending.add(entry);
                    added++;
                }
            }
        }
        System.out.println("[Yeedar] jalist page " + pager.pagesRead() + ": " + candidates
                + " snitch items, " + parsed + " parsed, " + added + " new (total "
                + seenKeys.size() + ")");
        if (candidates > parsed) describeUnparsed(stacks);
    }

    /** Dump the first slot that looked like a snitch but would not parse, so a
     *  format change shows itself instead of silently under-reading. */
    private void describeUnparsed(List<ItemStack> stacks) {
        long now = System.currentTimeMillis();
        for (ItemStack stack : stacks) {
            if (!(stack.isOf(Items.NOTE_BLOCK) || stack.isOf(Items.JUKEBOX))) continue;
            if (JalistEntry.fromStack(stack, now) != null) continue;
            System.out.println("[Yeedar]   unparsed: " + stack.getName().getString());
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore == null) {
                System.out.println("[Yeedar]   (no lore)");
            } else {
                int i = 0;
                for (Text line : lore.lines()) {
                    System.out.println("[Yeedar]   lore[" + (i++) + "] " + line.getString());
                }
            }
            return;
        }
    }

    /** Send everything read so far. Safe to call repeatedly: the server upserts
     *  by position and never deletes, so batches compose. */
    private void flush() {
        if (pending.isEmpty()) return;
        List<JalistEntry> batch = new ArrayList<>(pending);
        pending.clear();
        YeetVisClient.uploadJalist(batch);
    }

    private void finish(String message) {
        active = false;
        armed = false;
        if (message != null) feedback(message);
        if (seenKeys.isEmpty()) {
            feedback("§eNo snitches found — is this actually the jalist window?");
            return;
        }
        int pages = pager.pagesRead();
        feedback(String.format("§aRead %d snitches across %d page%s. Uploading...",
                seenKeys.size(), pages, pages == 1 ? "" : "s"));
        logGroupBreakdown();
        flush();
    }

    /**
     * Log which namelayer groups the scan covered. Dashboard visibility is
     * inherited from where a group's reports already go, so a group name
     * YeetVis has never seen explains an empty map faster than guessing does.
     */
    private void logGroupBreakdown() {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (JalistEntry e : pending) {
            counts.merge(e.group == null ? "(none)" : e.group, 1, Integer::sum);
        }
        if (counts.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[Yeedar] groups in final batch:");
        counts.forEach((g, n) -> sb.append(' ').append(g).append('=').append(n));
        System.out.println(sb);
    }

    private static boolean isJalistOpen(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return false;
        String title = screen.getTitle().getString().toLowerCase();
        return title.contains("jukealert") || title.contains("snitch");
    }

    private static int countSnitchItems(List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack st : stacks) {
            if (st.isOf(Items.NOTE_BLOCK) || st.isOf(Items.JUKEBOX)) n++;
        }
        return n;
    }

    /** Cheap identity for a page's contents, over the snitch slots only, so a
     *  changing control row cannot look like a new page. */
    private static String fingerprint(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            if (s.isOf(Items.NOTE_BLOCK) || s.isOf(Items.JUKEBOX)) {
                sb.append(s.getName().getString()).append('|');
            }
        }
        return sb.toString();
    }

    private static void feedback(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Yeedar] " + message), false);
        }
    }
}
