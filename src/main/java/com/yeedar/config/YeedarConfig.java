package com.yeedar.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class YeedarConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("yeedar.json");
    private static YeedarConfig instance;

    private String apiBaseUrl = "https://yeetvis-api.onrender.com";
    private String token = "";
    private String username = "";
    private double detectionRange = 128.0;
    private boolean trackingEnabled = true;

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

    public boolean isLoggedIn() {
        return token != null && !token.isEmpty();
    }
}
