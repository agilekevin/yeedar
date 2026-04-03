package com.yeedar.tracker;

import com.yeedar.config.YeedarConfig;
import com.yeedar.api.YeetVisClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();
    private static final int CHECK_INTERVAL = 20; // ticks (1 second)

    private final Set<String> trackedPlayers = new HashSet<>();
    private final Map<String, double[]> lastKnownPositions = new HashMap<>();
    private int tickCounter = 0;
    private Object lastWorld = null;

    public static PlayerTracker getInstance() {
        return INSTANCE;
    }

    public void tick(MinecraftClient client) {
        tickCounter++;

        if (client.world == null || client.player == null) {
            if (!trackedPlayers.isEmpty()) {
                trackedPlayers.clear();
                lastKnownPositions.clear();
                lastWorld = null;
            }
            return;
        }

        // Clear state on world change (dimension switch, reconnect)
        if (lastWorld != client.world) {
            trackedPlayers.clear();
            lastKnownPositions.clear();
            lastWorld = client.world;
        }

        if (tickCounter % CHECK_INTERVAL != 0) return;
        if (!YeedarConfig.getInstance().isTrackingEnabled()) return;

        double range = YeedarConfig.getInstance().getDetectionRange();
        double rangeSq = range * range;

        Set<String> currentPlayers = new HashSet<>();
        // Store observer's position (not the detected player's)
        double[] myPos = new double[]{client.player.getX(), client.player.getY(), client.player.getZ()};

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            if (client.player.squaredDistanceTo(player) <= rangeSq) {
                String name = player.getName().getString();
                currentPlayers.add(name);
            }
        }

        // Players who just entered range
        for (String name : currentPlayers) {
            if (!trackedPlayers.contains(name)) {
                boolean friendly = FriendlyTracker.getInstance().isFriendly(name);
                YeetVisClient.sendPlayerEvent(name, myPos[0], myPos[1], myPos[2], true, friendly);
            }
        }

        // Players who just left range
        for (String name : trackedPlayers) {
            if (!currentPlayers.contains(name)) {
                double[] pos = lastKnownPositions.getOrDefault(name, myPos);
                boolean friendly = FriendlyTracker.getInstance().isFriendly(name);
                YeetVisClient.sendPlayerEvent(name, pos[0], pos[1], pos[2], false, friendly);
            }
        }

        trackedPlayers.clear();
        trackedPlayers.addAll(currentPlayers);
        lastKnownPositions.clear();
        for (String name : currentPlayers) {
            lastKnownPositions.put(name, myPos);
        }
    }

    public Set<String> getTrackedPlayers() {
        return Set.copyOf(trackedPlayers);
    }

    public Map<String, double[]> getLastKnownPositions() {
        return Map.copyOf(lastKnownPositions);
    }
}
