package com.yeedar.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yeedar.api.OAuthCallbackServer;
import com.yeedar.config.YeedarConfig;
import com.yeedar.terrain.TerrainCapture;
import com.yeedar.tracker.JalistScanner;
import com.yeedar.tracker.PlayerTracker;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YeedarCommands {

    /** Split "yeet, yeetistan" or "yeet yeetistan" into namelayer names. */
    static List<String> parseGroups(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,\\s]+")) {
            String name = part.trim().toLowerCase();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /**
     * Add {@code name} to {@code names} unless something matching it is already
     * listed.
     *
     * <p>Matching mirrors {@link YeedarConfig#isIgnored}, which compares with
     * {@code equalsIgnoreCase}. Storing "freecam" beside "FreeCam" would be a
     * second entry that can never match anything the first one misses, so this
     * treats it as already present rather than growing the list.
     *
     * @return true if the list changed
     */
    static boolean addIgnored(List<String> names, String name) {
        if (names == null || name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        for (String existing : names) {
            if (existing != null && existing.equalsIgnoreCase(trimmed)) return false;
        }
        names.add(trimmed);
        return true;
    }

    /**
     * Remove every entry matching {@code name}, ignoring case.
     *
     * <p>Every match, not the first: a config hand-edited before this command
     * existed can hold both "FreeCam" and "freecam", and removing one while
     * leaving the other would look like the command had done nothing.
     *
     * @return true if the list changed
     */
    static boolean removeIgnored(List<String> names, String name) {
        if (names == null || name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        return names.removeIf(existing -> existing != null && existing.equalsIgnoreCase(trimmed));
    }

    /** Render the ignore list for chat, or a hint when it is empty. */
    private static Text ignoreListText(List<String> names) {
        if (names.isEmpty()) {
            return Text.literal("\u00a77No ignored names. \u00a7fFreecam mods report a fake "
                    + "player at your body \u00a77\u2014 add its name with "
                    + "\u00a7f/yeedar ignore add <name>\u00a77.");
        }
        StringBuilder sb = new StringBuilder("\u00a76--- Ignored Names (" + names.size() + ") ---");
        for (String n : names) sb.append("\n\u00a7f").append(n);
        sb.append("\n\u00a77Never reported as sightings. Matching is case-insensitive.");
        return Text.literal(sb.toString());
    }

    private static Text mappingStatus() {
        boolean on = YeedarConfig.getInstance().isMappingEnabled();
        return Text.literal("\u00a76--- Terrain mapping ---\n"
                + "\u00a77State: " + (on ? "\u00a7aON" : "\u00a7cOFF") + "\n"
                + "\u00a77Queued chunks: \u00a7f" + TerrainCapture.getInstance().pending() + "\n"
                + "\u00a77Uploading the chunks you load keeps the map current, and\n"
                + "\u00a77records where you have been. It is off unless you turn it on.");
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("yeedar")
                    .then(ClientCommandManager.literal("jalist")
                            .executes(ctx -> {
                                // Bare form scans the server's default
                                // namelayers, which is almost always what is
                                // meant — most players are in groups nobody
                                // wants imported.
                                JalistScanner.getInstance().beginScanWithDefaults();
                                return 1;
                            })
                            .then(ClientCommandManager.literal("--all")
                                    .executes(ctx -> {
                                        JalistScanner.getInstance().beginScanAll();
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("show-defaults")
                                    .executes(ctx -> {
                                        JalistScanner.getInstance().showDefaults();
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.argument("groups", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        JalistScanner.getInstance().beginScan(
                                                parseGroups(StringArgumentType.getString(ctx, "groups")));
                                        return 1;
                                    })
                            )
                    )
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
                    .then(ClientCommandManager.literal("mapping")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(mappingStatus());
                                return 1;
                            })
                            .then(ClientCommandManager.literal("on")
                                    .executes(ctx -> {
                                        YeedarConfig config = YeedarConfig.getInstance();
                                        config.setMappingEnabled(true);
                                        config.save();
                                        ctx.getSource().sendFeedback(Text.literal(
                                                "\u00a7aTerrain mapping on. \u00a77Chunks you load are "
                                                + "sampled and uploaded so the map stays current — which "
                                                + "also records where you have been. \u00a7f/yeedar mapping off"
                                                + "\u00a77 stops it."));
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("off")
                                    .executes(ctx -> {
                                        YeedarConfig config = YeedarConfig.getInstance();
                                        config.setMappingEnabled(false);
                                        config.save();
                                        ctx.getSource().sendFeedback(Text.literal(
                                                "\u00a7cTerrain mapping off. \u00a77Nothing further is uploaded."));
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("status")
                                    .executes(ctx -> {
                                        ctx.getSource().sendFeedback(mappingStatus());
                                        return 1;
                                    })
                            )
                    )
                    .then(ClientCommandManager.literal("ignore")
                            // Bare form lists, so the read-only case needs no
                            // subcommand — same shape as `/yeedar list`.
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(
                                        ignoreListText(YeedarConfig.getInstance().getIgnoredNames()));
                                return 1;
                            })
                            .then(ClientCommandManager.literal("list")
                                    .executes(ctx -> {
                                        ctx.getSource().sendFeedback(
                                                ignoreListText(YeedarConfig.getInstance().getIgnoredNames()));
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("add")
                                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "name");
                                                YeedarConfig config = YeedarConfig.getInstance();
                                                if (addIgnored(config.getIgnoredNames(), name)) {
                                                    config.save();
                                                    ctx.getSource().sendFeedback(Text.literal(
                                                            "\u00a7aIgnoring \u00a7f" + name
                                                            + "\u00a7a \u2014 it will no longer be reported."));
                                                } else {
                                                    ctx.getSource().sendFeedback(Text.literal(
                                                            "\u00a77Already ignored: \u00a7f" + name
                                                            + "\u00a77 (matching ignores case)."));
                                                }
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("remove")
                                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "name");
                                                YeedarConfig config = YeedarConfig.getInstance();
                                                if (removeIgnored(config.getIgnoredNames(), name)) {
                                                    config.save();
                                                    ctx.getSource().sendFeedback(Text.literal(
                                                            "\u00a7aNo longer ignoring \u00a7f" + name
                                                            + "\u00a7a \u2014 it will be reported again."));
                                                } else {
                                                    ctx.getSource().sendFeedback(Text.literal(
                                                            "\u00a7cNot in the ignore list: \u00a7f" + name));
                                                }
                                                return 1;
                                            })
                                    )
                            )
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
