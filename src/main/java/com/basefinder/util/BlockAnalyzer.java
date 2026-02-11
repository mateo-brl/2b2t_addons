package com.basefinder.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.*;

/**
 * Analyzes blocks to determine if they indicate player activity.
 * Tuned for 2b2t - ignores natural structures (villages, temples, mineshafts).
 */
public class BlockAnalyzer {

    /**
     * STRONG indicators - blocks that are NEVER placed by world generation.
     * These are 100% player-placed on 2b2t.
     */
    private static final Set<Block> STRONG_PLAYER_BLOCKS = new HashSet<>(Arrays.asList(
            // Shulker boxes - the #1 indicator of a stash/base
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX, Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX, Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,

            // End game blocks - never natural in overworld
            Blocks.ENDER_CHEST,
            Blocks.BEACON,
            Blocks.ENCHANTING_TABLE,
            Blocks.BREWING_STAND,
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,

            // Valuable blocks - players place these, world gen doesn't
            Blocks.NETHERITE_BLOCK,
            Blocks.DIAMOND_BLOCK,
            Blocks.EMERALD_BLOCK,
            Blocks.IRON_BLOCK,
            Blocks.GOLD_BLOCK,
            Blocks.LAPIS_BLOCK,
            Blocks.REDSTONE_BLOCK,
            Blocks.CRYING_OBSIDIAN,

            // End/Nether blocks in overworld = player brought them
            Blocks.END_STONE_BRICKS,
            Blocks.PURPUR_BLOCK,
            Blocks.PURPUR_PILLAR,
            Blocks.PURPUR_STAIRS,
            Blocks.PURPUR_SLAB,
            Blocks.QUARTZ_BLOCK,
            Blocks.SMOOTH_QUARTZ,
            Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.GILDED_BLACKSTONE,

            // Redstone machines - not natural
            Blocks.PISTON,
            Blocks.STICKY_PISTON,
            Blocks.OBSERVER,
            Blocks.COMPARATOR,
            Blocks.REPEATER,
            Blocks.HOPPER,
            Blocks.DISPENSER,
            Blocks.DROPPER,

            // Concrete - only from players
            Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE,
            Blocks.LIGHT_BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE,
            Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE,
            Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE,
            Blocks.BLACK_CONCRETE,

            // Signs - not natural in 2b2t world gen
            Blocks.OAK_SIGN, Blocks.SPRUCE_SIGN, Blocks.BIRCH_SIGN,
            Blocks.JUNGLE_SIGN, Blocks.ACACIA_SIGN, Blocks.DARK_OAK_SIGN,
            Blocks.OAK_WALL_SIGN
    ));

