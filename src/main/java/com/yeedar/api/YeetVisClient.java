package com.yeedar.api;

import com.google.gson.Gson;
import com.yeedar.config.YeedarConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.yeedar.tracker.JalistEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class YeetVisClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final Deque<Long> recentSendTimestamps = new ArrayDeque<>();
    private static final int UPLOAD_RETRIES = 3;
    private static final long UPLOAD_RETRY_BASE_MS = 2000;
    private static final ScheduledExecutorService RETRY_POOL =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "yeedar-upload-retry");
                t.setDaemon(true);   // never hold the game open
                return t;
            });
    /** Confirmed stored vs given up on, so a scan can report what actually
     *  landed rather than what it read. */
    private static final AtomicInteger uploaded = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
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

    /**
     * Upload a completed /jalist scan as one batch.
     *
     * <p>Sent whole rather than per page so the server sees a single coherent
     * reading, mirroring how /friendlies replaces a group's members. Absence
     * is not deletion server-side — a scan only proves what this player can
     * see — so a partial scan is safe to send.
     *
     * <p>Deliberately not rate-limited: this is one user-initiated request,
     * not the per-sighting stream the limiter exists to throttle.
     */
    public static void uploadJalist(List<JalistEntry> entries) {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();

        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) {
            System.err.println("[Yeedar] Not configured; skipping jalist upload");
            return;
        }

        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (JalistEntry e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", e.x);
            row.put("y", e.y);
            row.put("z", e.z);
            row.put("world", e.world);
            row.put("name", e.name);
            row.put("group", e.group);
            row.put("type", e.type);
            // Exactly one of these is normally set: JukeAlert reports only the
            // next event. Sending null for the other leaves whatever an
            // earlier scan established intact.
            row.put("dormant_ts", e.dormantTs == 0 ? null : Instant.ofEpochMilli(e.dormantTs).toString());
            row.put("cull_ts", e.cullTs == 0 ? null : Instant.ofEpochMilli(e.cullTs).toString());
            rows.add(row);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snitches", rows);
        payload.put("scanned_at", Instant.now().toString());
        payload.put("uploaded_by", config.getUsername());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/snitches/jalist"))
                .header("Content-Type", "application/json")
                .header("X-Yeedar-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        send(request, rows.size(), 0);
    }

    /**
     * POST a batch, retrying on failure.
     *
     * <p>A scan of several thousand snitches is a dozen or more batches, and
     * the first version dropped any that failed: pending was cleared before
     * the request completed, so a 502 lost 400 snitches silently while the
     * scan still reported success. Retries make a transient failure survivable;
     * the counter makes a permanent one visible.
     */
    private static void send(HttpRequest request, int count, int attempt) {
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        uploaded.addAndGet(count);
                        System.out.println("[Yeedar] Uploaded " + count + " snitches: " + response.body());
                    } else {
                        retryOrGiveUp(request, count, attempt,
                                "HTTP " + response.statusCode() + " " + response.body());
                    }
                })
                .exceptionally(t -> {
                    retryOrGiveUp(request, count, attempt, String.valueOf(t.getMessage()));
                    return null;
                });
    }

    private static void retryOrGiveUp(HttpRequest request, int count, int attempt, String why) {
        if (attempt < UPLOAD_RETRIES) {
            long delay = UPLOAD_RETRY_BASE_MS * (1L << attempt);   // 2s, 4s, 8s
            System.err.println("[Yeedar] upload of " + count + " failed (" + why
                    + "); retry " + (attempt + 1) + "/" + UPLOAD_RETRIES + " in " + delay + "ms");
            RETRY_POOL.schedule(() -> send(request, count, attempt + 1), delay, TimeUnit.MILLISECONDS);
            return;
        }
        failed.addAndGet(count);
        System.err.println("[Yeedar] gave up on " + count + " snitches: " + why);
        chat("§c" + count + " snitches failed to upload (" + why + ").");
    }

    /** Snitches confirmed stored, and given up on, since the counters were reset. */
    public static int uploadedCount() { return uploaded.get(); }
    public static int failedCount() { return failed.get(); }

    public static void resetUploadCounters() {
        uploaded.set(0);
        failed.set(0);
    }

    /**
     * Report to chat from an HTTP completion.
     *
     * <p>These callbacks run on an HttpClient worker thread, so the message is
     * marshalled onto the client thread before touching the player.
     */
    private static void chat(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("[Yeedar] " + message), false);
            }
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
