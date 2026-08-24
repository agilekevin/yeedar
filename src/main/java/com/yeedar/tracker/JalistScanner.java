package com.yeedar.tracker;

import com.yeedar.api.YeetVisClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    private JalistPager pager;
    /** Namelayers still to scan. Empty with currentGroup null means one
     *  unfiltered pass over everything the player can see. */
    private final Deque<String> queue = new ArrayDeque<>();
    private String currentGroup = null;
    private final List<String> done = new ArrayList<>();
    private int groupStartCount = 0;
    private int totalPages = 0;
    /** Non-zero while waiting for in-flight uploads to finish before
     *  summarising. Uploads retry for up to ~14s, so allow for that. */
    private static final int UPLOAD_SETTLE_TICKS = 400;   // 20s
    private int settleTicks = -1;
    private int settleTarget = 0;

    /** Positions seen this scan, so a repeat across pages is never re-sent. */
    private final Set<String> seenKeys = new HashSet<>();
    /** Read but not yet uploaded; flushed in batches so a long scan makes
     *  durable progress instead of risking everything on one final request. */
    private final List<JalistEntry> pending = new ArrayList<>();
    /** Everything read this scan, kept for the closing group breakdown —
     *  `pending` is emptied by each flush. */
    private final List<JalistEntry> allEntries = new ArrayList<>();

    public static JalistScanner getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public void beginScan() {
        beginScan(List.of());
    }

    /**
     * Scan the given namelayer groups in turn, or everything when the list is
     * empty.
     *
     * <p>Per-group is the more reliable route for a large network: JukeAlert
     * filters server-side, so each pass has far fewer pages to work through.
     */
    public void beginScan(List<String> groups) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (active) {
            feedback("§eScan already running.");
            return;
        }
        if (mc.player == null || mc.player.networkHandler == null) {
            feedback("§cNot connected to a server.");
            return;
        }
        // Check this before scanning, not after. A 150-page scan takes minutes,
        // and uploading used to fail silently when unconfigured — a user could
        // watch the whole thing succeed and lose every snitch of it.
        String why = YeetVisClient.unconfiguredReason();
        if (why != null) {
            feedback("§cCannot upload — " + why);
            feedback("§7Nothing was scanned. Fix that first, then run this again.");
            return;
        }
        active = true;
        settleTicks = -1;
        settleTarget = 0;
        YeetVisClient.resetUploadCounters();
        seenKeys.clear();
        pending.clear();
        allEntries.clear();
        queue.clear();
        done.clear();
        totalPages = 0;
        queue.addAll(groups);

        if (groups.isEmpty()) {
            feedback("§7Scanning every snitch you can see...");
        } else {
            feedback("§7Scanning " + groups.size() + " namelayer"
                    + (groups.size() == 1 ? "" : "s") + ": §f" + String.join("§7, §f", groups));
        }
        startNextGroup(mc);
    }

    /** Kick off the next queued namelayer, or the single unfiltered pass. */
    private void startNextGroup(MinecraftClient mc) {
        currentGroup = queue.poll();
        armed = true;
        armedTicks = 0;
        groupStartCount = seenKeys.size();
        pager = new JalistPager(GAP_TICKS, WAIT_TICKS, MAX_RETRIES,
                REOPEN_GRACE_TICKS, MAX_PAGES);
        if (currentGroup != null) {
            feedback("§7→ " + currentGroup + "...");
        }
        mc.player.networkHandler.sendChatCommand(
                currentGroup == null ? "jalist" : "jalist " + currentGroup);
    }

    /** Called every client tick; a cheap no-op unless a scan is running. */
    public void tick(MinecraftClient mc) {
        if (settleTicks >= 0) {
            // Reading is done; waiting on the last uploads to confirm.
            reportWhenUploadsSettle(settleTarget, ++settleTicks);
            if (settleTicks >= UPLOAD_SETTLE_TICKS) settleTicks = -1;
            return;
        }
        if (!active) return;

        if (armed) {
            // Waiting for the window the command opens.
            if (isJalistOpen(mc)) {
                armed = false;
            } else if (++armedTicks > ARM_TIMEOUT_TICKS) {
                armed = false;
                feedback(currentGroup == null
                        ? "§cJukeAlert never opened a window — are you on EdenMC?"
                        : "§e" + currentGroup + " — no window opened (no access to that group?)");
                endGroup(MinecraftClient.getInstance());
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
            case DONE -> {
                if (pager.wrappedOnto() > 0) {
                    System.out.println("[Yeedar] page " + (pager.pagesRead() + 1)
                            + " matched page " + pager.wrappedOnto()
                            + " — list wrapped, stopping");
                }
                finish(null);
            }
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
                    allEntries.add(entry);
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
        if (message != null) feedback(message);
        endGroup(MinecraftClient.getInstance());
    }

    /**
     * Wrap up the current namelayer, then move to the next or end the scan.
     *
     * <p>Each group is reported as it completes rather than only at the end: a
     * multi-group scan takes a while, and knowing which namelayers are already
     * safely uploaded matters if it stops early.
     */
    private void endGroup(MinecraftClient mc) {
        armed = false;
        int found = seenKeys.size() - groupStartCount;
        int pages = pager == null ? 0 : pager.pagesRead();
        totalPages += pages;

        if (currentGroup != null) {
            done.add(currentGroup);
            feedback(String.format("§a✔ %s — %d snitch%s across %d page%s",
                    currentGroup, found, found == 1 ? "" : "es", pages, pages == 1 ? "" : "s"));
        }
        // Upload as each group finishes so a scan that stops early still lands
        // everything it has already read.
        flush();

        if (!queue.isEmpty()) {
            startNextGroup(mc);
            return;
        }

        active = false;
        if (seenKeys.isEmpty()) {
            feedback("§eNo snitches found — check the namelayer names.");
            return;
        }
        if (done.isEmpty()) {
            feedback(String.format("§7Read %d snitches across %d page%s. Finishing upload...",
                    seenKeys.size(), totalPages, totalPages == 1 ? "" : "s"));
        } else {
            feedback(String.format("§7Read %d snitches from §f%s§7. Finishing upload...",
                    seenKeys.size(), String.join("§7, §f", done)));
        }
        logGroupBreakdown();
        reportWhenUploadsSettle(seenKeys.size(), 0);
    }

    /**
     * Summarise once the uploads have settled.
     *
     * <p>Uploads are asynchronous and retried, so the count read is not the
     * count stored. Reporting "Done — 6713" the moment reading finished was
     * how 2,700 lost snitches went unnoticed: the scan claimed success for
     * batches that had failed.
     */
    private void reportWhenUploadsSettle(int read, int waited) {
        int stored = YeetVisClient.uploadedCount();
        int lost = YeetVisClient.failedCount();
        if (stored + lost >= read || waited >= UPLOAD_SETTLE_TICKS) {
            if (lost == 0 && stored >= read) {
                feedback(String.format("§aDone — %d snitches uploaded.", stored));
            } else if (lost > 0) {
                feedback(String.format("§eDone — %d of %d uploaded, §c%d failed§e. Re-run to retry them.",
                        stored, read, lost));
            } else if (stored == 0) {
                // Nothing acknowledged at all: far more likely to be a dead
                // upload path than 6,000 slow requests.
                feedback(String.format("§cDone — none of %d snitches reached YeetVis.", read));
                feedback("§7Check §f/yeedar status§7, then re-run.");
            } else {
                feedback(String.format("§eDone — %d of %d confirmed; the rest may still be in flight.",
                        stored, read));
            }
            settleTicks = -1;
            return;
        }
        settleTicks = waited;
        settleTarget = read;
    }

    /**
     * Log which namelayer groups the scan covered. Dashboard visibility is
     * inherited from where a group's reports already go, so a group name
     * YeetVis has never seen explains an empty map faster than guessing does.
     */
    private void logGroupBreakdown() {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (JalistEntry e : allEntries) {
            counts.merge(e.group == null ? "(none)" : e.group, 1, Integer::sum);
        }
        if (counts.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[Yeedar] groups scanned:");
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

    /**
     * Identity for a page's contents, over the snitch slots only so a changing
     * control row cannot look like a new page.
     *
     * <p>Keyed on each item's location lore line rather than its display name.
     * Names are not unique — duplicates are common — so a name-based
     * fingerprint makes two genuinely different pages look identical, which
     * reads as the list wrapping and ends the scan early. That is what stopped
     * every scan at page 7.
     *
     * <p>Uses the raw lore line rather than a parsed entry: this runs on every
     * client tick, and regex-parsing 45 items twenty times a second to answer
     * "has the page changed" is a lot of work for a string comparison.
     */
    private static String fingerprint(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            if (!(s.isOf(Items.NOTE_BLOCK) || s.isOf(Items.JUKEBOX))) continue;
            var lore = s.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                sb.append(lore.lines().get(0).getString());   // "Location: world x y z"
            } else {
                sb.append(s.getName().getString());
            }
            sb.append('|');
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
