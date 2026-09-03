package com.yeedar;

import com.yeedar.command.YeedarCommands;
import com.yeedar.config.YeedarConfig;
import com.yeedar.terrain.TerrainCapture;
import com.yeedar.tracker.FriendlyTracker;
import com.yeedar.tracker.JalistScanner;
import com.yeedar.tracker.NamelayerListener;
import com.yeedar.tracker.PlayerTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

public class YeedarClient implements ClientModInitializer {

    private int friendlyRefreshCounter = 0;
    private static final int FRIENDLY_REFRESH_INTERVAL = 600; // ticks = 30 seconds

    @Override
    public void onInitializeClient() {
        YeedarConfig.load();
        YeedarCommands.register();

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
        });

        // Watch outgoing commands for /nllm (command string has no leading slash)
        ClientSendMessageEvents.COMMAND.register((command) -> {
            NamelayerListener.getInstance().onOutgoingChat("/" + command);
        });

        // Watch incoming chat for namelayer member lists, and for JukeAlert
        // refusing a group mid-scan — that refusal is the fastest way to know
        // a namelayer is unreadable, and ignoring it costs the scanner its
        // full arm timeout per group to learn what it was told at once.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                String text = message.getString();
                NamelayerListener.getInstance().onIncomingChat(text);
                JalistScanner.getInstance().onIncomingChat(text);
            }
        });

        // Initial friendly list fetch
        FriendlyTracker.getInstance().refresh();

        System.out.println("[Yeedar] Initialized - player tracking mod for EdenMc");
    }
}
