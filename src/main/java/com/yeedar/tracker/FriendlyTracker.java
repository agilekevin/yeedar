package com.yeedar.tracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yeedar.config.YeedarConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FriendlyTracker {
    private static final FriendlyTracker INSTANCE = new FriendlyTracker();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    private volatile Set<String> friendlyPlayers = Collections.emptySet();

    public static FriendlyTracker getInstance() {
        return INSTANCE;
    }

    public boolean isFriendly(String playerName) {
        return friendlyPlayers.contains(playerName.toLowerCase());
    }

    public Set<String> getFriendlyPlayers() {
        return friendlyPlayers;
    }

    public void refresh() {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) return;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/friendlies"))
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        List<String> names = GSON.fromJson(response.body(),
                                new TypeToken<List<String>>() {}.getType());
                        Set<String> lower = new HashSet<>();
                        for (String name : names) {
                            lower.add(name.toLowerCase());
                        }
                        friendlyPlayers = Collections.unmodifiableSet(lower);
                        System.out.println("[Yeedar] Refreshed friendly list: " + lower.size() + " players");
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[Yeedar] Failed to refresh friendlies: " + throwable.getMessage());
                    return null;
                });
    }
}
