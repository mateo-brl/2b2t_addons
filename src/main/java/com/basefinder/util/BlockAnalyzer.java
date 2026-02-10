package com.basefinder.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

/**
 * Analyzes blocks to determine if they indicate player activity.
 */
public class BlockAnalyzer {

    /**
     * Blocks that are strong indicators of player-built structures.
     */
    private static final Set<Block> PLAYER_PLACED_BLOCKS = new HashSet<>(Arrays.asList(
            // Building blocks
            Blocks.OBSIDIAN,
            Blocks.CRYING_OBSIDIAN,
            Blocks.END_STONE_BRICKS,
            Blocks.PURPUR_BLOCK,
            Blocks.PURPUR_PILLAR,
            Blocks.QUARTZ_BLOCK,
            Blocks.SMOOTH_QUARTZ,
            Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.GILDED_BLACKSTONE,
            Blocks.NETHERITE_BLOCK,
            Blocks.DIAMOND_BLOCK,
            Blocks.EMERALD_BLOCK,
            Blocks.GOLD_BLOCK,
            Blocks.IRON_BLOCK,
            Blocks.LAPIS_BLOCK,
            Blocks.REDSTONE_BLOCK,

            // Functional blocks
            Blocks.BEACON,
            Blocks.ENCHANTING_TABLE,
            Blocks.ANVIL,
            Blocks.CHIPPED_ANVIL,
            Blocks.DAMAGED_ANVIL,
            Blocks.BREWING_STAND,
            Blocks.ENDER_CHEST,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,

            // Redstone
            Blocks.PISTON,
            Blocks.STICKY_PISTON,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.HOPPER,
            Blocks.OBSERVER,
            Blocks.COMPARATOR,
            Blocks.REPEATER,

            // Storage
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,

            // Beds
            Blocks.WHITE_BED,
            Blocks.RED_BED,
            Blocks.BLACK_BED,

            // Rails (often trails)
            Blocks.RAIL,
            Blocks.POWERED_RAIL,
            Blocks.ACTIVATOR_RAIL,
            Blocks.DETECTOR_RAIL,

            // Misc player indicators
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.CRAFTING_TABLE,
            Blocks.SMITHING_TABLE,
            Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE,
            Blocks.GRINDSTONE,
            Blocks.STONECUTTER,
            Blocks.LOOM,
            Blocks.LECTERN,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE,
            Blocks.TORCH,
            Blocks.SOUL_TORCH,
            Blocks.WALL_TORCH,
            Blocks.SOUL_WALL_TORCH,
            Blocks.LANTERN,
            Blocks.SOUL_LANTERN,

            // Map art related
            Blocks.WHITE_CONCRETE,
            Blocks.ORANGE_CONCRETE,
            Blocks.MAGENTA_CONCRETE,
            Blocks.LIGHT_BLUE_CONCRETE,
            Blocks.YELLOW_CONCRETE,
            Blocks.LIME_CONCRETE,
            Blocks.PINK_CONCRETE,
            Blocks.GRAY_CONCRETE,
            Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.CYAN_CONCRETE,
            Blocks.PURPLE_CONCRETE,
            Blocks.BLUE_CONCRETE,
            Blocks.BROWN_CONCRETE,
            Blocks.GREEN_CONCRETE,
            Blocks.RED_CONCRETE,
            Blocks.BLACK_CONCRETE,

            // Signs & banners
            Blocks.OAK_SIGN,
            Blocks.SPRUCE_SIGN,
            Blocks.BIRCH_SIGN,
            Blocks.JUNGLE_SIGN,
            Blocks.ACACIA_SIGN,
            Blocks.DARK_OAK_SIGN,
            Blocks.OAK_WALL_SIGN,
            Blocks.ITEM_FRAME,

            // Farm blocks
            Blocks.FARMLAND,
            Blocks.MELON,
            Blocks.PUMPKIN,
            Blocks.HAY_BLOCK,
            Blocks.COMPOSTER
    ));

