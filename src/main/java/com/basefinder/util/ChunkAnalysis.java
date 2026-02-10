package com.basefinder.util;

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
    private double score;
    private BaseType baseType = BaseType.NONE;
    private final List<SignificantBlock> significantBlocks = new ArrayList<>();

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
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public BaseType getBaseType() { return baseType; }
    public void setBaseType(BaseType baseType) { this.baseType = baseType; }
    public List<SignificantBlock> getSignificantBlocks() { return significantBlocks; }

    public BlockPos getCenterBlockPos() {
        return new BlockPos(chunkPos.getMiddleBlockX(), 64, chunkPos.getMiddleBlockZ());
    }

    public record SignificantBlock(BlockPos pos, Block block) {}
}
