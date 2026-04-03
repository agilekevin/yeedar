package com.yeedar.tracker;

import com.google.gson.Gson;
import com.yeedar.config.YeedarConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NamelayerListener {
    private static final NamelayerListener INSTANCE = new NamelayerListener();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    // Matches "/nllm groupname" or "/nll groupname"
    private static final Pattern NLLM_COMMAND = Pattern.compile("^/nll[m]?\\s+(\\S+)");
    // Matches "playerName (RANK)"
    private static final Pattern MEMBER_LINE = Pattern.compile("^(\\S+)\\s+\\(\\w+\\)$");
    // Matches "[groupname] playername: message" but not "[!]"
    private static final Pattern GROUP_CHAT = Pattern.compile("^\\[([^!][^\\]]*)]\\s+(\\S+):");

    // Track which players we've already seen in chat to avoid spamming the API
    private final Set<String> seenChatMembers = new HashSet<>();

    private String pendingGroup = null;
    private boolean capturing = false;
    private final List<String> capturedMembers = new ArrayList<>();

    public static NamelayerListener getInstance() {
        return INSTANCE;
    }

    /**
     * Called when the player sends a chat message (outgoing).
     * Watches for /nllm commands to enter capture mode.
     */
    public void onOutgoingChat(String message) {
        Matcher m = NLLM_COMMAND.matcher(message);
        if (m.find()) {
            pendingGroup = m.group(1).toLowerCase();
            capturing = false;
            capturedMembers.clear();
            System.out.println("[Yeedar] Watching for namelayer list: " + pendingGroup);
        }
    }

    /**
     * Called when a chat message is received from the server (incoming).
     * Parses namelayer member lists and group chat messages.
     */
    public void onIncomingChat(String message) {
        String stripped = message.replaceAll("\u00a7.", "").trim();

        // Check for group chat messages (passive member detection)
        Matcher chatMatch = GROUP_CHAT.matcher(stripped);
        if (chatMatch.find()) {
            String group = chatMatch.group(1).toLowerCase();
            String player = chatMatch.group(2);
            String key = group + ":" + player.toLowerCase();
            if (!seenChatMembers.contains(key)) {
                seenChatMembers.add(key);
                uploadSingleMember(group, player);
            }
            return;
        }

        // /nllm capture mode
        if (pendingGroup == null) return;

        if (stripped.equals("Members are as follows:")) {
            capturing = true;
            capturedMembers.clear();
            return;
        }

        if (capturing) {
            Matcher m = MEMBER_LINE.matcher(stripped);
            if (m.matches()) {
                capturedMembers.add(m.group(1));
            } else if (!stripped.isEmpty()) {
                finishCapture();
            }
        }
    }

    private void finishCapture() {
        if (pendingGroup != null && !capturedMembers.isEmpty()) {
            System.out.println("[Yeedar] Captured " + capturedMembers.size() +
                    " members for group: " + pendingGroup);
            uploadMembers(pendingGroup, new ArrayList<>(capturedMembers));

            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                int count = capturedMembers.size();
                String group = pendingGroup;
                client.execute(() -> client.player.sendMessage(
                        Text.literal("\u00a77[Yeedar] Synced " + count + " members from " + group),
                        false));
            }
        }
        pendingGroup = null;
        capturing = false;
        capturedMembers.clear();
    }

    private void uploadSingleMember(String group, String player) {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();
        if (baseUrl == null || baseUrl.isEmpty()) return;
        if (token == null || token.isEmpty()) return;

        String json = GSON.toJson(Map.of("group", group, "player", player));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/friendlies/member"))
                .header("Content-Type", "application/json")
                .header("X-Yeedar-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        FriendlyTracker.getInstance().refresh();
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[Yeedar] Chat member upload error: " + throwable.getMessage());
                    return null;
                });
    }

    private void uploadMembers(String group, List<String> members) {
        YeedarConfig config = YeedarConfig.getInstance();
        String baseUrl = config.getApiBaseUrl();
        String token = config.getToken();
        if (baseUrl == null || baseUrl.isEmpty()) return;
        if (token == null || token.isEmpty()) return;

        String json = GSON.toJson(Map.of("group", group, "members", members));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/friendlies"))
                .header("Content-Type", "application/json")
                .header("X-Yeedar-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        FriendlyTracker.getInstance().refresh();
                    } else {
                        System.err.println("[Yeedar] Upload friendlies failed: " +
                                response.statusCode() + " " + response.body());
                    }
                })
                .exceptionally(throwable -> {
                    System.err.println("[Yeedar] Upload friendlies error: " + throwable.getMessage());
                    return null;
                });
    }
}