    /**
     * Blocks that indicate trails / paths to bases.
     */
    private static final Set<Block> TRAIL_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.DIRT_PATH,
            Blocks.COBBLESTONE,
            Blocks.COBBLESTONE_SLAB,
            Blocks.STONE_BRICKS,
            Blocks.RAIL,
            Blocks.POWERED_RAIL,
            Blocks.TORCH,
            Blocks.WALL_TORCH,
            Blocks.NETHER_BRICKS,
            Blocks.ICE,
            Blocks.PACKED_ICE,
            Blocks.BLUE_ICE,
            Blocks.OBSIDIAN,
            Blocks.NETHERRACK
    ));

    /**
     * Blocks that are strong indicators of storage bases.
     */
    private static final Set<Block> STORAGE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.ENDER_CHEST,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,
            Blocks.HOPPER,
            Blocks.DISPENSER,
            Blocks.DROPPER
    ));

    public static boolean isPlayerPlaced(Block block) {
        return PLAYER_PLACED_BLOCKS.contains(block);
    }

    public static boolean isTrailBlock(Block block) {
        return TRAIL_BLOCKS.contains(block);
    }

    public static boolean isStorageBlock(Block block) {
        return STORAGE_BLOCKS.contains(block);
    }

    public static boolean isShulkerBox(Block block) {
        return block instanceof ShulkerBoxBlock;
    }

    /**
     * Check if a block is part of a map art (large flat area of colored blocks at y=255 or similar).
     */
    public static boolean isMapArtBlock(Block block) {
        return block instanceof ConcretePowderBlock
                || block instanceof StainedGlassBlock
                || block == Blocks.WHITE_CONCRETE || block == Blocks.ORANGE_CONCRETE
                || block == Blocks.MAGENTA_CONCRETE || block == Blocks.LIGHT_BLUE_CONCRETE
                || block == Blocks.YELLOW_CONCRETE || block == Blocks.LIME_CONCRETE
                || block == Blocks.PINK_CONCRETE || block == Blocks.GRAY_CONCRETE
                || block == Blocks.LIGHT_GRAY_CONCRETE || block == Blocks.CYAN_CONCRETE
                || block == Blocks.PURPLE_CONCRETE || block == Blocks.BLUE_CONCRETE
                || block == Blocks.BROWN_CONCRETE || block == Blocks.GREEN_CONCRETE
                || block == Blocks.RED_CONCRETE || block == Blocks.BLACK_CONCRETE
                || block instanceof WoolBlock;
    }

    /**
     * Scores a chunk for player activity (0 = natural, higher = more likely player-built).
     */
    public static ChunkAnalysis analyzeChunk(Level level, LevelChunk chunk) {
        ChunkAnalysis analysis = new ChunkAnalysis(chunk.getPos());

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int playerBlockCount = 0;
        int storageCount = 0;
        int trailBlockCount = 0;
        int mapArtBlockCount = 0;
        int shulkerCount = 0;
        int highYColoredBlocks = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    BlockPos pos = new BlockPos(minX + x, y, minZ + z);
                    BlockState state = chunk.getBlockState(pos);
                    Block block = state.getBlock();

                    if (state.isAir()) continue;

                    if (isPlayerPlaced(block)) {
                        playerBlockCount++;
                        analysis.addSignificantBlock(pos, block);
                    }
                    if (isStorageBlock(block)) {
                        storageCount++;
                    }
                    if (isShulkerBox(block)) {
                        shulkerCount++;
                    }
                    if (isTrailBlock(block)) {
                        trailBlockCount++;
                    }
                    if (isMapArtBlock(block) && y > 200) {
                        highYColoredBlocks++;
                        mapArtBlockCount++;
                    }
                }
            }
        }

        analysis.setPlayerBlockCount(playerBlockCount);
        analysis.setStorageCount(storageCount);
        analysis.setTrailBlockCount(trailBlockCount);
        analysis.setMapArtBlockCount(mapArtBlockCount);
        analysis.setShulkerCount(shulkerCount);

        // Determine base type
        if (highYColoredBlocks > 200) {
            analysis.setBaseType(BaseType.MAP_ART);
        } else if (shulkerCount >= 3 || storageCount >= 10) {
            analysis.setBaseType(BaseType.STORAGE);
        } else if (playerBlockCount >= 50) {
            analysis.setBaseType(BaseType.CONSTRUCTION);
        } else if (trailBlockCount >= 20 && playerBlockCount < 30) {
            analysis.setBaseType(BaseType.TRAIL);
        } else {
            analysis.setBaseType(BaseType.NONE);
        }

        // Score calculation
        double score = playerBlockCount * 1.0
                + storageCount * 5.0
                + shulkerCount * 20.0
                + mapArtBlockCount * 0.5;

        analysis.setScore(score);
        return analysis;
    }
}
