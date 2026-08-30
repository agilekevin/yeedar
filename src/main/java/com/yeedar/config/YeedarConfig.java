package com.yeedar.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class YeedarConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("yeedar.json");
    private static YeedarConfig instance;

    private String apiBaseUrl = "https://yeetvis-api.onrender.com";
    private String token = "";
    private String username = "";
    private double detectionRange = 128.0;
    private boolean trackingEnabled = true;
    // Freecam mods spawn a fake player at your body while the camera flies free.
    // Reporting it reads as a sighting of yourself standing still. The two names
    // below are what the common Fabric freecam mods use; matching is
    // case-insensitive, so only genuinely different names need listing.
    private List<String> ignoredNames = new ArrayList<>(List.of("FreeCam", "FreeCamera"));

    public static YeedarConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                instance = GSON.fromJson(json, YeedarConfig.class);
                return;
            } catch (IOException e) {
                System.err.println("[Yeedar] Failed to load config: " + e.getMessage());
            }
        }
        instance = new YeedarConfig();
        instance.save();
    }

    public void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[Yeedar] Failed to save config: " + e.getMessage());
        }
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public double getDetectionRange() {
        return detectionRange;
    }

    public void setDetectionRange(double detectionRange) {
        this.detectionRange = detectionRange;
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public void setTrackingEnabled(boolean trackingEnabled) {
        this.trackingEnabled = trackingEnabled;
    }

    /**
     * The live ignore list, never null so callers can mutate it directly.
     * A config file carrying an explicit {@code "ignoredNames": null} would
     * otherwise hand back null and break `/yeedar ignore add`.
     */
    public List<String> getIgnoredNames() {
        if (ignoredNames == null) ignoredNames = new ArrayList<>();
        return ignoredNames;
    }

    public boolean isIgnored(String name) {
        if (ignoredNames == null || name == null) return false;
        for (String ignored : ignoredNames) {
            if (ignored != null && ignored.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public boolean isLoggedIn() {
        return token != null && !token.isEmpty();
    }
}
