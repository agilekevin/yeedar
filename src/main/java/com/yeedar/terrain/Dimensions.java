package com.yeedar.terrain;

import net.minecraft.client.world.ClientWorld;

/**
 * Names dimensions the way YeetVis stores them, and says what may be done in
 * each.
 *
 * <p>Exists because both upload paths used to write {@code "overworld"} as a
 * literal, so everything Yeedar ever sent claimed to come from there. On Eden
 * the Nether is 1:1 with the overworld, which means a mislabelled Nether chunk
 * did not land somewhere obviously wrong — it landed exactly on top of the real
 * overworld chunk it shadows.
 *
 * <p>Unknown dimensions keep their own name rather than being folded into
 * overworld. A recognisably unfamiliar label can be found and cleaned up later;
 * a plausible lie cannot.
 */
public final class Dimensions {

    public static final String OVERWORLD = "overworld";
    public static final String NETHER = "nether";
    public static final String END = "end";
    public static final String UNKNOWN = "unknown";

    private Dimensions() {}

    /** The YeetVis name for a dimension registry key. */
    public static String name(String registryKey) {
        if (registryKey == null || registryKey.isBlank()) return UNKNOWN;
        String key = registryKey.trim();
        String bare = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        return switch (bare) {
            case "overworld" -> OVERWORLD;
            case "the_nether", "nether" -> NETHER;
            case "the_end", "end" -> END;
            // Not ours to name. Pass it through so it is visibly foreign
            // rather than silently filed as somewhere it is not.
            default -> key;
        };
    }

    /** The YeetVis name for the world the client is currently in. */
    public static String of(ClientWorld world) {
        if (world == null) return UNKNOWN;
        return name(world.getRegistryKey().getValue().toString());
    }

    /**
     * Whether terrain from this dimension may be uploaded at all.
     *
     * <p>A dimension we cannot name would be stored under a label nobody can
     * later identify — which is precisely the mess this class exists to clean
     * up, repeated.
     */
    public static boolean isMappable(String dimension) {
        return OVERWORLD.equals(dimension) || NETHER.equals(dimension);
    }

    /**
     * Whether cave-mode sampling is permitted here.
     *
     * <p>Eden's rules: <em>"Maps may use 'cave mode' in the nether only."</em>
     * So this is a gate rather than a preference. Cave mode in the overworld is
     * a rules breach, and the sampler asks this question instead of being
     * trusted to remember.
     */
    public static boolean allowsCaveMode(String dimension) {
        return NETHER.equals(dimension);
    }
}
