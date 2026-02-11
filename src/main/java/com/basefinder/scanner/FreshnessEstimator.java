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
 * 1. Chunk age data from NewChunkDetector (new chunks nearby = active player)
 * 2. Block version analysis (pre-1.18 blocks = old generation)
 * 3. Weathering indicators (vines, moss growing on structures)
 * 4. Surrounding chunk state (isolated old chunks in new terrain = old base)
 */
public class FreshnessEstimator {

    private final Minecraft mc = Minecraft.getInstance();
    private NewChunkDetector newChunkDetector;
    private ChunkAgeAnalyzer chunkAgeAnalyzer;

    public void setNewChunkDetector(NewChunkDetector detector) {
        this.newChunkDetector = detector;
    }

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

        // Signal 1: NewChunk data - are nearby chunks new or old?
        if (newChunkDetector != null && newChunkDetector.isEnabled()) {
            int newNearby = 0;
            int oldNearby = 0;

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    ChunkPos neighbor = new ChunkPos(pos.x + dx, pos.z + dz);
                    if (newChunkDetector.isNewChunk(neighbor)) newNearby++;
                    if (newChunkDetector.isOldChunk(neighbor)) oldNearby++;
                }
            }

            totalSignals++;
            if (newNearby > oldNearby * 2) {
                // Mostly new chunks = this is new territory, base is active or recently found
                activeSignals += 2;
            } else if (oldNearby > newNearby * 2) {
                // Mostly old chunks = well-traveled area, base may be abandoned
                abandonedSignals += 1;
            } else {
                // Mixed = moderate activity
                activeSignals += 0.5;
            }
        }

        // Signal 2: Chunk generation version
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

        // Signal 3: Weathering indicators in the chunk
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
