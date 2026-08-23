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

/**
 * Reads every page of the JukeAlert {@code /jalist} GUI and uploads the result.
 *
 * <p>Yeedar has no mixins — it is built entirely on Fabric API events — so this
 * deliberately avoids SnitchMod's approach of injecting into the packet
 * listener. Instead it reads {@code ScreenHandler.getStacks()}, which holds the
 * very same {@link ItemStack}s the container packet delivered. The tradeoff is
 * that it only works while the GUI is open, which is inherent to /jalist.
 *
 * <p>Pagination: the bottom-right slot holds a "next page" arrow. Clicking it
 * replaces the container contents in place, so pages are detected by the stack
 * fingerprint changing rather than by any event.
 */
public class JalistScanner {

    private static final JalistScanner INSTANCE = new JalistScanner();

    private static final int NEXT_PAGE_SLOT = 53;
    /**
     * How long to wait for a page before doing anything about it. Doubles on
     * each stall, up to WAIT_MAX.
     */
    private static final int WAIT_BASE_TICKS = 60;    // 3 seconds
    private static final int WAIT_MAX_TICKS = 480;    // 24 seconds
    private static final double WAIT_BACKOFF = 1.5;
    /**
     * How long the window may vanish mid-scan before we give up on it.
     * Paginated server GUIs often close the old inventory and open a new one
     * per page rather than updating in place, so a slow page shows up here as
     * a missing screen — not as the player closing it.
     */
    private static final int REOPEN_GRACE_TICKS = 200;   // 10 seconds
    /** Stop rather than loop forever if paging never terminates. */
    private static final int MAX_PAGES = 200;
    /** Upload once this many unsent snitches have accumulated. */
    private static final int BATCH_SIZE = 400;
    /**
     * Pause between page clicks. Clicking as fast as pages arrive got ~4 per
     * second and the server simply stopped sending new ones after seven, so it
     * is throttling us. Pace the clicks instead of hammering it.
     */
    /**
     * Gap between page clicks. The server allows a short burst — about seven
     * pages — then stalls, which is a token bucket with a slow refill rather
     * than a per-request limit. Recovering and then immediately clicking again
     * just re-exhausts it, so the gap widens permanently for the rest of a
     * scan each time we stall, converging on the sustained rate the server
     * will actually serve.
     */
    private static final int CLICK_DELAY_MIN = 14;     // ~700ms
    private static final int CLICK_DELAY_MAX = 80;     // ~4s
    /** Back off hard on a stall, recover gently once pages flow again (AIMD).
     *  Purely monotonic slowing meant one hiccup taxed every remaining page. */
    private static final double CLICK_BACKOFF = 1.35;
    private static final double CLICK_RECOVER = 0.9;
    private static final int CLEAN_PAGES_BEFORE_RECOVER = 3;
    /**
     * Re-click at most once before concluding the list ended. Retrying is a
     * gamble: if the original click did register and the server is merely
     * slow, the retry advances an extra page and silently skips its contents.
     */
    private static final int MAX_PAGE_RETRIES = 1;

    /**
     * How long after the player types /jalist we keep watching for the window
     * to appear. Long enough for a laggy server, short enough that an unrelated
     * container opened a minute later is never mistaken for the reply.
     */
    private static final int ARM_TIMEOUT_TICKS = 200;   // 10 seconds

    private boolean active = false;
    private boolean armed = false;
    private int armedTicks = 0;
    private boolean awaitingPage = false;
    private int waitTicks = 0;
    private int pageRetries = 0;
    private int clickDelay = 0;
    /** Both adapt during a scan; they never speed back up, so one hiccup does
     *  not have to be re-learned page after page. */
    private int clickDelayTicks = CLICK_DELAY_MIN;
    private int waitBudget = WAIT_BASE_TICKS;
    private int screenGoneTicks = 0;
    private int cleanStreak = 0;
    private int pages = 0;
    private String lastFingerprint = null;
    private int uploadedTotal = 0;
    /** Positions seen this scan, so a repeat across pages is never re-sent. */
    private final Set<String> seenKeys = new HashSet<>();
    /** Read but not yet uploaded. Flushed in batches so a long scan makes
     *  durable progress instead of risking everything on one final request. */
    private final List<JalistEntry> pending = new ArrayList<>();
    /** Page fingerprints already read — the reliable end-of-list signal. */
    private final Set<String> seenPages = new HashSet<>();

