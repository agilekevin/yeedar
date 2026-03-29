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
        Map<String, double[]> currentPositions = new HashMap<>();

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            if (client.player.squaredDistanceTo(player) <= rangeSq) {
                String name = player.getName().getString();
                if (FriendlyTracker.getInstance().isFriendly(name)) continue;
                currentPlayers.add(name);
                currentPositions.put(name, new double[]{player.getX(), player.getY(), player.getZ()});
            }
        }

        // Players who just entered range
        for (String name : currentPlayers) {
            if (!trackedPlayers.contains(name)) {
                double[] pos = currentPositions.get(name);
                YeetVisClient.sendPlayerEvent(name, pos[0], pos[1], pos[2], true);
            }
        }

        // Players who just left range
        for (String name : trackedPlayers) {
            if (!currentPlayers.contains(name)) {
                double[] pos = lastKnownPositions.getOrDefault(name, new double[]{0, 0, 0});
                YeetVisClient.sendPlayerEvent(name, pos[0], pos[1], pos[2], false);
            }
        }

        trackedPlayers.clear();
        trackedPlayers.addAll(currentPlayers);
        lastKnownPositions.clear();
        lastKnownPositions.putAll(currentPositions);
    }

    public Set<String> getTrackedPlayers() {
        return Set.copyOf(trackedPlayers);
    }

    public Map<String, double[]> getLastKnownPositions() {
        return Map.copyOf(lastKnownPositions);
    }
}
