package com.yeedar.tracker;

import com.yeedar.api.YeetVisClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads every page of the JukeAlert {@code /jalist} GUI and uploads the result.
 *
 * <p>Pages are requested by number — {@code /jalist 1}, {@code /jalist 2}, … —
 * rather than by clicking the next-page arrow. Clicking was tried first and
 * failed consistently around page seven: the click stopped taking effect, the
 * window sat on the same page, and no amount of pacing changed it. 300ms, 700ms
 * and 4s gaps all stalled in the same place, which rules out rate limiting and
 * points at the synthetic click itself. Asking for a page by number has no such
 * failure mode — each request is an ordinary command, with no cursor state,
 * container revision, or predicted client-side inventory to get out of step.
 *
 * <p>Reading a page needs no interaction at all: item names and lore are
 * already in the container the server sent, so the hover tooltip is only a
 * client-side rendering of data that can be read directly.
 */
public class JalistScanner {

    private static final JalistScanner INSTANCE = new JalistScanner();

    /** Requesting a page closes and reopens the window, so tolerate the gap. */
    private static final int REOPEN_GRACE_TICKS = 200;      // 10s
    private static final int ARM_TIMEOUT_TICKS = 200;       // 10s
    /** Upload once this many unsent snitches have accumulated. */
    private static final int BATCH_SIZE = 400;
    private static final int MAX_PAGES = 200;

    /** Gap between page requests, and how long to wait for one to arrive. */
    private static final int GAP_MIN_TICKS = 10;            // 500ms
    private static final int GAP_MAX_TICKS = 60;            // 3s
    private static final int WAIT_BASE_TICKS = 20;          // 1s
    private static final int WAIT_MAX_TICKS = 300;          // 15s
    private static final double BACKOFF = 1.35;
    private static final double RECOVER = 0.9;
    private static final int CLEAN_PAGES_BEFORE_RECOVER = 3;
    private static final int MAX_RETRIES = 1;

    private boolean active = false;
    private boolean armed = false;
    private int armedTicks = 0;
    private int pageWanted = 1;
    private int pagesRead = 0;
    private int gapTicks = GAP_MIN_TICKS;
    private int gapCountdown = 0;
    private int waitBudget = WAIT_BASE_TICKS;
    private int waitTicks = 0;
    private int retries = 0;
    private int cleanStreak = 0;
    private int screenGoneTicks = 0;
    private boolean awaitingPage = false;
    private String lastFingerprint = null;

    /** Positions seen this scan, so a repeat across pages is never re-sent. */
    private final Set<String> seenKeys = new HashSet<>();
    /** Read but not yet uploaded; flushed in batches. */
    private final List<JalistEntry> pending = new ArrayList<>();
    /** Page contents already read — the end-of-list signal when one repeats. */
    private final Set<String> seenPages = new HashSet<>();

    public static JalistScanner getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    /** Entry point for {@code /yeedar jalist}. */
    public void beginScan() {
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
        pageWanted = 1;
        pagesRead = 0;
        gapTicks = GAP_MIN_TICKS;
        gapCountdown = 0;
        waitBudget = WAIT_BASE_TICKS;
        waitTicks = 0;
        retries = 0;
        cleanStreak = 0;
        screenGoneTicks = 0;
        awaitingPage = false;
        lastFingerprint = null;
        seenKeys.clear();
        pending.clear();
        seenPages.clear();

        feedback("§7Scanning /jalist...");
        requestPage(mc, 1);
    }

