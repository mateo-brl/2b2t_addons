package com.basefinder.scanner;

import com.basefinder.util.ChunkAnalysis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;

/**
 * Estimates whether a detected base is active, abandoned, or ancient.
 *
 * Uses multiple signals:
 * 1. Block version analysis (pre-1.18 blocks = old generation)
 * 2. Weathering indicators (vines, moss growing on structures)
 */
public class FreshnessEstimator {

    private final Minecraft mc = Minecraft.getInstance();
    private ChunkAgeAnalyzer chunkAgeAnalyzer;

    public void setChunkAgeAnalyzer(ChunkAgeAnalyzer analyzer) {
        this.chunkAgeAnalyzer = analyzer;
    }

    /**
     * Estimate freshness of a detected base.
     * Modifies the analysis in place.
     */
    public void estimateFreshness(ChunkAnalysis analysis) {
        if (mc.level == null) return;

        ChunkPos pos = analysis.getChunkPos();
        double activeSignals = 0;
        double abandonedSignals = 0;
        double ancientSignals = 0;
        int totalSignals = 0;

        // Signal 1: Chunk generation version (replaces old NewChunk signal)
        if (chunkAgeAnalyzer != null) {
            try {
                var chunkSource = mc.level.getChunkSource();
                LevelChunk chunk = chunkSource.getChunk(pos.x, pos.z, false);
                if (chunk != null) {
                    ChunkAgeAnalyzer.ChunkGeneration gen = chunkAgeAnalyzer.analyzeChunkGeneration(chunk);
                    totalSignals++;
                    switch (gen) {
                        case PRE_1_13 -> {
                            ancientSignals += 3;
                        }
                        case PRE_1_16 -> {
                            ancientSignals += 2;
                        }
                        case PRE_1_18 -> {
                            abandonedSignals += 2;
                        }
                        case POST_1_18, CURRENT -> {
                            activeSignals += 1;
                        }
                        default -> {}
                    }
                }
            } catch (Exception e) {
                // Ignore errors
            }
        }

        // Signal 2: Weathering indicators in the chunk
        try {
            var chunkSource = mc.level.getChunkSource();
            LevelChunk chunk = chunkSource.getChunk(pos.x, pos.z, false);
            if (chunk != null) {
                int vineCount = 0;
                int mossCount = 0;
                int minX = pos.getMinBlockX();
                int minZ = pos.getMinBlockZ();

                // Sample blocks for weathering
                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        for (int y = 40; y < 120; y += 4) {
                            BlockState state = chunk.getBlockState(new BlockPos(minX + x, y, minZ + z));
                            if (state.is(Blocks.VINE)) vineCount++;
                            if (state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MOSS_CARPET)) mossCount++;
                        }
                    }
                }

                totalSignals++;
                if (vineCount > 5 || mossCount > 3) {
                    abandonedSignals += 1.5; // Heavy weathering = abandoned
                }
            }
        } catch (Exception e) {
            // Ignore errors
        }

        // Classify freshness
        if (totalSignals == 0) {
            analysis.setFreshness(ChunkAnalysis.Freshness.UNKNOWN);
            analysis.setFreshnessConfidence(0);
            return;
        }

        double total = activeSignals + abandonedSignals + ancientSignals;
        if (total == 0) {
            analysis.setFreshness(ChunkAnalysis.Freshness.UNKNOWN);
            analysis.setFreshnessConfidence(0);
            return;
        }

        double confidence = Math.max(activeSignals, Math.max(abandonedSignals, ancientSignals)) / total;

        if (ancientSignals >= activeSignals && ancientSignals >= abandonedSignals) {
            analysis.setFreshness(ChunkAnalysis.Freshness.ANCIENT);
        } else if (abandonedSignals >= activeSignals) {
            analysis.setFreshness(ChunkAnalysis.Freshness.ABANDONED);
        } else {
            analysis.setFreshness(ChunkAnalysis.Freshness.ACTIVE);
        }

        analysis.setFreshnessConfidence(confidence);
    }
}
