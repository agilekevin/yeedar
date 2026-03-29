package com.yeedar.api;

import com.sun.net.httpserver.HttpServer;
import com.yeedar.config.YeedarConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OAuthCallbackServer {
    private static final int PORT = 25585;
    private static final int TIMEOUT_SECONDS = 120;
    private static HttpServer server;

    public static void start() {
        if (server != null) {
            stop();
        }

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        } catch (IOException e) {
            System.err.println("[Yeedar] Failed to start OAuth callback server: " + e.getMessage());
            sendChat("\u00a7cFailed to start login server. Is port " + PORT + " in use?");
            return;
        }

        server.createContext("/callback", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String token = params.get("token");
            String username = params.get("username");

            String html;
            if (token != null && !token.isEmpty()) {
                YeedarConfig config = YeedarConfig.getInstance();
                config.setToken(token);
                if (username != null) {
                    config.setUsername(username);
                }
                config.save();

                html = successHtml(username != null ? username : "Unknown");
                sendChat("\u00a7aLogged in as \u00a7f" + (username != null ? username : "Unknown") + "\u00a7a! Tracking is ready.");
            } else {
                html = errorHtml("No token received. Please try again.");
                sendChat("\u00a7cLogin failed — no token received.");
            }

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }

            // Shut down after handling the callback
            stop();
        });

        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        // Auto-shutdown after timeout
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            if (server != null) {
                sendChat("\u00a77Login timed out. Run \u00a7f/yeedar login\u00a77 to try again.");
                stop();
            }
            scheduler.shutdown();
        }, TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public static int getPort() {
        return PORT;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private static void sendChat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(message), false);
                }
            });
        }
    }

    private static String successHtml(String username) {
        return """
                <!DOCTYPE html><html><head><title>Yeedar — Login Successful</title>
                <style>
                  body { background: #0d1117; color: #c9d1d9; font-family: monospace;
                         display: flex; align-items: center; justify-content: center; height: 100vh; }
                  .box { text-align: center; }
                  h1 { color: #3fb950; }
                </style></head><body>
                <div class="box">
                  <h1>Welcome, %s!</h1>
                  <p>You're now logged in to Yeedar. You can close this tab and return to Minecraft.</p>
                </div></body></html>
                """.formatted(username);
    }

    private static String errorHtml(String message) {
        return """
                <!DOCTYPE html><html><head><title>Yeedar — Login Failed</title>
                <style>
                  body { background: #0d1117; color: #c9d1d9; font-family: monospace;
                         display: flex; align-items: center; justify-content: center; height: 100vh; }
                  .box { text-align: center; }
                  h1 { color: #f85149; }
                </style></head><body>
                <div class="box">
                  <h1>Login Failed</h1>
                  <p>%s</p>
                </div></body></html>
                """.formatted(message);
    }
}
