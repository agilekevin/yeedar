package com.yeedar.api;

import com.google.gson.Gson;
import com.yeedar.config.YeedarConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

public class YeetVisClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final Deque<Long> recentSendTimestamps = new ArrayDeque<>();
    private static final int MAX_MESSAGES_PER_WINDOW = 5;
    private static final long WINDOW_MS = 10_000;

    public static void sendPlayerEvent(String playerName, double x, double y, double z, boolean entered, boolean friendly) {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();

        if (baseUrl == null || baseUrl.isEmpty()) return;
        if (token == null || token.isEmpty()) return;

        if (!checkRateLimit()) {
            System.err.println("[Yeedar] Rate limited, skipping API call");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player", playerName);
        payload.put("x", (int) x);
        payload.put("y", (int) y);
        payload.put("z", (int) z);
        payload.put("world", "overworld");
        payload.put("snitch_name", "yeedar-" + (entered ? "enter" : "leave"));
        payload.put("group", friendly ? "yeedar-known" : "yeedar-unknown");
        String reporter = config.getUsername().isEmpty() ? "unknown" : config.getUsername();
        payload.put("raw", String.format("[Yeedar/%s] %s %s range (observer at %.1f, %.1f, %.1f)",
                reporter, playerName, entered ? "entered" : "left", x, y, z));

        String json = GSON.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/events"))
                .header("Content-Type", "application/json")
                .header("X-Yeedar-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        System.err.println("[Yeedar] API returned " + response.statusCode() + ": " + response.body());
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[Yeedar] API error: " + throwable.getMessage());
                    return null;
                });
    }

    private static synchronized boolean checkRateLimit() {
        long now = System.currentTimeMillis();
        while (!recentSendTimestamps.isEmpty() && now - recentSendTimestamps.peekFirst() > WINDOW_MS) {
            recentSendTimestamps.pollFirst();
        }
        if (recentSendTimestamps.size() >= MAX_MESSAGES_PER_WINDOW) {
            return false;
        }
        recentSendTimestamps.addLast(now);
        return true;
    }
}
