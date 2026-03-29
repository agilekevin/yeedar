package com.yeedar.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yeedar.api.OAuthCallbackServer;
import com.yeedar.config.YeedarConfig;
import com.yeedar.tracker.PlayerTracker;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class YeedarCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("yeedar")
                    .then(ClientCommandManager.literal("login")
                            .executes(ctx -> {
                                YeedarConfig config = YeedarConfig.getInstance();
                                String baseUrl = config.getApiBaseUrl();
                                if (baseUrl.isEmpty()) {
                                    ctx.getSource().sendFeedback(Text.literal(
                                            "\u00a7cAPI URL not set. Run \u00a7f/yeedar api <url>\u00a7c first."));
                                    return 0;
                                }

                                OAuthCallbackServer.start();
                                String redirect = URLEncoder.encode(
                                        "http://localhost:" + OAuthCallbackServer.getPort() + "/callback",
                                        StandardCharsets.UTF_8);
                                String loginUrl = baseUrl + "/auth/yeedar/start?redirect=" + redirect;

                                Util.getOperatingSystem().open(loginUrl);
                                ctx.getSource().sendFeedback(Text.literal(
                                        "\u00a7aOpening browser for Discord login...\n" +
                                        "\u00a77Complete login in your browser to connect."));
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("logout")
                            .executes(ctx -> {
                                YeedarConfig config = YeedarConfig.getInstance();
                                config.setToken("");
                                config.setUsername("");
                                config.save();
                                ctx.getSource().sendFeedback(Text.literal("\u00a7aLogged out."));
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("token")
                            .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String token = StringArgumentType.getString(ctx, "token");
                                        YeedarConfig.getInstance().setToken(token);
                                        YeedarConfig.getInstance().save();
                                        ctx.getSource().sendFeedback(Text.literal("\u00a7aToken set manually."));
                                        return 1;
                                    })
                            )
                    )
                    .then(ClientCommandManager.literal("api")
                            .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String url = StringArgumentType.getString(ctx, "url");
                                        YeedarConfig.getInstance().setApiBaseUrl(url);
                                        YeedarConfig.getInstance().save();
                                        ctx.getSource().sendFeedback(Text.literal("\u00a7aAPI URL set to " + url));
                                        return 1;
                                    })
                            )
                    )
                    .then(ClientCommandManager.literal("range")
                            .then(ClientCommandManager.argument("blocks", DoubleArgumentType.doubleArg(1.0, 512.0))
                                    .executes(ctx -> {
                                        double range = DoubleArgumentType.getDouble(ctx, "blocks");
                                        YeedarConfig.getInstance().setDetectionRange(range);
                                        YeedarConfig.getInstance().save();
                                        ctx.getSource().sendFeedback(Text.literal(
                                                "\u00a7aDetection range set to " + range + " blocks."));
                                        return 1;
                                    })
                            )
                    )
                    .then(ClientCommandManager.literal("toggle")
                            .executes(ctx -> {
                                YeedarConfig config = YeedarConfig.getInstance();
                                config.setTrackingEnabled(!config.isTrackingEnabled());
                                config.save();
                                String state = config.isTrackingEnabled() ? "\u00a7aenabled" : "\u00a7cdisabled";
                                ctx.getSource().sendFeedback(Text.literal("Yeedar tracking " + state));
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("status")
                            .executes(ctx -> {
                                YeedarConfig config = YeedarConfig.getInstance();
                                int tracked = PlayerTracker.getInstance().getTrackedPlayers().size();

                                ctx.getSource().sendFeedback(Text.literal(
                                        "\u00a76--- Yeedar Status ---\n" +
                                        "\u00a77Tracking: " + (config.isTrackingEnabled() ? "\u00a7aON" : "\u00a7cOFF") + "\n" +
                                        "\u00a77Logged in: " + (config.isLoggedIn()
                                                ? "\u00a7a" + config.getUsername()
                                                : "\u00a7cNo") + "\n" +
                                        "\u00a77API: " + (config.getApiBaseUrl().isEmpty()
                                                ? "\u00a7cNot set"
                                                : "\u00a7a" + config.getApiBaseUrl()) + "\n" +
                                        "\u00a77Range: \u00a7f" + config.getDetectionRange() + " blocks\n" +
                                        "\u00a77Tracked players: \u00a7f" + tracked
                                ));
                                return 1;
                            })
                    )
                    .then(ClientCommandManager.literal("list")
                            .executes(ctx -> {
                                PlayerTracker tracker = PlayerTracker.getInstance();
                                Map<String, double[]> positions = tracker.getLastKnownPositions();

                                if (positions.isEmpty()) {
                                    ctx.getSource().sendFeedback(Text.literal("\u00a77No players in range."));
                                    return 1;
                                }

                                StringBuilder sb = new StringBuilder("\u00a76--- Nearby Players ---");
                                for (Map.Entry<String, double[]> entry : positions.entrySet()) {
                                    double[] pos = entry.getValue();
                                    sb.append(String.format("\n\u00a7f%s\u00a77: %.1f, %.1f, %.1f",
                                            entry.getKey(), pos[0], pos[1], pos[2]));
                                }
                                ctx.getSource().sendFeedback(Text.literal(sb.toString()));
                                return 1;
                            })
                    )
            );
        });
    }
}
