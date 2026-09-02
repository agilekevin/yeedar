package com.yeedar;

import com.yeedar.command.YeedarCommands;
import com.yeedar.config.YeedarConfig;
import com.yeedar.terrain.TerrainCapture;
import com.yeedar.tracker.FriendlyTracker;
import com.yeedar.tracker.JalistScanner;
import com.yeedar.tracker.NamelayerListener;
import com.yeedar.tracker.PlayerTracker;
import com.yeedar.update.ModVersion;
import com.yeedar.update.UpdateChecker;
import com.yeedar.update.UpdateNotifier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

import java.net.URI;

public class YeedarClient implements ClientModInitializer {

    private int friendlyRefreshCounter = 0;
    private static final int FRIENDLY_REFRESH_INTERVAL = 600; // ticks = 30 seconds

    /** Ticks left before the update notice is printed, or -1 for "nothing
     *  pending". Sending straight from the join event races the server's own
     *  join spam and the notice scrolls away unread, so it waits a moment. */
    private int updateNoticeCountdown = -1;
    private static final int UPDATE_NOTICE_DELAY = 60; // ticks = 3 seconds

    @Override
    public void onInitializeClient() {
        YeedarConfig.load();
        YeedarCommands.register();

        // Ask once per launch whether a newer jar exists. Yeedar cannot update
        // itself, so the most it can do is say so. Silent on every failure.
        if (YeedarConfig.getInstance().isUpdateCheckEnabled()) {
            UpdateChecker.check();
        }

        // Player tracking tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PlayerTracker.getInstance().tick(client);
            // No-op unless a /jalist scan is running.
            JalistScanner.getInstance().tick(client);
            // No-op unless /yeedar mapping is on.
            TerrainCapture.getInstance().tick(client);

            // Periodically refresh friendly list
            friendlyRefreshCounter++;
            if (friendlyRefreshCounter >= FRIENDLY_REFRESH_INTERVAL) {
                friendlyRefreshCounter = 0;
                FriendlyTracker.getInstance().refresh();
            }

            if (updateNoticeCountdown > 0) {
                updateNoticeCountdown--;
                if (updateNoticeCountdown == 0) {
                    updateNoticeCountdown = -1;
                    sendUpdateNotice(client);
                }
            }
        });

        // Arm the update notice on join. The check is async and a fast join can
        // beat it; that join is simply quiet and the next one picks it up.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            updateNoticeCountdown = UPDATE_NOTICE_DELAY;
        });

        // Watch outgoing commands for /nllm (command string has no leading slash)
        ClientSendMessageEvents.COMMAND.register((command) -> {
            NamelayerListener.getInstance().onOutgoingChat("/" + command);
        });

        // Watch incoming chat for namelayer member lists
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                NamelayerListener.getInstance().onIncomingChat(message.getString());
            }
        });

        // Initial friendly list fetch
        FriendlyTracker.getInstance().refresh();

        System.out.println("[Yeedar] Initialized - player tracking mod for EdenMc");
    }

    /** Print the "newer version exists" notice, if one is owed right now. */
    private void sendUpdateNotice(net.minecraft.client.MinecraftClient client) {
        if (client == null || client.player == null) return;

        YeedarConfig config = YeedarConfig.getInstance();
        UpdateChecker.Release latest = UpdateChecker.getLatest();
        String current = ModVersion.current();

        if (!UpdateNotifier.shouldNotify(current, latest,
                config.getLastNotifiedVersion(), config.isUpdateCheckEnabled())) {
            return;
        }

        // Recorded before sending, so a failure to render cannot turn this into
        // a message that repeats every join for ever.
        config.setLastNotifiedVersion(latest.version());
        config.save();

        Text message = Text.literal(
                        "§6[Yeedar] §fA newer version is available: §a"
                                + latest.version() + " §7(you have " + current + ")\n"
                                + "§7Yeedar cannot update itself — ")
                .append(Text.literal("§9§ndownload it here")
                        .styled(s -> s.withClickEvent(
                                new ClickEvent.OpenUrl(URI.create(latest.url())))));

        client.player.sendMessage(message, false);
    }
}
