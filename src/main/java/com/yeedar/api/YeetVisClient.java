package com.yeedar.api;

import com.google.gson.Gson;
import com.yeedar.config.YeedarConfig;
import com.yeedar.net.EdenServer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.yeedar.terrain.ChunkPlanes;
import com.yeedar.terrain.Dimensions;
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
import java.util.concurrent.CompletableFuture;
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

    /**
     * Set once the API has said this build is too old to accept.
     *
     * <p>Suppresses every further upload for the session rather than letting
     * each one fail on its own: the server has already decided, and retrying
     * only produces noise. Cleared on disconnect, so relaunching after an
     * update starts clean without restarting the game.
     */
    private static volatile boolean versionRejected = false;

    public static void sendPlayerEvent(String playerName, double x, double y, double z, boolean entered, boolean friendly) {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();

        if (baseUrl == null || baseUrl.isEmpty()) return;
        if (token == null || token.isEmpty()) return;
        if (versionRejected) return;
        // Backstop. PlayerTracker already stops sweeping off Eden; this is
        // here so a future caller cannot reintroduce the leak by accident.
        if (!EdenServer.connected()) return;

        if (!checkRateLimit()) {
            System.err.println("[Yeedar] Rate limited, skipping API call");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player", playerName);
        payload.put("x", (int) x);
        payload.put("y", (int) y);
        payload.put("z", (int) z);
        // The dimension actually observed in. This was the literal "overworld"
        // for every sighting Yeedar ever sent, which on a 1:1 server put Nether
        // sightings on top of the overworld rather than somewhere obviously odd.
        payload.put("world", Dimensions.of(MinecraftClient.getInstance().world));
        payload.put("snitch_name", "yeedar-" + (entered ? "enter" : "leave"));
        payload.put("group", friendly ? "yeedar-known" : "yeedar-unknown");
        String reporter = config.getUsername().isEmpty() ? "unknown" : config.getUsername();
        payload.put("raw", String.format("[Yeedar/%s] %s %s range (observer at %.1f, %.1f, %.1f)",
                reporter, playerName, entered ? "entered" : "left", x, y, z));

        String json = GSON.toJson(payload);

        HttpRequest request = AuthedRequest.to(baseUrl + "/events", token)
                .header("Content-Type", "application/json")
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
     * Report that a player logged out, at the position they logged out from.
     *
     * <p>The one call in Yeedar that publishes somebody else's precise
     * coordinates. Everything else — {@link #sendPlayerEvent} included —
     * reports the OBSERVER's position, so that watching somebody never reveals
     * where they are. A logout is the agreed exception: the player is gone, the
     * spot is what matters, and Combat Radar already tells its own user the
     * same thing locally.
     *
     * <p>Callers must have filtered friendlies out already; LogoutTracker does.
     */
    public static void sendLogoutEvent(String playerName, double x, double y, double z) {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();

        if (baseUrl == null || baseUrl.isEmpty()) return;
        if (token == null || token.isEmpty()) return;
        if (versionRejected) return;
        // Backstop, matching sendPlayerEvent: nothing is reported off Eden.
        if (!EdenServer.connected()) return;

        if (!checkRateLimit()) {
            // Louder than the sighting path's stderr line, and deliberately so.
            // A dropped sighting is replaced by the next sweep a second later;
            // a dropped logout is gone for good, because the player is. The cap
            // is 5 per 10s, which a busy area can genuinely exhaust.
            chat("§6[Yeedar] §fRate limited — a logout report for §f"
                    + playerName + "§f was dropped.");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player", playerName);
        payload.put("x", (int) x);
        payload.put("y", (int) y);
        payload.put("z", (int) z);
        payload.put("world", Dimensions.of(MinecraftClient.getInstance().world));
        // Null, and load-bearing. The API upserts a snitch for any event that
        // carries BOTH a snitch_name and coordinates, so a name here would plant
        // a phantom snitch at the player's logout spot and feed it into the
        // snitch layer and the coverage maths.
        payload.put("snitch_name", null);
        payload.put("group", "yeedar-unknown");
        payload.put("action", "logout");
        String reporter = config.getUsername().isEmpty() ? "unknown" : config.getUsername();
        payload.put("raw", String.format("[Yeedar/%s] %s logged out at %.0f, %.0f, %.0f",
                reporter, playerName, x, y, z));

        String json = GSON.toJson(payload);

        HttpRequest request = AuthedRequest.to(baseUrl + "/events", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    noteStatus(response.statusCode());
                    if (response.statusCode() != 200) {
                        System.err.println("[Yeedar] Logout report returned "
                                + response.statusCode() + ": " + response.body());
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[Yeedar] Logout report error: "
                            + throwable.getMessage());
                    return null;
                });
    }

    /**
     * Fetch the shared list of namelayers a bare {@code /yeedar jalist} scans.
     *
     * <p>Players belong to namelayers whose snitches nobody wants imported.
     * The list is server-side and edited from Discord, so it is the same for
     * everyone and does not have to be retyped on every scan.
     *
     * <p>Hands back an empty list on any failure: falling back to "scan
     * everything" is the old behaviour, and is better than refusing to scan.
     */
    public static CompletableFuture<List<String>> fetchDefaultGroups() {
        YeedarConfig config = YeedarConfig.getInstance();
        if (unconfiguredReason() != null) {
            return CompletableFuture.completedFuture(List.of());
        }
        HttpRequest request = AuthedRequest.to(config.getApiBaseUrl() + "/jalist/defaults", config.getToken())
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        System.err.println("[Yeedar] defaults fetch returned " + resp.statusCode());
                        return List.<String>of();
                    }
                    var body = GSON.fromJson(resp.body(), DefaultsResponse.class);
                    return body == null || body.groups == null ? List.<String>of() : body.groups;
                })
                .exceptionally(t -> {
                    System.err.println("[Yeedar] defaults fetch failed: " + t.getMessage());
                    return List.of();
                });
    }

    private static final class DefaultsResponse {
        List<String> groups;
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

        if (!EdenServer.connected()) {
            // Silent to the player by design; still counted, because a scan
            // that stored nothing must not report success. In practice
            // unreachable — JukeAlert only exists on Eden, so a scan
            // elsewhere never gets far enough to have rows to send.
            System.out.println("[Yeedar] skipping jalist upload — not connected to "
                    + EdenServer.HOST);
            failed.addAndGet(entries.size());
            return;
        }

        Unconfigured why = unconfiguredReason();
        if (why != null) {
            // This used to return silently to stderr, which is how a 150-page
            // scan could report success having sent nothing at all.
            System.err.println("[Yeedar] skipping jalist upload — " + why.problem());
            failed.addAndGet(entries.size());
            chat("§c" + entries.size() + " snitches were not uploaded. " + why.problem());
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

        HttpRequest request = AuthedRequest.to(baseUrl + "/snitches/jalist", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        send(request, rows.size(), 0);
    }

    /**
     * Upload one batch of sampled chunks.
     *
     * <p>Unlike {@link #uploadJalist}, this reports its outcome: a snitch scan
     * reports separately from the upload, but terrain has nothing else
     * watching, so a failed batch would be silently lost if this fired and
     * forgot like that one does.
     */
    public static CompletableFuture<Boolean> uploadTerrain(String world, List<ChunkPlanes> batch) {
        YeedarConfig config = YeedarConfig.getInstance();
        if (!EdenServer.connected()) {
            // Backstop behind TerrainCapture's own check. Returning false
            // hands the batch back to the buffer rather than dropping it, so
            // chunks sampled just before a disconnect survive the reconnect.
            System.out.println("[Yeedar] skipping terrain upload — not connected to "
                    + EdenServer.HOST);
            return CompletableFuture.completedFuture(false);
        }

        Unconfigured why = unconfiguredReason();
        if (why != null) {
            System.err.println("[Yeedar] skipping terrain upload — " + why.problem());
            return CompletableFuture.completedFuture(false);
        }

        List<Map<String, Object>> rows = new ArrayList<>(batch.size());
        for (ChunkPlanes planes : batch) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cx", planes.cx());
            row.put("cz", planes.cz());
            // `colors` holds palette indices whenever `palette` is present.
            // Older rows on the server have no palette and hold MapColor ids
            // directly; the server tells them apart by that field's presence.
            row.put("palette", planes.encodePalette());
            row.put("colors", planes.encodeColors());
            row.put("floors", planes.encodeFloors());
            row.put("tops", planes.encodeTops());
            rows.add(row);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        // Passed in rather than read here: these chunks were sampled some time
        // ago, and the player may have changed dimension since. Asking "where
        // am I now" would relabel them, which is the bug being fixed.
        payload.put("world", world);
        payload.put("chunks", rows);

        HttpRequest request = AuthedRequest.to(config.getApiBaseUrl() + "/terrain", config.getToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status == 200) return true;
                    // 429 is the server's hourly cap and is expected under heavy
                    // exploration; say so plainly rather than as an error.
                    if (status == 429) {
                        System.out.println("[Yeedar] terrain rate cap reached; "
                                + "pausing until the next hour");
                    } else {
                        System.err.println("[Yeedar] terrain upload failed: " + status
                                + " " + response.body());
                    }
                    return false;
                })
                .exceptionally(error -> {
                    System.err.println("[Yeedar] terrain upload error: " + error.getMessage());
                    return false;
                });
    }

    /**
     * Why uploads cannot be sent, or null when they can.
     *
     * <p>Two lines: what is wrong, and the command that fixes it. This is the
     * one failure a player can resolve unaided, so it is worth saying properly
     * rather than in shorthand.
     */
    public record Unconfigured(String problem, String fix) { }

    public static Unconfigured unconfiguredReason() {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return new Unconfigured(
                    "Yeedar does not know where YeetVis is.",
                    "Set it with §f/yeedar api <url>§7, then run §f/yeedar login§7.");
        }
        if (token == null || token.isEmpty()) {
            return new Unconfigured(
                    "You are not logged in, so Yeedar cannot upload anything.",
                    "Run §f/yeedar login§7 to connect your Discord account, then try again.");
        }
        return null;
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
                    } else if (response.statusCode() == 426) {
                        // A permanent answer, not a blip. The backoff ladder
                        // would spend 2s, 4s and 8s re-asking a question the
                        // server has already settled, then report it as an
                        // upload failure rather than as what it is.
                        noteStatus(response.statusCode());
                        failed.addAndGet(count);
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

    /**
     * Notice a 426 and shut uploads down for the session.
     *
     * <p>Spoken exactly once. A line that reappears every second is a line
     * people stop reading, which is the same reasoning UpdateNotifier uses for
     * its once-per-version rule.
     */
    private static void noteStatus(int statusCode) {
        if (statusCode != 426 || versionRejected) return;
        versionRejected = true;
        chat("§c[Yeedar] This version is no longer accepted by YeetVis. "
                + "§fNothing more will be uploaded this session — "
                + "update Yeedar and restart to resume.");
    }

    /** Forget a rejection, so a fresh connection re-checks. */
    public static void clearVersionRejection() {
        versionRejected = false;
    }
}
