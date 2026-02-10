package com.basefinder.scanner;

import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.BlockAnalyzer;
import com.basefinder.util.ChunkAnalysis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.rusherhack.client.api.utils.WorldUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans loaded chunks for signs of player activity.
 * Keeps track of already-scanned chunks to avoid re-scanning.
 */
public class ChunkScanner {

    private final Minecraft mc = Minecraft.getInstance();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final List<ChunkAnalysis> interestingChunks = Collections.synchronizedList(new ArrayList<>());
    private final List<BaseRecord> foundBases = Collections.synchronizedList(new ArrayList<>());
    private final List<ChunkAnalysis> trailChunks = Collections.synchronizedList(new ArrayList<>());

    private double minScore = 20.0;
    private boolean detectMapArt = true;
    private boolean detectStorage = true;
    private boolean detectConstruction = true;
    private boolean detectTrails = true;

    /**
     * Scan all currently loaded chunks that haven't been scanned yet.
     * Returns newly found interesting chunks.
     */
    public List<ChunkAnalysis> scanLoadedChunks() {
        if (mc.level == null) return Collections.emptyList();

        List<ChunkAnalysis> newFinds = new ArrayList<>();
        List<LevelChunk> chunks = WorldUtils.getChunks();

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            if (scannedChunks.contains(pos)) continue;

            scannedChunks.add(pos);
            ChunkAnalysis analysis = BlockAnalyzer.analyzeChunk(mc.level, chunk);

            if (analysis.getScore() >= minScore && analysis.isInteresting()) {
                if (!shouldDetect(analysis.getBaseType())) continue;

                interestingChunks.add(analysis);
                newFinds.add(analysis);

                if (analysis.getBaseType() == BaseType.TRAIL) {
                    trailChunks.add(analysis);
                } else {
                    BaseRecord record = new BaseRecord(
                            analysis.getCenterBlockPos(),
                            analysis.getBaseType(),
                            analysis.getScore(),
                            analysis.getPlayerBlockCount(),
                            analysis.getStorageCount(),
                            analysis.getShulkerCount()
                    );
                    foundBases.add(record);
                }
            }
        }

        return newFinds;
    }

    private boolean shouldDetect(BaseType type) {
        return switch (type) {
            case MAP_ART -> detectMapArt;
            case STORAGE -> detectStorage;
            case CONSTRUCTION -> detectConstruction;
            case TRAIL -> detectTrails;
            case NONE -> false;
        };
    }

    public void reset() {
        scannedChunks.clear();
        interestingChunks.clear();
        foundBases.clear();
        trailChunks.clear();
    }

    public int getScannedCount() { return scannedChunks.size(); }
    public List<ChunkAnalysis> getInterestingChunks() { return Collections.unmodifiableList(interestingChunks); }
    public List<BaseRecord> getFoundBases() { return Collections.unmodifiableList(foundBases); }
    public List<ChunkAnalysis> getTrailChunks() { return Collections.unmodifiableList(trailChunks); }

    // Settings
    public void setMinScore(double minScore) { this.minScore = minScore; }
    public void setDetectMapArt(boolean v) { this.detectMapArt = v; }
    public void setDetectStorage(boolean v) { this.detectStorage = v; }
    public void setDetectConstruction(boolean v) { this.detectConstruction = v; }
    public void setDetectTrails(boolean v) { this.detectTrails = v; }
}
