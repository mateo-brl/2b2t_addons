package com.basefinder.util;

import net.minecraft.core.BlockPos;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Record of a found base.
 */
public class BaseRecord {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BlockPos position;
    private final BaseType type;
    private final double score;
    private final String timestamp;
    private final int playerBlockCount;
    private final int storageCount;
    private final int shulkerCount;
    private String notes;

    public BaseRecord(BlockPos position, BaseType type, double score, int playerBlockCount, int storageCount, int shulkerCount) {
        this.position = position;
        this.type = type;
        this.score = score;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
        this.playerBlockCount = playerBlockCount;
        this.storageCount = storageCount;
        this.shulkerCount = shulkerCount;
        this.notes = "";
    }

    public String toLogLine() {
        return String.format("[%s] %s at %d, %d, %d | Score: %.1f | Blocks: %d | Storage: %d | Shulkers: %d%s",
                timestamp,
                type.getDisplayName(),
                position.getX(), position.getY(), position.getZ(),
                score,
                playerBlockCount,
                storageCount,
                shulkerCount,
                notes.isEmpty() ? "" : " | " + notes
        );
    }

    public String toShortString() {
        return String.format("%s @ %d, %d (score: %.0f)", type.getDisplayName(), position.getX(), position.getZ(), score);
    }

    // Getters
    public BlockPos getPosition() { return position; }
    public BaseType getType() { return type; }
    public double getScore() { return score; }
    public String getTimestamp() { return timestamp; }
    public int getPlayerBlockCount() { return playerBlockCount; }
    public int getStorageCount() { return storageCount; }
    public int getShulkerCount() { return shulkerCount; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
