package com.yeedar.terrain;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Reads one loaded chunk's surface into {@link ChunkPlanes}.
 *
 * <h2>What the rules allow, and how this stays inside them</h2>
 *
 * Eden permits reading "the top layer only of solid blocks (including y
 * position), and above transparent blocks in the world for mapping purposes".
 *
 * The top layer comes from the MOTION_BLOCKING heightmap, which the server
 * sends to clients. OCEAN_FLOOR would give the seabed directly but is
 * LIVE_WORLD purpose and is never transmitted — a client cannot read it.
 *
 * So the seabed is found by stepping down while the block is water, stopping at
 * the first block that is not. That is the "above transparent blocks" clause,
 * and it is what Xaero's World Map — which Eden recommends — displays.
 *
 * <b>The property to preserve is that this never descends through a solid
 * block.</b> Not "never descends". The loop below continues only while the
 * block it just read is water; any solid block ends it immediately. A change
 * that widens that condition — to any transparent block, to air, to a fixed
 * number of steps — would break the rule this mod depends on for its existence.
 *
 * Light level is deliberately never read. That permission is scoped to "within
 * the client", and this data leaves the client.
 */
public final class SurfaceSampler {

    /**
     * How far down a water column may be followed.
     *
     * Deep ocean bottoms out around 40 blocks below sea level, so this is
     * generous. It exists so a pathological column — a bug, a mod-made water
     * shaft — cannot turn one chunk into thousands of block reads on the render
     * thread.
     */
    private static final int MAX_WATER_DEPTH = 64;

    private SurfaceSampler() {}

    /** The surface of `chunk`, or null if any column could not be read. */
    public static ChunkPlanes sample(ClientWorld world, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (pos.x < ChunkPlanes.MIN_CHUNK || pos.x > ChunkPlanes.MAX_CHUNK
                || pos.z < ChunkPlanes.MIN_CHUNK || pos.z > ChunkPlanes.MAX_CHUNK) {
            return null;   // outside the mapped world; nothing to say about it
        }

        Heightmap surface = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING);
        if (surface == null) return null;

        ChunkPlanes planes = new ChunkPlanes(pos.x, pos.z);
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int z = 0; z < ChunkPlanes.SIDE; z++) {
            for (int x = 0; x < ChunkPlanes.SIDE; x++) {
                // The heightmap reports the first empty y ABOVE the surface, so
                // the surface block itself is one below.
                int top = surface.get(x, z) - 1;
                if (top < ChunkPlanes.MIN_Y) return null;   // nothing here yet

                int worldX = pos.getStartX() + x;
                int worldZ = pos.getStartZ() + z;

                int y = top;
                BlockState state = world.getBlockState(cursor.set(worldX, y, worldZ));

                // Descend through water only. The condition tests the block we
                // just read: the first non-water block ends the loop, so this
                // can never pass through solid ground.
                int steps = 0;
                while (state.getFluidState().isIn(FluidTags.WATER)
                        && y > ChunkPlanes.MIN_Y
                        && steps++ < MAX_WATER_DEPTH) {
                    y--;
                    state = world.getBlockState(cursor.set(worldX, y, worldZ));
                }

                int mapColor = state.getMapColor(world, cursor).id;
                planes.set(ChunkPlanes.index(x, z), mapColor, y, top);
            }
        }
        return planes;
    }
}
