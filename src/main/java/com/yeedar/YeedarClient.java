package com.yeedar;

import com.yeedar.command.YeedarCommands;
import com.yeedar.config.YeedarConfig;
import com.yeedar.tracker.FriendlyTracker;
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
}
