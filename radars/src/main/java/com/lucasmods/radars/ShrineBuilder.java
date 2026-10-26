package com.lucasmods.radars;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Finds solid ground near a target column and builds a shrine there by placing blocks directly
 * (no worldgen structure), so the boss and its tamed cat always have a floor.
 */
public final class ShrineBuilder {
    private static final int SEARCH_RADIUS = 48;
    private static final int SEARCH_STEP = 6;
    private static final int HALF = 4;          // 9x9 platform
    private static final int FALLBACK_Y = 100;  // used when the column is pure void

    private ShrineBuilder() {}

    /** Resolves a location near (targetX, targetZ), builds the shrine and returns its centre (first air block above the lodestone). */
    public static BlockPos resolveAndBuild(ServerLevel level, int targetX, int targetZ) {
        BlockPos centre = findGround(level, targetX, targetZ);
        if (centre == null) {
            BlockPos top = surface(level, targetX, targetZ);
            centre = top.getY() <= level.getMinBuildHeight() + 1 ? new BlockPos(targetX, FALLBACK_Y, targetZ) : top;
            Radars.LOGGER.info("No natural ground near {}, {}; building the shrine anyway at {}", targetX, targetZ, centre);
        }
        build(level, centre);
        return centre;
    }

    private static BlockPos findGround(ServerLevel level, int targetX, int targetZ) {
        for (int r = 0; r <= SEARCH_RADIUS; r += SEARCH_STEP) {
            int samples = r == 0 ? 1 : 8;
            for (int i = 0; i < samples; i++) {
                double angle = i * Math.PI * 2 / samples;
                int x = targetX + (int) Math.round(Math.cos(angle) * r);
                int z = targetZ + (int) Math.round(Math.sin(angle) * r);
                BlockPos top = surface(level, x, z);
                if (top.getY() <= level.getMinBuildHeight() + 1 || top.getY() >= level.getMaxBuildHeight() - 12) continue;
                BlockState ground = level.getBlockState(top.below());
                if (!ground.isSolid() || ground.liquid() || ground.is(BlockTags.LEAVES)) continue;
                if (!level.getFluidState(top).isEmpty()) continue;
                return top;
            }
        }
        return null;
    }

    /** Loads (and generates if needed) the chunk, then returns the heightmap position of the column. */
    private static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
    }

    private static void build(ServerLevel level, BlockPos centre) {
        int floorY = centre.getY() - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                boolean edge = Math.abs(dx) == HALF || Math.abs(dz) == HALF;
                level.setBlock(p.set(x, floorY, z), edge ? Blocks.TUFF_BRICKS.defaultBlockState() : Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
                level.setBlock(p.set(x, floorY - 1, z), Blocks.TUFF_BRICKS.defaultBlockState(), 3);
                for (int dy = 0; dy <= 8; dy++) {
                    level.setBlock(p.set(x, floorY + 1 + dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(p.set(centre.getX(), floorY, centre.getZ()), Blocks.LODESTONE.defaultBlockState(), 3);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = centre.getX() + sx * HALF;
                int z = centre.getZ() + sz * HALF;
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlock(p.set(x, floorY + dy, z), Blocks.TUFF_BRICK_WALL.defaultBlockState(), 3);
                }
                level.setBlock(p.set(x, floorY + 4, z), Blocks.LANTERN.defaultBlockState(), 3);
            }
        }
    }
}
