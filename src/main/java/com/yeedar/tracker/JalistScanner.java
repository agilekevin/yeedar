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
    /** Give the server time to send the new page before re-reading. */
    private static final int PAGE_TIMEOUT_TICKS = 60;
    /** Stop rather than loop forever if paging never terminates. */
    private static final int MAX_PAGES = 200;
    /** Upload once this many unsent snitches have accumulated. */
    private static final int BATCH_SIZE = 400;

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
     * Called when the player sends /jalist. The GUI has not opened yet, so this
     * only arms the scanner; {@link #tick} starts the scan once the window
     * actually appears.
     *
     * <p>Matching the command rather than merely noticing a JukeAlert window is
     * what keeps this from hijacking a window the player opened deliberately to
     * read by hand.
     */
    public void onJalistCommand() {
        if (active) return;
        armed = true;
        armedTicks = 0;
        // Say something now: the window takes a moment to arrive, and silence
        // here is indistinguishable from the detection not working at all.
        feedback("§7Detected /jalist — waiting for the window...");
    }

    /** Begin a scan against an already-open jalist GUI. */
    public boolean start() {
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
                feedback("§eNo jalist window appeared — run §f/yeedar jalist§e with it open.");
            }
        }
        if (!active) return;

        if (!isJalistOpen(mc)) {
            // The player closed the window (or the server did). Keep whatever
            // was read rather than throwing away a partial scan.
            finish("§eScan ended early — jalist closed.");
            return;
        }

        List<ItemStack> stacks = ((HandledScreen<?>) mc.currentScreen).getScreenHandler().getStacks();
        String fingerprint = fingerprint(stacks);

        if (awaitingPage) {
            if (fingerprint.equals(lastFingerprint)) {
                if (++waitTicks > PAGE_TIMEOUT_TICKS) {
                    // No new page arrived: this was the last one.
                    finish(null);
                }
                return;   // same page still showing; keep waiting
            }
            awaitingPage = false;
            waitTicks = 0;
        }

        // A fingerprint we have already read means paging wrapped around, which
        // is the reliable end-of-list signal. The next-page arrow is not: the
        // client removes it from the slot the moment we click it, so checking
        // whether it is still there stops the scan after a single page.
        if (!seenPages.add(fingerprint)) {
            finish(null);
            return;
        }

        readPage(stacks);
        describeControls(stacks);
        lastFingerprint = fingerprint;
        pages++;

        if (pending.size() >= BATCH_SIZE) flush();

        if (pages >= MAX_PAGES) {
            finish("§eScan stopped at the " + MAX_PAGES + "-page limit.");
            return;
        }
        clickNextPage(mc, stacks);
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
        System.out.printf("[Yeedar] jalist page %d: %d slots, %d snitch items, "
                        + "%d parsed, %d new (total %d)%n",
                pages + 1, stacks.size(), candidates, parsed, added, seenKeys.size());
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
