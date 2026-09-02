package com.yeedar.launch;

import com.google.gson.Gson;
import com.yeedar.config.YeedarConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a launch attempt to YeetVis and plays back whatever it says.
 *
 * <p>The client knows nothing. It does not know the code, it does not know
 * which payloads exist, and it cannot tell a wrong code from a wrong payload
 * except by reading the sentence the server hands back. That is deliberate:
 * this repository is public, and anything shipped in the jar is readable by
 * anyone who wants to skip the game.
 */
public final class LaunchClient {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /** Countdown beats are timed off the main thread; nothing here blocks the
     *  game, and the thread never holds Minecraft open. */
    private static final ScheduledExecutorService COUNTDOWN_POOL =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "yeedar-launch-countdown");
                t.setDaemon(true);
                return t;
            });

    private LaunchClient() {}

    /** What the server sends back. A null ordnance means nothing was fired. */
    private static final class LaunchResponse {
        String ordnance;
        String message;
        Integer countdown_seconds;
    }

    public static void launch(String code, String thing, int x, int z) {
        YeedarConfig config = YeedarConfig.getInstance();
        if (!config.isLoggedIn()) {
            say("§cNot logged in. §7Run §f/yeedar login§7 first.");
            return;
        }

        String body = GSON.toJson(Map.of(
                "code", code, "thing", thing, "x", x, "z", z));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiBaseUrl() + "/strikes"))
                .header("X-Yeedar-Token", config.getToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(LaunchClient::handle)
                .exceptionally(t -> {
                    say("§cLaunch control is not answering. §7(" + t.getMessage() + ")");
                    return null;
                });
    }

    private static void handle(HttpResponse<String> response) {
        if (response.statusCode() == 403) {
            say("§cLaunch control does not recognise you.");
            return;
        }
        if (response.statusCode() != 200) {
            say("§cLaunch control returned " + response.statusCode() + ".");
            return;
        }

        LaunchResponse parsed;
        try {
            parsed = GSON.fromJson(response.body(), LaunchResponse.class);
        } catch (RuntimeException e) {
            say("§cLaunch control is speaking in tongues.");
            return;
        }
        if (parsed == null || parsed.message == null) {
            say("§cLaunch control said nothing at all.");
            return;
        }

        // A refusal. Printed exactly as sent — the wording is the server's job,
        // and every refusal it writes says LAUNCH REJECTED so nobody walks away
        // believing something is queued.
        if (parsed.ordnance == null) {
            say("§7" + parsed.message);
            return;
        }

        int seconds = parsed.countdown_seconds == null ? 0 : parsed.countdown_seconds;
        List<LaunchCountdown.Beat> beats = LaunchCountdown.forSeconds(seconds);
        for (LaunchCountdown.Beat beat : beats) {
            COUNTDOWN_POOL.schedule(() -> say(beat.text()),
                    beat.atSecond(), TimeUnit.SECONDS);
        }
        // The payload's own line, right after impact.
        COUNTDOWN_POOL.schedule(() -> {
            for (String line : parsed.message.split("\n")) say("§e" + line);
        }, seconds, TimeUnit.SECONDS);
    }

    /** Chat, from whatever thread. Client-side only — nothing is ever sent to
     *  the game server, which is what keeps this harmless. */
    private static void say(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(message), false);
        });
    }
}
