package com.basefinder.scanner;

import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.BlockAnalyzer;
import com.basefinder.util.ChunkAnalysis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
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

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BaseFinder");
    private int debugCounter = 0;

    /**
     * Scan all currently loaded chunks that haven't been scanned yet.
     * Returns newly found interesting chunks.
     */
    public List<ChunkAnalysis> scanLoadedChunks() {
        if (mc.level == null || mc.player == null) {
            LOGGER.warn("[ChunkScanner] mc.level or mc.player is null!");
            return Collections.emptyList();
        }

        List<ChunkAnalysis> newFinds = new ArrayList<>();
        int chunksFound = 0;
        int chunksScanned = 0;

        try {
            // Get chunks directly from the client chunk cache
            var chunkSource = mc.level.getChunkSource();
            int renderDist = mc.options.renderDistance().get();
            int playerChunkX = mc.player.chunkPosition().x;
            int playerChunkZ = mc.player.chunkPosition().z;

            debugCounter++;
            if (debugCounter % 10 == 1) {
                LOGGER.info("[ChunkScanner] Player at chunk ({}, {}), render distance: {}", playerChunkX, playerChunkZ, renderDist);
            }

            for (int x = playerChunkX - renderDist; x <= playerChunkX + renderDist; x++) {
                for (int z = playerChunkZ - renderDist; z <= playerChunkZ + renderDist; z++) {
                    try {
                        LevelChunk chunk = chunkSource.getChunk(x, z, false);
                        if (chunk == null) continue;

                        chunksFound++;
                        ChunkPos pos = chunk.getPos();

                        if (scannedChunks.contains(pos)) continue;
                        scannedChunks.add(pos);
                        chunksScanned++;

                        ChunkAnalysis analysis = BlockAnalyzer.analyzeChunk(mc.level, chunk);

                        if (analysis != null && analysis.getScore() >= minScore && analysis.isInteresting()) {
                            if (!shouldDetect(analysis.getBaseType())) continue;

                            interestingChunks.add(analysis);
                            newFinds.add(analysis);
                            LOGGER.info("[ChunkScanner] Found interesting chunk at ({}, {}) - Type: {}, Score: {}",
                                pos.x, pos.z, analysis.getBaseType(), analysis.getScore());

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
                    } catch (Exception e) {
                        LOGGER.error("[ChunkScanner] Error scanning chunk ({}, {}): {}", x, z, e.getMessage());
                    }
                }
            }

            if (debugCounter % 10 == 1) {
                LOGGER.info("[ChunkScanner] Found {} chunks, scanned {} new chunks, total scanned: {}",
                    chunksFound, chunksScanned, scannedChunks.size());
            }

        } catch (Exception e) {
            LOGGER.error("[ChunkScanner] Fatal error in scanLoadedChunks: {}", e.getMessage());
            e.printStackTrace();
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
