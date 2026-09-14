package com.yeedar.update;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Yeedar's own version, read from mod metadata.
 *
 * <p>Deliberately not a constant in Java. {@code build.gradle} expands
 * {@code ${version}} into {@code fabric.mod.json} from {@code mod_version} in
 * {@code gradle.properties}, so the metadata is authoritative and there is
 * exactly one place to bump on release. A second copy here would be a second
 * thing to forget, and it would fail silently.
 */
public final class ModVersion {

    private ModVersion() {}

    /** The running version, or "" when it cannot be read. */
    public static String current() {
        return FabricLoader.getInstance().getModContainer("yeedar")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElseGet(() -> {
                    // A blank version reads to the server as "too old" once a
                    // minimum is configured — it is indistinguishable from a
                    // genuinely stale build. That would surface as a confusing
                    // 426 with nothing in the client to explain it, so the real
                    // cause (we could not resolve our own version) needs to be
                    // visible in the logs before it turns into a lockout nobody
                    // can account for.
                    System.err.println("[Yeedar] Could not resolve mod version "
                            + "from metadata; sending blank version header.");
                    return "";
                });
    }
}