    public static JalistScanner getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Entry point for {@code /yeedar jalist}: runs the JukeAlert command
     * ourselves and scans the window it opens.
     *
     * <p>Yeedar used to watch for the player typing /jalist and take over the
     * resulting window. That was disorienting — a window you opened to read by
     * hand would start paging itself. Owning the whole interaction makes it
     * explicit: nothing moves unless you asked for a scan.
     */
    public void beginScan() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (active) {
            feedback("§eScan already running.");
            return;
        }
        if (isJalistOpen(mc)) {
            start();          // already looking at it; no need to reopen
            return;
        }
        if (mc.player == null || mc.player.networkHandler == null) {
            feedback("§cNot connected to a server.");
            return;
        }
        armed = true;
        armedTicks = 0;
        feedback("§7Running /jalist...");
        mc.player.networkHandler.sendChatCommand("jalist");
    }

    /** Scan the jalist window that is already open. */
    private boolean start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (active) {
            feedback("§eScan already running.");
            return false;
        }
        if (!isJalistOpen(mc)) {
            feedback("§cOpen /jalist first, then run this while the window is up.");
            return false;
        }
        active = true;
        armed = false;
        awaitingPage = false;
        waitTicks = 0;
        pageRetries = 0;
        clickDelay = 0;
        clickDelayTicks = CLICK_DELAY_MIN;
        waitBudget = WAIT_BASE_TICKS;
        screenGoneTicks = 0;
        cleanStreak = 0;
        pages = 0;
        lastFingerprint = null;
        uploadedTotal = 0;
        seenKeys.clear();
        pending.clear();
        seenPages.clear();
        feedback("§7Scanning /jalist...");
        return true;
    }

    /** Called every client tick; cheap no-op unless a scan is running. */
    public void tick(MinecraftClient mc) {
        if (armed) {
            if (isJalistOpen(mc)) {
                armed = false;
                start();
            } else if (++armedTicks > ARM_TIMEOUT_TICKS) {
                armed = false;   // the window never came; stop watching
                feedback("§eJukeAlert never opened a window — are you on EdenMC?");
            }
        }
        if (!active) return;

        if (!isJalistOpen(mc)) {
            // Do not treat this as the end straight away. The server reopens
            // the inventory to turn a page, so a slow page looks exactly like
            // a closed window for a moment — bailing here ended scans at the
            // first page the server was slow to send.
            if (++screenGoneTicks == 1) {
                System.out.println("[Yeedar] jalist window vanished after page "
                        + pages + "; waiting for it to reopen");
            }
            if (screenGoneTicks > REOPEN_GRACE_TICKS) {
                finish("§eScan ended — jalist closed and did not reopen.");
            }
            return;
        }
        if (screenGoneTicks > 0) {
            System.out.println("[Yeedar] jalist window reopened after "
                    + (screenGoneTicks * 50) + "ms");
            screenGoneTicks = 0;
            // A reopened window is a fresh container; the pending click is done.
            awaitingPage = false;
            waitTicks = 0;
        }

        List<ItemStack> stacks = ((HandledScreen<?>) mc.currentScreen).getScreenHandler().getStacks();

        // Clicking makes the client optimistically empty the container before
        // the server's real contents arrive. That predicted state is not a
        // page: counting it produced a phantom empty page, a second click per
        // real page (which is what the server was throttling), and an empty
        // fingerprint that later matched and ended the scan early.
        if (countSnitchItems(stacks) == 0) {
            if (active && pages > 0 && ++waitTicks > waitBudget) {
                retryOrFinish(mc, stacks);
            }
            return;
        }

        String fingerprint = fingerprint(stacks);

        if (clickDelay > 0) {
            if (--clickDelay == 0) clickNextPage(mc, stacks);
            return;
        }

        if (awaitingPage) {
            if (fingerprint.equals(lastFingerprint)) {
                if (++waitTicks > waitBudget) {
                    retryOrFinish(mc, stacks);
                }
                return;   // same page still showing; keep waiting
            }
            awaitingPage = false;
            waitTicks = 0;
            pageRetries = 0;
            waitBudget = WAIT_BASE_TICKS;
            // Pages are flowing again: ease the gap back down gradually rather
            // than paying for one stall for the rest of the scan.
            if (++cleanStreak >= CLEAN_PAGES_BEFORE_RECOVER
                    && clickDelayTicks > CLICK_DELAY_MIN) {
                cleanStreak = 0;
                clickDelayTicks = Math.max(CLICK_DELAY_MIN,
                        (int) (clickDelayTicks * CLICK_RECOVER));
                System.out.println("[Yeedar] pages flowing; gap eased to "
                        + (clickDelayTicks * 50) + "ms");
            }
        }

        // A page whose contents we have already read means paging wrapped,
        // which is the reliable end-of-list signal. The next-page arrow is
        // not: the client removes it from the slot the moment it is clicked,
        // so it is absent from most pages even mid-list.
        if (!seenPages.add(fingerprint)) {
            System.out.println("[Yeedar] page " + (pages + 1)
                    + " repeats a page already read — list wrapped, stopping");
            finish(null);
            return;
        }

        readPage(stacks);
        describeControls(stacks);
        if (pages > 0 && pages % 5 == 0) {
            feedback("§7" + pages + " pages, " + seenKeys.size() + " snitches...");
        }
        lastFingerprint = fingerprint;
        pages++;

        if (pending.size() >= BATCH_SIZE) flush();

        if (pages >= MAX_PAGES) {
            finish("§eScan stopped at the " + MAX_PAGES + "-page limit.");
            return;
        }
        clickDelay = clickDelayTicks;
    }

    /** A dropped click and the end of the list look identical, so re-click
     *  before believing the list is finished. */
    private void retryOrFinish(MinecraftClient mc, List<ItemStack> stacks) {
        // Every stall is evidence the pace is too fast, so slow the rest of
        // the scan whichever branch we take below.
        clickDelayTicks = Math.min(CLICK_DELAY_MAX,
                (int) Math.ceil(clickDelayTicks * CLICK_BACKOFF));
        cleanStreak = 0;

        if (waitBudget < WAIT_MAX_TICKS) {
            // Wait longer before touching anything. Re-clicking is the risky
            // move: if the first click did register and the server is merely
            // throttled, a second one advances an extra page and silently
            // skips its contents. Patience cannot cause that.
            waitBudget = Math.min(WAIT_MAX_TICKS, (int) Math.ceil(waitBudget * WAIT_BACKOFF));
            waitTicks = 0;
            System.out.println("[Yeedar] page " + (pages + 1) + " slow; waiting up to "
                    + (waitBudget / 20) + "s, page gap now " + (clickDelayTicks * 50) + "ms");
            feedback("§7Page " + (pages + 1) + " is slow — still going ("
                    + seenKeys.size() + " snitches so far)...");
            return;
        }

        if (pageRetries < MAX_PAGE_RETRIES) {
            pageRetries++;
            waitTicks = 0;
            System.out.println("[Yeedar] page " + (pages + 1)
                    + " timed out after " + (waitBudget / 20) + "s; re-clicking");
            clickNextPage(mc, stacks);
            return;
        }

        System.out.println("[Yeedar] page " + (pages + 1)
                + " never arrived — treating as end of list");
        finish(null);
    }

    private static int countSnitchItems(List<ItemStack> stacks) {
        int n = 0;
        for (ItemStack st : stacks) {
            if (st.isOf(Items.NOTE_BLOCK) || st.isOf(Items.JUKEBOX)) n++;
        }
        return n;
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
        // Per-page detail: "parsed < candidates" means the lore did not match,
        // "added < parsed" means the page repeated snitches we already had.
        System.out.println("[Yeedar] jalist page " + (pages + 1) + ": " + stacks.size()
                + " slots, " + candidates + " snitch items, " + parsed + " parsed, "
                + added + " new (total " + seenKeys.size() + ")");
        if (candidates > parsed) {
            describeUnparsed(stacks);
        }
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
                for (net.minecraft.text.Text line : lore.lines()) {
                    System.out.println("[Yeedar]   lore[" + (i++) + "] " + line.getString());
                }
            }
            return;
        }
    }

    /** What the control row actually holds, so a wrong next-page slot is visible. */
    private static void describeControls(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder("[Yeedar] jalist controls:");
        for (int i = 45; i < Math.min(54, stacks.size()); i++) {
            ItemStack st = stacks.get(i);
            if (st.isEmpty()) continue;
            sb.append(' ').append(i).append('=').append(st.getName().getString());
        }
        System.out.println(sb);
    }

    private void clickNextPage(MinecraftClient mc, List<ItemStack> stacks) {
        if (mc.interactionManager == null || mc.player == null) {
            finish("§cScan aborted — no interaction manager.");
            return;
        }
        int syncId = ((HandledScreen<?>) mc.currentScreen).getScreenHandler().syncId;
        mc.interactionManager.clickSlot(syncId, NEXT_PAGE_SLOT, 0, SlotActionType.PICKUP, mc.player);
        awaitingPage = true;
        waitTicks = 0;
    }

    /**
     * Log which namelayer groups the scan covered. Visibility on the dashboard
     * is inherited from where a group's reports already go, so a group name
     * YeetVis has never seen an event for explains an empty map far faster
     * than guessing does.
     */
    private void logGroupBreakdown() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (JalistEntry e : pending) {
            counts.merge(e.group == null ? "(none)" : e.group, 1, Integer::sum);
        }
        if (counts.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[Yeedar] groups in this batch:");
        counts.forEach((g, n) -> sb.append(' ').append(g).append('=').append(n));
        System.out.println(sb);
    }

    /** Send everything read so far. Safe to call repeatedly: the server upserts
     *  by position and never deletes, so batches compose. */
    private void flush() {
        if (pending.isEmpty()) return;
        List<JalistEntry> batch = new ArrayList<>(pending);
        pending.clear();
        uploadedTotal += batch.size();
        YeetVisClient.uploadJalist(batch);
    }

    private void finish(String earlyMessage) {
        active = false;
        awaitingPage = false;

        if (earlyMessage != null) {
            feedback(earlyMessage);
        }
        if (seenKeys.isEmpty()) {
            feedback("§eNo snitches found — is this actually the jalist window?");
            return;
        }
        feedback(String.format("§aRead %d snitches across %d page%s. Uploading...",
                seenKeys.size(), pages, pages == 1 ? "" : "s"));
        logGroupBreakdown();
        flush();
    }

    private static boolean isJalistOpen(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return false;
        String title = screen.getTitle().getString().toLowerCase();
        return title.contains("jukealert") || title.contains("snitch");
    }

    /**
     * Cheap identity for a page's contents. Only the snitch-bearing slots are
     * included so an unrelated cursor change can't look like a new page.
     */
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
