package com.yeedar.tracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yeedar.api.AuthedRequest;
import com.yeedar.config.YeedarConfig;

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

    /**
     * Whether a real friendly list has ever been loaded.
     *
     * <p>An empty {@link #friendlyPlayers} is indistinguishable from "nobody is
     * friendly" unless something else says otherwise: on a cold launch, or
     * after any failed refresh, {@link #isFriendly} reads false for everyone,
     * including allies this client simply hasn't heard about yet. A caller that
     * treats that as "confirmed not friendly" — as the logout-reporting path
     * does — would publish an ally's exact position, which is the one thing
     * this feature must never do. Once true this stays true: a later failed
     * refresh leaves a stale-but-real list in place, which is a very different
     * situation from never having had one at all.
     */
    private volatile boolean loaded = false;

    public static FriendlyTracker getInstance() {
        return INSTANCE;
    }

    public boolean isFriendly(String playerName) {
        return friendlyPlayers.contains(playerName.toLowerCase());
    }

    public Set<String> getFriendlyPlayers() {
        return friendlyPlayers;
    }

    /** Whether {@link #isFriendly} has ever reflected a real server response. */
    public boolean isLoaded() {
        return loaded;
    }

    public void refresh() {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) return;

        // GET /friendlies is behind verify_dashboard_or_api_secret server-side.
        // This call used to send no credentials at all and had been silently
        // 401ing ever since that gate was added: a 401 is a perfectly good HTTP
        // exchange, so it lands in thenAccept, fails the == 200 test and falls
        // through doing nothing — no log, no retry, no sign anything is wrong.
        // The visible symptom was every ally reading as unknown.
        String token = config.getToken();
        if (token == null || token.isEmpty()) return;   // not logged in yet

        HttpRequest request = AuthedRequest.to(baseUrl + "/friendlies", token)
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
                        loaded = true;
                        System.out.println("[Yeedar] Refreshed friendly list: " + lower.size() + " players");
                    } else {
                        // Say so. This branch staying silent is the only reason
                        // the missing credentials went unnoticed for so long,
                        // and an unloaded list now suppresses logout reporting
                        // entirely — a failure worth one line of stderr.
                        System.err.println("[Yeedar] Friendly list refresh returned "
                                + response.statusCode() + "; keeping "
                                + (loaded ? "the previous list" : "an unloaded list"));
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[Yeedar] Failed to refresh friendlies: " + throwable.getMessage());
                    return null;
                });
    }
}
