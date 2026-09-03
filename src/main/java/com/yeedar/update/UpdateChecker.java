package com.yeedar.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks the releases API once per game launch whether a newer Yeedar exists.
 *
 * <p>Yeedar cannot update itself — a mod that rewrites its own jar is a large,
 * fragile feature that also wants write access to somebody's mods folder. This
 * is the small thing instead: know that a newer version is out, so someone can
 * be told.
 *
 * <p>Every failure path here is silent. A network blip, an outage, a rate
 * limit, a truncated body — all of them mean "no update known" and none of
 * them produce chat or a stack trace. This is the least important thing the
 * mod does and must never be the loudest.
 */
public final class UpdateChecker {

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/agilekevin/yeedar/releases/latest";

    /** Requests without a User-Agent are rejected outright with a 403. */
    private static final String USER_AGENT = "yeedar-mod";

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /** The newest release, once known. Null until the call lands, and null
     *  forever if it never does. Written once, read from the render thread. */
    private static volatile Release latest;

    /** Guards against a second in-flight request if check() is called twice. */
    private static volatile boolean started;

    private UpdateChecker() {}

    /** A release worth telling somebody about. */
    public record Release(String version, String url) {}

    /** The newest release seen this session, or null if none is known yet. */
    public static Release getLatest() {
        return latest;
    }

    /**
     * Fire the check, at most once per launch. Returns immediately; the result
     * shows up in {@link #getLatest()} whenever it arrives, or never.
     */
    public static void check() {
        if (started) return;
        started = true;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) return;
                    latest = parseRelease(response.body());
                })
                .exceptionally(e -> null);   // offline, DNS, timeout: nothing to say
    }

    /**
     * Pull tag and page URL out of a release payload, or null for anything
     * that is not one.
     *
     * <p>Separated from the request so the failure modes are testable without
     * a network: a 403's rate-limit body is valid JSON and reaches here.
     */
    public static Release parseRelease(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            if (obj == null) return null;
            if (!obj.has("tag_name") || !obj.has("html_url")) return null;

            String tag = obj.get("tag_name").getAsString();
            String url = obj.get("html_url").getAsString();
            if (tag.isBlank() || url.isBlank()) return null;

            return new Release(tag, url);
        } catch (RuntimeException e) {
            // Malformed JSON, an array where an object was expected, a field
            // of the wrong type. All the same answer.
            return null;
        }
    }
}