    /** Called every client tick; a cheap no-op unless a scan is running. */
    public void tick(MinecraftClient mc) {
        if (!active) return;

        if (armed) {
            // Waiting for the very first window to appear.
            if (isJalistOpen(mc)) {
                armed = false;
            } else if (++armedTicks > ARM_TIMEOUT_TICKS) {
                active = false;
                armed = false;
                feedback("§cJukeAlert never opened a window — are you on EdenMC?");
            }
            return;
        }

        if (gapCountdown > 0) {
            if (--gapCountdown == 0) requestPage(mc, pageWanted);
            return;
        }

        if (!isJalistOpen(mc)) {
            // Requesting a page reopens the window, so a missing screen is the
            // normal in-between state, not the player closing it.
            if (++screenGoneTicks > REOPEN_GRACE_TICKS) {
                finish("§eScan ended — jalist window did not reopen.");
            }
            return;
        }
        screenGoneTicks = 0;

        List<ItemStack> stacks = ((HandledScreen<?>) mc.currentScreen).getScreenHandler().getStacks();
        if (countSnitchItems(stacks) == 0) {
            // Either the window is still being populated, or we asked for a page
            // past the end. Both resolve by waiting.
            if (++waitTicks > waitBudget) backOffOrFinish(mc);
            return;
        }

        String fingerprint = fingerprint(stacks);
        if (awaitingPage && fingerprint.equals(lastFingerprint)) {
            if (++waitTicks > waitBudget) backOffOrFinish(mc);
            return;   // still showing the previous page
        }

        // A page we have already read means the server clamped our request to
        // the last page — there is nothing further to fetch.
        if (!seenPages.add(fingerprint)) {
            System.out.println("[Yeedar] page " + pageWanted
                    + " repeats an earlier page — end of list");
            finish(null);
            return;
        }

        awaitingPage = false;
        waitTicks = 0;
        retries = 0;
        waitBudget = WAIT_BASE_TICKS;
        readPage(stacks);
        lastFingerprint = fingerprint;
        pagesRead++;

        if (++cleanStreak >= CLEAN_PAGES_BEFORE_RECOVER && gapTicks > GAP_MIN_TICKS) {
            cleanStreak = 0;
            gapTicks = Math.max(GAP_MIN_TICKS, (int) (gapTicks * RECOVER));
        }
        if (pending.size() >= BATCH_SIZE) flush();
        if (pagesRead % 5 == 0) {
            feedback("§7" + pagesRead + " pages, " + seenKeys.size() + " snitches...");
        }
        if (pagesRead >= MAX_PAGES) {
            finish("§eScan stopped at the " + MAX_PAGES + "-page limit.");
            return;
        }

        pageWanted++;
        gapCountdown = gapTicks;
    }

    /** Ask the server for a page by number. */
    private void requestPage(MinecraftClient mc, int page) {
        if (mc.player == null || mc.player.networkHandler == null) {
            finish("§cScan aborted — disconnected.");
            return;
        }
        awaitingPage = true;
        waitTicks = 0;
        screenGoneTicks = 0;
        mc.player.networkHandler.sendChatCommand("jalist " + page);
    }

    /** Every stall is evidence the pace is too fast; re-ask only as a last resort. */
    private void backOffOrFinish(MinecraftClient mc) {
        gapTicks = Math.min(GAP_MAX_TICKS, (int) Math.ceil(gapTicks * BACKOFF));
        cleanStreak = 0;

        if (waitBudget < WAIT_MAX_TICKS) {
            waitBudget = Math.min(WAIT_MAX_TICKS, (int) Math.ceil(waitBudget * BACKOFF));
            waitTicks = 0;
            System.out.println("[Yeedar] page " + pageWanted + " slow; waiting up to "
                    + (waitBudget / 20) + "s, gap now " + (gapTicks * 50) + "ms");
            feedback("§7Page " + pageWanted + " is slow — still going ("
                    + seenKeys.size() + " snitches so far)...");
            return;
        }
        if (retries < MAX_RETRIES) {
            retries++;
            System.out.println("[Yeedar] re-requesting page " + pageWanted);
            requestPage(mc, pageWanted);
            return;
        }
        System.out.println("[Yeedar] page " + pageWanted + " never arrived — end of list");
        finish(null);
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
        System.out.println("[Yeedar] jalist page " + pageWanted + ": " + candidates
                + " snitch items, " + parsed + " parsed, " + added + " new (total "
                + seenKeys.size() + ")");
        if (candidates > parsed) describeUnparsed(stacks);
    }

    /** Dump the first slot that looked like a snitch but would not parse. */
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
        awaitingPage = false;
        gapCountdown = 0;

        if (message != null) feedback(message);
        if (seenKeys.isEmpty()) {
            feedback("§eNo snitches found — is this actually the jalist window?");
            return;
        }
        feedback(String.format("§aRead %d snitches across %d page%s. Uploading...",
                seenKeys.size(), pagesRead, pagesRead == 1 ? "" : "s"));
        logGroupBreakdown();
        flush();
    }

    /**
     * Log which namelayer groups the scan covered. Dashboard visibility is
     * inherited from where a group's reports already go, so a group name
     * YeetVis has never seen explains an empty map faster than guessing does.
     */
    private void logGroupBreakdown() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
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

    /** Cheap identity for a page's contents, over the snitch slots only. */
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