    /**
     * Wool blocks used in map art detection.
     */
    private static final Set<Block> WOOL_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL,
            Blocks.LIGHT_BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
            Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL,
            Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL,
            Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL
    ));

    /**
     * Trail blocks - only blocks that strongly indicate player-made paths.
     * Removed: cobblestone (natural), torch (mineshafts), netherrack (nether).
     */
    private static final Set<Block> TRAIL_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.DIRT_PATH,
            Blocks.RAIL,
            Blocks.POWERED_RAIL,
            Blocks.ICE,
            Blocks.PACKED_ICE,
            Blocks.BLUE_ICE,
            Blocks.OBSIDIAN,
            Blocks.NETHER_BRICKS
    ));

    /**
     * Storage blocks for stash detection.
     */
    private static final Set<Block> STORAGE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.ENDER_CHEST,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX, Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX, Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,
            Blocks.HOPPER,
            Blocks.DISPENSER,
            Blocks.DROPPER
    ));

    public static boolean isStrongPlayerBlock(Block block) {
        return STRONG_PLAYER_BLOCKS.contains(block);
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

    public static boolean isMapArtBlock(Block block) {
        return block instanceof ConcretePowderBlock
                || block instanceof StainedGlassBlock
                || STRONG_PLAYER_BLOCKS.contains(block) && block.defaultBlockState().is(Blocks.WHITE_CONCRETE.defaultBlockState().getBlock())
                || WOOL_BLOCKS.contains(block);
    }

    /**
     * Counts obsidian in a chunk. Large amounts (>10) strongly indicate player activity.
     */
    private static boolean isObsidian(Block block) {
        return block == Blocks.OBSIDIAN;
    }

    /**
     * Scores a chunk for player activity. Tuned for 2b2t to avoid false positives
     * from natural structures (villages, temples, dungeons, mineshafts).
     *
     * Scoring:
     * - Strong player block: 5 points each
     * - Shulker box: 25 points each
     * - Obsidian (>5 in chunk): 2 points each
     * - Storage blocks only count if there are also strong indicators
     * - Trail blocks: 0.5 points each
     * - Map art blocks at high Y: 1 point each
     */
    public static ChunkAnalysis analyzeChunk(Level level, LevelChunk chunk) {
        ChunkAnalysis analysis = new ChunkAnalysis(chunk.getPos());

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int strongBlockCount = 0;
        int obsidianCount = 0;
        int storageCount = 0;
        int trailBlockCount = 0;
        int mapArtBlockCount = 0;
        int shulkerCount = 0;
        int highYColoredBlocks = 0;

        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = level.getMinSectionY();

        for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
            LevelChunkSection section = sections[sectionIdx];
            if (section == null || section.hasOnlyAir()) continue;

            int sectionY = (minSectionY + sectionIdx) << 4;

            for (int x = 0; x < 16; x += 2) {
                for (int z = 0; z < 16; z += 2) {
                    for (int y = 0; y < 16; y += 2) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;

                        Block block = state.getBlock();
                        int worldY = sectionY + y;

                        if (isShulkerBox(block)) {
                            shulkerCount++;
                            strongBlockCount++;
                            analysis.addSignificantBlock(new BlockPos(minX + x, worldY, minZ + z), block);
                        } else if (isStrongPlayerBlock(block)) {
                            strongBlockCount++;
                            analysis.addSignificantBlock(new BlockPos(minX + x, worldY, minZ + z), block);
                        }

                        if (isObsidian(block)) {
                            obsidianCount++;
                        }

                        if (isStorageBlock(block)) {
                            storageCount++;
                        }

                        if (isTrailBlock(block)) {
                            trailBlockCount++;
                        }

                        if (isMapArtBlock(block) && worldY > 200) {
                            highYColoredBlocks++;
                            mapArtBlockCount++;
                        }
                    }
                }
            }
        }

        // Only count obsidian as significant if there's a lot (>5 = not just a portal)
        int significantObsidian = obsidianCount > 5 ? obsidianCount : 0;

        analysis.setPlayerBlockCount(strongBlockCount);
        analysis.setStorageCount(storageCount);
        analysis.setTrailBlockCount(trailBlockCount);
        analysis.setMapArtBlockCount(mapArtBlockCount);
        analysis.setShulkerCount(shulkerCount);

        // Determine base type - only flag if strong indicators present
        if (highYColoredBlocks > 50) {
            analysis.setBaseType(BaseType.MAP_ART);
        } else if (shulkerCount >= 1) {
            analysis.setBaseType(BaseType.STORAGE);
        } else if (strongBlockCount >= 3 && storageCount >= 3) {
            analysis.setBaseType(BaseType.STORAGE);
        } else if (strongBlockCount >= 5) {
            analysis.setBaseType(BaseType.CONSTRUCTION);
        } else if (trailBlockCount >= 15) {
            // High threshold for trails to avoid mineshaft detection
            analysis.setBaseType(BaseType.TRAIL);
        } else {
            analysis.setBaseType(BaseType.NONE);
        }

        // Score calculation - weighted towards strong indicators
        double score = shulkerCount * 25.0
                + strongBlockCount * 5.0
                + significantObsidian * 2.0
                + mapArtBlockCount * 1.0
                + trailBlockCount * 0.5;

        analysis.setScore(score);
        return analysis;
    }
}
