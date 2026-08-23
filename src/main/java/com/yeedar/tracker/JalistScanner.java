package com.yeedar.tracker;

import com.yeedar.api.YeetVisClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** Stop rather than loop forever if the arrow never goes away. */
    private static final int MAX_PAGES = 200;

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
    // Keyed by position so a snitch appearing on two pages is not double-counted.
    private final Map<String, JalistEntry> found = new LinkedHashMap<>();

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
        found.clear();
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
                    finish("§eScan ended — next page never arrived.");
                }
                return;   // same page still showing; keep waiting
            }
            awaitingPage = false;
            waitTicks = 0;
        }

        readPage(stacks);
        lastFingerprint = fingerprint;
        pages++;

        if (pages >= MAX_PAGES) {
            finish("§eScan stopped at the page limit.");
            return;
        }
        if (!hasNextPage(stacks)) {
            finish(null);
            return;
        }
        clickNextPage(mc, stacks);
    }

    private void readPage(List<ItemStack> stacks) {
        long now = System.currentTimeMillis();
        for (ItemStack stack : stacks) {
            JalistEntry entry = JalistEntry.fromStack(stack, now);
            if (entry != null) {
                // Later pages win: a re-read is at worst equally fresh.
                found.put(entry.key(), entry);
            }
        }
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

    private void finish(String earlyMessage) {
        active = false;
        awaitingPage = false;
        List<JalistEntry> entries = new ArrayList<>(found.values());
        found.clear();

        if (earlyMessage != null) {
            feedback(earlyMessage);
        }
        if (entries.isEmpty()) {
            feedback("§eNo snitches found — is this actually the jalist window?");
            return;
        }
        feedback(String.format("§aRead %d snitches across %d page%s. Uploading...",
                entries.size(), pages, pages == 1 ? "" : "s"));
        YeetVisClient.uploadJalist(entries);
    }

    private static boolean isJalistOpen(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return false;
        String title = screen.getTitle().getString().toLowerCase();
        return title.contains("jukealert") || title.contains("snitch");
    }

    private static boolean hasNextPage(List<ItemStack> stacks) {
        if (stacks.size() <= NEXT_PAGE_SLOT) return false;
        ItemStack arrow = stacks.get(NEXT_PAGE_SLOT);
        if (arrow.isEmpty()) return false;
        return arrow.isOf(Items.ARROW)
                || arrow.getName().getString().toLowerCase().contains("next");
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
