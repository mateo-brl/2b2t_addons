package com.basefinder.util;

import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.scan.ChunkClassifier;
import com.basefinder.domain.scan.ChunkCounts;
import com.basefinder.domain.scan.ScoringContext;
import com.basefinder.domain.scan.ScoringResult;
import com.basefinder.domain.world.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import com.basefinder.scanner.CaveAirAnalyzer;

import java.util.*;

/**
 * Analyzes blocks to determine if they indicate player activity.
 * Tuned for 2b2t - ignores natural structures (villages, temples, mineshafts).
 */
public class BlockAnalyzer {

    /**
     * STRONG indicators - blocks that are NEVER placed by world generation.
     * These are 100% player-placed on 2b2t. Scored at 5 pts each.
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

            // Valuable blocks - players place these, world gen doesn't
            Blocks.NETHERITE_BLOCK,
            Blocks.DIAMOND_BLOCK,
            Blocks.EMERALD_BLOCK,
            Blocks.IRON_BLOCK,
            Blocks.GOLD_BLOCK,
            Blocks.LAPIS_BLOCK,
            Blocks.REDSTONE_BLOCK,

            // End/Nether blocks in overworld = player brought them
            // NOTE: These are skipped in their native dimension (see analyzeChunk)
            Blocks.END_STONE_BRICKS,
            Blocks.PURPUR_BLOCK,
            Blocks.PURPUR_PILLAR,
            Blocks.PURPUR_STAIRS,
            Blocks.PURPUR_SLAB,
            Blocks.QUARTZ_BLOCK,
            Blocks.SMOOTH_QUARTZ,

            // Redstone machines - not natural
            Blocks.PISTON,
            Blocks.STICKY_PISTON,
            Blocks.OBSERVER,
            Blocks.COMPARATOR,
            Blocks.REPEATER,
            Blocks.HOPPER,

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
            Blocks.OAK_WALL_SIGN,

            // Glazed terracotta - 100% crafted, never natural
            Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.ORANGE_GLAZED_TERRACOTTA,
            Blocks.MAGENTA_GLAZED_TERRACOTTA, Blocks.LIGHT_BLUE_GLAZED_TERRACOTTA,
            Blocks.YELLOW_GLAZED_TERRACOTTA, Blocks.LIME_GLAZED_TERRACOTTA,
            Blocks.PINK_GLAZED_TERRACOTTA, Blocks.GRAY_GLAZED_TERRACOTTA,
            Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA, Blocks.CYAN_GLAZED_TERRACOTTA,
            Blocks.PURPLE_GLAZED_TERRACOTTA, Blocks.BLUE_GLAZED_TERRACOTTA,
            Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA,
            Blocks.RED_GLAZED_TERRACOTTA, Blocks.BLACK_GLAZED_TERRACOTTA,

            // Rare utility blocks - 100% player-placed
            Blocks.RESPAWN_ANCHOR,
            Blocks.LODESTONE,
            Blocks.CONDUIT
    ));

    /**
     * MEDIUM indicators - blocks that are sometimes in structures but mostly player-placed.
     * Scored at 2 pts each.
     */
    private static final Set<Block> MEDIUM_PLAYER_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.CRYING_OBSIDIAN,     // Ruined portals contain 1-6
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.CRAFTING_TABLE,
            Blocks.LECTERN,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE,
            // Moved from STRONG: these spawn in villages, temples, trial chambers
            Blocks.BREWING_STAND,
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
            Blocks.DISPENSER,
            Blocks.DROPPER
    ));

    /**
     * Blocks that are STRONG in overworld but natural in the End dimension.
     * Skipped when scanning the End.
     */
    private static final Set<Block> END_NATIVE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.END_STONE_BRICKS,
            Blocks.PURPUR_BLOCK,
            Blocks.PURPUR_PILLAR,
            Blocks.PURPUR_STAIRS,
            Blocks.PURPUR_SLAB
    ));

    /**
     * Blocks that are STRONG in overworld but natural in the Nether dimension.
     * Skipped when scanning the Nether.
     */
    private static final Set<Block> NETHER_NATIVE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.QUARTZ_BLOCK,
            Blocks.SMOOTH_QUARTZ
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
     * Concrete blocks - player-placed, also used in map art.
     */
    private static final Set<Block> CONCRETE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE,
            Blocks.LIGHT_BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE,
            Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE,
            Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE,
            Blocks.BLACK_CONCRETE
    ));

    /**
     * ANCIENT CITY / DEEP DARK blocks - these generate naturally and cause false positives.
     */
    private static final Set<Block> ANCIENT_CITY_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.SCULK,
            Blocks.SCULK_CATALYST,
            Blocks.SCULK_SENSOR,
            Blocks.SCULK_SHRIEKER,
            Blocks.SCULK_VEIN,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES,
            Blocks.CRACKED_DEEPSLATE_BRICKS,
            Blocks.CRACKED_DEEPSLATE_TILES,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.DEEPSLATE_BRICK_WALL,
            Blocks.DEEPSLATE_TILE_WALL,
            Blocks.DEEPSLATE_BRICK_STAIRS,
            Blocks.DEEPSLATE_TILE_STAIRS,
            Blocks.DEEPSLATE_BRICK_SLAB,
            Blocks.DEEPSLATE_TILE_SLAB,
            Blocks.SOUL_LANTERN,
            Blocks.SOUL_FIRE
    ));

    /**
     * Check if a block is an ancient city / deep dark indicator.
     */
    public static boolean isAncientCityBlock(Block block) {
        return ANCIENT_CITY_BLOCKS.contains(block);
    }

    /**
     * NATURAL STRUCTURE signature blocks - if ANY of these are found, the chunk is a vanilla structure.
     * These blocks are 100% unique to their respective structures and never player-placed.
     */
    private static final Set<Block> VILLAGE_SIGNATURE_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.BELL,              // 100% unique to villages
            Blocks.COMPOSTER,         // Village farm workstation
            Blocks.HAY_BLOCK          // Village decoration
    ));

    private static final Set<Block> TRIAL_CHAMBER_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.TRIAL_SPAWNER,     // 100% unique to trial chambers
            Blocks.VAULT,             // 100% unique to trial chambers
            Blocks.HEAVY_CORE         // Trial chamber loot
    ));

    /**
     * Trail blocks - only blocks that strongly indicate player-made paths.
     * Removed: ICE (natural in cold biomes), OBSIDIAN (double-dip with ruined portals).
     */
    private static final Set<Block> TRAIL_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.DIRT_PATH,
            Blocks.RAIL,
            Blocks.POWERED_RAIL,
            Blocks.PACKED_ICE,
            Blocks.BLUE_ICE,
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

    /**
     * Check if a block is a strong player indicator, accounting for dimension.
     * End-native blocks (purpur, end stone bricks) are not strong in the End.
     * Nether-native blocks (quartz) are not strong in the Nether.
     */
    public static boolean isStrongPlayerBlock(Block block, ResourceKey<Level> dimension) {
        if (!STRONG_PLAYER_BLOCKS.contains(block)) return false;
        if (dimension == Level.END && END_NATIVE_BLOCKS.contains(block)) return false;
        if (dimension == Level.NETHER && NETHER_NATIVE_BLOCKS.contains(block)) return false;
        return true;
    }

    public static boolean isStrongPlayerBlock(Block block) {
        return STRONG_PLAYER_BLOCKS.contains(block);
    }

    public static boolean isMediumPlayerBlock(Block block) {
        return MEDIUM_PLAYER_BLOCKS.contains(block);
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
                || WOOL_BLOCKS.contains(block)
                || CONCRETE_BLOCKS.contains(block);
    }

    private static boolean isObsidian(Block block) {
        return block == Blocks.OBSIDIAN;
    }

    /**
     * Check if a chunk's biome is one that naturally generates structures
     * with blocks that could cause false positives.
     * Returns a multiplier: 1.0 = normal, <1.0 = reduce score (structure biome).
     */
    public static double getBiomePenalty(Level level, LevelChunk chunk) {
        try {
            BlockPos center = new BlockPos(
                    chunk.getPos().getMiddleBlockX(), 64, chunk.getPos().getMiddleBlockZ());
            Holder<Biome> biomeHolder = level.getBiome(center);

            // Also check underground biome at ancient city depth (Y = -40)
            BlockPos deepCenter = new BlockPos(
                    chunk.getPos().getMiddleBlockX(), -40, chunk.getPos().getMiddleBlockZ());
            Holder<Biome> deepBiomeHolder = level.getBiome(deepCenter);

            // DEEP DARK biome = ancient city territory - very heavy penalty
            if (deepBiomeHolder.is(Biomes.DEEP_DARK)) {
                return 0.15; // 85% penalty
            }

            // Villages - heavy penalty, they contain many workstation blocks
            if (biomeHolder.is(BiomeTags.HAS_VILLAGE_PLAINS)
                    || biomeHolder.is(BiomeTags.HAS_VILLAGE_DESERT)
                    || biomeHolder.is(BiomeTags.HAS_VILLAGE_SAVANNA)
                    || biomeHolder.is(BiomeTags.HAS_VILLAGE_TAIGA)
                    || biomeHolder.is(BiomeTags.HAS_VILLAGE_SNOWY)) {
                return 0.1; // 90% penalty for village biomes
            }

            // Bastion remnants (gold blocks) - Nether
            if (biomeHolder.is(BiomeTags.HAS_BASTION_REMNANT)) {
                return 0.15; // 85% penalty
            }

            // Trial chambers - many dispensers, copper, storage
            if (biomeHolder.is(BiomeTags.HAS_TRIAL_CHAMBERS)) {
                return 0.1; // 90% penalty
            }

            // Desert/jungle temples, witch huts
            if (biomeHolder.is(BiomeTags.HAS_DESERT_PYRAMID)
                    || biomeHolder.is(BiomeTags.HAS_JUNGLE_TEMPLE)
                    || biomeHolder.is(BiomeTags.HAS_SWAMP_HUT)) {
                return 0.2; // 80% penalty
            }

            // Stronghold biomes
            if (biomeHolder.is(BiomeTags.HAS_STRONGHOLD)) {
                return 0.2; // 80% penalty
            }

            // Woodland mansions
            if (biomeHolder.is(BiomeTags.HAS_WOODLAND_MANSION)) {
                return 0.2; // 80% penalty
            }

            // Mineshaft biomes - reduce trail detection
            if (biomeHolder.is(BiomeTags.HAS_MINESHAFT)) {
                return 0.6; // 40% penalty
            }
        } catch (Exception e) {
            // If biome check fails, don't penalize
        }
        return 1.0;
    }

    /**
     * Scores a chunk for player activity. Tuned for 2b2t to avoid false positives.
     *
     * Two-pass scanning:
     * - Pass 1: Sampled scan (x+=2,y+=2,z+=2) for common blocks
     * - Pass 2: 100% scan of block entities (shulkers, chests, barrels) via getBlockEntitiesPos()
     *
     * Scoring:
     * - Strong player block: 5 points each
     * - Medium player block: 2 points each
     * - Shulker box: 25 points each
     * - Obsidian (>5 in chunk): 2 points each
     * - Trail blocks: 0.5 points each
     * - Map art blocks: 1 point each
     * - Signs with text: 15 points each
     * - Multi-Y bonuses for bedrock/sky stashes
     * - Biome penalty for structure biomes
     * - Dimension-aware: skips native blocks in End/Nether
     * - Unified ancient city penalty (doesn't stack with biome penalty)
     */
    public static ChunkAnalysis analyzeChunk(Level level, LevelChunk chunk) {
        ChunkAnalysis analysis = new ChunkAnalysis(chunk.getPos());

        // Store dimension context
        ResourceKey<Level> dimension = level.dimension();
        if (dimension == Level.END) {
            analysis.setDimension("end");
        } else if (dimension == Level.NETHER) {
            analysis.setDimension("nether");
        } else {
            analysis.setDimension("overworld");
        }

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int strongBlockCount = 0;
        int mediumBlockCount = 0;
        int obsidianCount = 0;
        int storageCount = 0;
        int trailBlockCount = 0;
        int mapArtBlockCount = 0;
        int shulkerCount = 0;
        int signWithTextCount = 0;
        int ancientCityBlockCount = 0;
        int villageSignatureCount = 0;
        int trialChamberSignatureCount = 0;

        // Multi-Y scanning
        int bedrockLayerBlocks = 0;
        int skyLayerBlocks = 0;

        // ===== PASS 1: Sampled scan (12.5% of blocks) =====
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
                        } else if (isStrongPlayerBlock(block, dimension)) {
                            strongBlockCount++;
                            analysis.addSignificantBlock(new BlockPos(minX + x, worldY, minZ + z), block);
                        }

                        if (isMediumPlayerBlock(block)) {
                            mediumBlockCount++;
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

                        if (isMapArtBlock(block)) {
                            mapArtBlockCount++;
                        }

                        if (isAncientCityBlock(block) && worldY < 0) {
                            ancientCityBlockCount++;
                        }

                        // Natural structure signature detection
                        if (VILLAGE_SIGNATURE_BLOCKS.contains(block)) {
                            villageSignatureCount++;
                        }
                        if (TRIAL_CHAMBER_BLOCKS.contains(block)) {
                            trialChamberSignatureCount++;
                        }

                        // Sign text detection
                        if (block instanceof SignBlock || block instanceof WallSignBlock) {
                            BlockPos signPos = new BlockPos(minX + x, worldY, minZ + z);
                            if (chunk.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
                                if (hasSignText(sign)) {
                                    signWithTextCount++;
                                    analysis.addSignificantBlock(signPos, block);
                                }
                            }
                        }

                        // Multi-Y tracking
                        if (worldY >= 0 && worldY <= 10 && (isStrongPlayerBlock(block, dimension) || isStorageBlock(block))) {
                            bedrockLayerBlocks++;
                        }
                        if (worldY > 200 && (isStrongPlayerBlock(block, dimension) || isStorageBlock(block))) {
                            skyLayerBlocks++;
                        }
                    }
                }
            }
        }

        // ===== PASS 2: 100% scan of block entities (shulkers, chests, barrels, etc.) =====
        // This catches every single block entity even if missed by the sampled scan.
        // O(n) where n = number of block entities (typically 0-50), not O(65536).
        for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
            BlockState beState = chunk.getBlockState(bePos);
            Block beBlock = beState.getBlock();

            if (isShulkerBox(beBlock)) {
                // Only count if not already found in Pass 1
                // Pass 1 samples at x+=2, y+=2, z+=2, so check alignment
                if (!isSampledPosition(bePos, minX, minZ, minSectionY)) {
                    shulkerCount++;
                    strongBlockCount++;
                    analysis.addSignificantBlock(bePos, beBlock);
                }
            } else if (isStorageBlock(beBlock)) {
                if (!isSampledPosition(bePos, minX, minZ, minSectionY)) {
                    storageCount++;
                }
            }
            // 100% coverage for structure signature blocks (bell, trial spawner, vault)
            if (VILLAGE_SIGNATURE_BLOCKS.contains(beBlock)) {
                villageSignatureCount++;
            }
            if (TRIAL_CHAMBER_BLOCKS.contains(beBlock)) {
                trialChamberSignatureCount++;
            }
        }

        analysis.setPlayerBlockCount(strongBlockCount);
        analysis.setMediumBlockCount(mediumBlockCount);
        analysis.setStorageCount(storageCount);
        analysis.setTrailBlockCount(trailBlockCount);
        analysis.setMapArtBlockCount(mapArtBlockCount);
        analysis.setShulkerCount(shulkerCount);
        analysis.setSignCount(signWithTextCount);

        // Cave air score (MC-dependent)
        double caveAirScore = CaveAirAnalyzer.analyzeChunkCaveAir(chunk, level);

        // Distance au spawn (utilisée par classifier + reportée dans analysis)
        double distFromSpawn = Math.sqrt(
                Math.pow(chunk.getPos().getMiddleBlockX(), 2) +
                Math.pow(chunk.getPos().getMiddleBlockZ(), 2));
        analysis.setDistanceFromSpawn(distFromSpawn);

        // Pénalité biome (MC-dependent)
        double biomePenalty = (shulkerCount == 0 && ancientCityBlockCount < 5)
                ? getBiomePenalty(level, chunk)
                : 1.0;

        // Bascule vers la logique pure (domain/scan/ChunkClassifier)
        ChunkCounts counts = ChunkCounts.builder()
                .strongBlockCount(strongBlockCount)
                .mediumBlockCount(mediumBlockCount)
                .obsidianCount(obsidianCount)
                .storageCount(storageCount)
                .trailBlockCount(trailBlockCount)
                .mapArtBlockCount(mapArtBlockCount)
                .shulkerCount(shulkerCount)
                .signWithTextCount(signWithTextCount)
                .ancientCityBlockCount(ancientCityBlockCount)
                .villageSignatureCount(villageSignatureCount)
                .trialChamberSignatureCount(trialChamberSignatureCount)
                .bedrockLayerBlocks(bedrockLayerBlocks)
                .skyLayerBlocks(skyLayerBlocks)
                .caveAirScore(caveAirScore)
                .build();

        ScoringContext ctx = ScoringContext.of(
                toDomainDimension(dimension),
                biomePenalty,
                distFromSpawn,
                CaveAirAnalyzer.getMinReportScore());

        ScoringResult result = ChunkClassifier.classify(counts, ctx);
        analysis.setBaseType(result.baseType());
        analysis.setScore(result.score());
        analysis.setCounts(counts);
        return analysis;
    }

    private static Dimension toDomainDimension(ResourceKey<Level> dimension) {
        if (dimension == Level.END) return Dimension.END;
        if (dimension == Level.NETHER) return Dimension.NETHER;
        return Dimension.OVERWORLD;
    }

    /**
     * Check if a block position was covered by the sampled Pass 1 scan.
     * Pass 1 samples at even local coordinates (x+=2, y+=2, z+=2).
     */
    private static boolean isSampledPosition(BlockPos pos, int chunkMinX, int chunkMinZ, int minSectionY) {
        int localX = pos.getX() - chunkMinX;
        int localZ = pos.getZ() - chunkMinZ;
        // Y within section: pos.getY() mod 16, check if even
        int localY = Math.floorMod(pos.getY(), 16);
        return (localX % 2 == 0) && (localZ % 2 == 0) && (localY % 2 == 0);
    }

    /**
     * Check if a sign has any non-empty text on it.
     */
    private static boolean hasSignText(SignBlockEntity sign) {
        try {
            SignText front = sign.getFrontText();
            SignText back = sign.getBackText();
            for (int i = 0; i < 4; i++) {
                String frontLine = front.getMessage(i, false).getString().trim();
                if (!frontLine.isEmpty()) return true;
                String backLine = back.getMessage(i, false).getString().trim();
                if (!backLine.isEmpty()) return true;
            }
        } catch (Exception e) {
            // Silently fail - sign may not be fully loaded
        }
        return false;
    }
}
