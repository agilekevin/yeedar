package com.yeedar.terrain;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.Registries;
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
 * block it just read is {@link Blocks#WATER} itself; any other block —
 * including a waterlogged one — ends it immediately. A change that widens
 * that condition — to any transparent block, to a fluid-state check, to air,
 * to a fixed number of steps — would break the rule this mod depends on for
 * its existence. A fluid-state check in particular looks equivalent but is
 * not: {@code getFluidState().isIn(FluidTags.WATER)} is also true for
 * waterlogged stairs, slabs, fences, and other solid blocks, which would turn
 * this into exactly the x-ray behaviour the rules forbid on the first
 * waterlogged fence on a pier.
 *
 * That block-identity check has a known cost: {@code SeagrassBlock} and
 * {@code KelpBlock} also report a still-water fluid state but are not
 * {@code Blocks.WATER}, so the descent stops at kelp or seagrass instead of
 * continuing to the sand beneath it. Ocean floors are often carpeted in both,
 * so this is a real, accepted loss of fidelity — plant renders as plant, not
 * seabed. That is the correct direction to be wrong in: this file may read
 * less than the rules allow, never more. Do not special-case plants to
 * "see through" them; every exception widens the condition this file exists
 * to keep narrow.
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

    /**
     * The surface of `chunk`, or null if any column could not be read.
     *
     * Null and an exception mean different things and callers must not
     * conflate them. Null means "nothing to record here" — the chunk is
     * outside the mapped world, or the heightmap has nothing yet — and is
     * the ordinary, expected result for such chunks. An exception (from
     * {@link ChunkPlanes}'s own validation) means the world state did not
     * match what this code assumes, which should not happen for a loaded
     * chunk's own heightmap. Task 6 calls this from the client tick loop; a
     * tick-loop caller must catch that exception rather than let it escape
     * into a render-thread callback.
     */
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
                int top = surface.getOneLower(x, z);
                if (top < ChunkPlanes.MIN_Y) return null;   // nothing here yet

                int worldX = pos.getStartX() + x;
                int worldZ = pos.getStartZ() + z;

                int y = top;
                BlockState state = world.getBlockState(cursor.set(worldX, y, worldZ));

                // Descend through water only, identified by block, not fluid
                // state. getFluidState().isIn(FluidTags.WATER) would also be
                // true for a waterlogged stair, slab, fence, chest, or
                // trapdoor — those are solid, player-placed blocks, and
                // reading beneath them is exactly the x-ray behaviour the
                // rules forbid. isOf(Blocks.WATER) tests the block itself, so
                // the condition tests the block we just read: the first block
                // that is not water itself ends the loop, so this can never
                // pass through solid ground. (It also stops at seagrass and
                // kelp, which are not Blocks.WATER either — an accepted loss
                // of fidelity, not a bug; see the class doc.)
                int steps = 0;
                while (state.isOf(Blocks.WATER)
                        && y > ChunkPlanes.MIN_Y
                        && steps++ < MAX_WATER_DEPTH) {
                    y--;
                    state = world.getBlockState(cursor.set(worldX, y, worldZ));
                }

                // Both the block and the colour vanilla gives it. The colour
                // alone cannot separate blocks that share one — mycelium and
                // purple wool are both MapColor.PURPLE — and re-deriving a
                // colour later from the block is a re-render, where changing
                // what was captured would be a re-capture.
                //
                // The registry's namespaced id, never its numeric one: numeric
                // ids are assigned at runtime and move between versions.
                String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                int mapColor = state.getMapColor(world, cursor).id;
                planes.set(ChunkPlanes.index(x, z), blockId, mapColor, y, top);
            }
        }
        return planes;
    }
}
