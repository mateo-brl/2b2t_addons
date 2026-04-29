package com.basefinder.util;

import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.scan.ChunkCounts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of analyzing a chunk for player activity.
 */
public class ChunkAnalysis {

    private final ChunkPos chunkPos;
    private int playerBlockCount;
    private int storageCount;
    private int trailBlockCount;
    private int mapArtBlockCount;
    private int shulkerCount;
    private int signCount;
    private double score;
    private BaseType baseType = BaseType.NONE;
    private final List<SignificantBlock> significantBlocks = new ArrayList<>();

    // Medium player blocks (crying obsidian, furnaces, etc.)
    private int mediumBlockCount;

    // Entity scanner data
    private double entityScore;
    private int entityCount;
    private int namedEntityCount;
    private int tamedAnimalCount;

    // Cluster scoring data
    private double clusterScore;
    private int clusterSize;

    // Dimension context
    private String dimension = "overworld";

    // Freshness estimation
    private Freshness freshness = Freshness.UNKNOWN;
    private double freshnessConfidence = 0;

    // Distance from spawn (0,0)
    private double distanceFromSpawn;

    // Pure-domain counts produced during scan (consumed by ChunkScannerService /
    // McChunkSource). Filled by BlockAnalyzer.analyzeChunk; null until then.
    private ChunkCounts counts;

    public enum Freshness {
        UNKNOWN,
        ACTIVE,     // Recent activity (new chunks nearby, fresh blocks)
        ABANDONED,  // Old, likely unused (all old chunks, weathered)
        ANCIENT     // Very old, pre-1.18 era
    }

    public ChunkAnalysis(ChunkPos chunkPos) {
        this.chunkPos = chunkPos;
    }

    public void addSignificantBlock(BlockPos pos, Block block) {
        if (significantBlocks.size() < 50) { // cap to avoid memory issues
            significantBlocks.add(new SignificantBlock(pos, block));
        }
    }

    public boolean isInteresting() {
        return baseType != BaseType.NONE;
    }

    // Getters and setters
    public ChunkPos getChunkPos() { return chunkPos; }
    public int getPlayerBlockCount() { return playerBlockCount; }
    public void setPlayerBlockCount(int count) { this.playerBlockCount = count; }
    public int getStorageCount() { return storageCount; }
    public void setStorageCount(int count) { this.storageCount = count; }
    public int getTrailBlockCount() { return trailBlockCount; }
    public void setTrailBlockCount(int count) { this.trailBlockCount = count; }
    public int getMapArtBlockCount() { return mapArtBlockCount; }
    public void setMapArtBlockCount(int count) { this.mapArtBlockCount = count; }
    public int getShulkerCount() { return shulkerCount; }
    public void setShulkerCount(int count) { this.shulkerCount = count; }
    public int getSignCount() { return signCount; }
    public void setSignCount(int count) { this.signCount = count; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public BaseType getBaseType() { return baseType; }
    public void setBaseType(BaseType baseType) { this.baseType = baseType; }
    public List<SignificantBlock> getSignificantBlocks() { return significantBlocks; }

    // Medium block accessors
    public int getMediumBlockCount() { return mediumBlockCount; }
    public void setMediumBlockCount(int count) { this.mediumBlockCount = count; }

    // Entity scanner accessors
    public double getEntityScore() { return entityScore; }
    public void setEntityScore(double entityScore) { this.entityScore = entityScore; }
    public int getEntityCount() { return entityCount; }
    public void setEntityCount(int entityCount) { this.entityCount = entityCount; }
    public int getNamedEntityCount() { return namedEntityCount; }
    public void setNamedEntityCount(int count) { this.namedEntityCount = count; }
    public int getTamedAnimalCount() { return tamedAnimalCount; }
    public void setTamedAnimalCount(int count) { this.tamedAnimalCount = count; }

    // Cluster scoring accessors
    public double getClusterScore() { return clusterScore; }
    public void setClusterScore(double clusterScore) { this.clusterScore = clusterScore; }
    public int getClusterSize() { return clusterSize; }
    public void setClusterSize(int clusterSize) { this.clusterSize = clusterSize; }

    // Freshness accessors
    public Freshness getFreshness() { return freshness; }
    public void setFreshness(Freshness freshness) { this.freshness = freshness; }
    public double getFreshnessConfidence() { return freshnessConfidence; }
    public void setFreshnessConfidence(double confidence) { this.freshnessConfidence = confidence; }

    // Dimension accessors
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    // Distance from spawn
    public double getDistanceFromSpawn() { return distanceFromSpawn; }
    public void setDistanceFromSpawn(double distance) { this.distanceFromSpawn = distance; }

    public ChunkCounts getCounts() { return counts; }
    public void setCounts(ChunkCounts counts) { this.counts = counts; }

    public BlockPos getCenterBlockPos() {
        return new BlockPos(chunkPos.getMiddleBlockX(), 64, chunkPos.getMiddleBlockZ());
    }

    public record SignificantBlock(BlockPos pos, Block block) {}
}
