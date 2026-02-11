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
 *
 * Enhanced with:
 * - Entity scanning (item frames, armor stands, minecarts, animals, villagers)
 * - Cluster scoring (aggregate neighboring chunk scores)
 * - Nether portal detection (obsidian frame patterns)
 * - Freshness estimation (active vs abandoned bases)
 */
public class ChunkScanner {

    private final Minecraft mc = Minecraft.getInstance();
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();
    private final List<ChunkAnalysis> interestingChunks = Collections.synchronizedList(new ArrayList<>());
    private final List<BaseRecord> foundBases = Collections.synchronizedList(new ArrayList<>());
    private final List<ChunkAnalysis> trailChunks = Collections.synchronizedList(new ArrayList<>());

    // All scanned analyses (for cluster scoring)
    private final Map<ChunkPos, ChunkAnalysis> allAnalyses = new ConcurrentHashMap<>();

    // Entity scanner
    private final EntityScanner entityScanner = new EntityScanner();

    // Freshness estimator
    private FreshnessEstimator freshnessEstimator;

    private double minScore = 20.0;
    private boolean detectMapArt = true;
    private boolean detectStorage = true;
    private boolean detectConstruction = true;
    private boolean detectTrails = true;
    private boolean useEntityScanning = true;
    private boolean useClusterScoring = true;

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
        List<ChunkAnalysis> newlyScanned = new ArrayList<>();
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

                        // Block analysis (existing)
                        ChunkAnalysis analysis = BlockAnalyzer.analyzeChunk(mc.level, chunk);

                        // Entity scanning (new) - additive, only increases scores
                        if (useEntityScanning) {
                            entityScanner.scanEntities(analysis);
                        }

                        // Store all analyses for cluster scoring
                        allAnalyses.put(pos, analysis);
                        newlyScanned.add(analysis);

                        // Freshness estimation
                        if (freshnessEstimator != null && analysis.isInteresting()) {
                            freshnessEstimator.estimateFreshness(analysis);
                        }

                        if (analysis != null && analysis.getScore() >= minScore && analysis.isInteresting()) {
                            if (!shouldDetect(analysis.getBaseType())) continue;

                            interestingChunks.add(analysis);
                            newFinds.add(analysis);
                            LOGGER.info("[ChunkScanner] Found interesting chunk at ({}, {}) - Type: {}, Score: {}{}",
                                pos.x, pos.z, analysis.getBaseType(), String.format("%.1f", analysis.getScore()),
                                analysis.getEntityScore() > 0 ? " (entities: " + String.format("%.1f", analysis.getEntityScore()) + ")" : "");

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
                                // Add freshness and entity info to notes
                                StringBuilder notes = new StringBuilder();
                                if (analysis.getFreshness() != ChunkAnalysis.Freshness.UNKNOWN) {
                                    notes.append(analysis.getFreshness().name());
                                }
                                if (analysis.getEntityCount() > 0) {
                                    if (!notes.isEmpty()) notes.append(", ");
                                    notes.append("entities:").append(analysis.getEntityCount());
                                }
                                if (analysis.getDistanceFromSpawn() > 0) {
                                    if (!notes.isEmpty()) notes.append(", ");
                                    notes.append(String.format("dist:%.0fk", analysis.getDistanceFromSpawn() / 1000));
                                }
                                if (!notes.isEmpty()) {
                                    record.setNotes(notes.toString());
                                }
                                foundBases.add(record);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("[ChunkScanner] Error scanning chunk ({}, {}): {}", x, z, e.getMessage());
                    }
                }
            }

            // Cluster scoring pass: check if newly scanned chunks form clusters
            if (useClusterScoring && !newlyScanned.isEmpty()) {
                applyClusterScoring(newlyScanned, newFinds);
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

    /**
     * CLUSTER SCORING: Aggregate scores from neighboring chunks.
     * A multi-chunk base will have several chunks with moderate scores.
     * By checking neighbors, we can detect bases that span multiple chunks.
     */
    private void applyClusterScoring(List<ChunkAnalysis> newlyScanned, List<ChunkAnalysis> newFinds) {
        for (ChunkAnalysis analysis : newlyScanned) {
            if (analysis.getScore() < 5) continue; // Only cluster chunks with some score

            ChunkPos pos = analysis.getChunkPos();
            double neighborScore = 0;
            int neighborCount = 0;

            // Check all 8 neighbors
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    ChunkPos neighbor = new ChunkPos(pos.x + dx, pos.z + dz);
                    ChunkAnalysis neighborAnalysis = allAnalyses.get(neighbor);
                    if (neighborAnalysis != null && neighborAnalysis.getScore() > 0) {
                        neighborScore += neighborAnalysis.getScore();
                        neighborCount++;
                    }
                }
            }

            if (neighborCount >= 2 && neighborScore >= 15) {
                // This chunk is part of a cluster
                double clusterBonus = neighborScore * 0.3; // 30% of neighbor scores as bonus
                analysis.setClusterScore(neighborScore);
                analysis.setClusterSize(neighborCount);
                double newScore = analysis.getScore() + clusterBonus;
                analysis.setScore(newScore);

                LOGGER.info("[ChunkScanner] Cluster detected at ({}, {}) - {} neighbors, bonus: {}, new score: {}",
                        pos.x, pos.z, neighborCount, String.format("%.1f", clusterBonus), String.format("%.1f", newScore));

                // If the chunk wasn't interesting before but now is after cluster bonus, add it
                if (analysis.isInteresting() && newScore >= minScore && !newFinds.contains(analysis)) {
                    if (shouldDetect(analysis.getBaseType())) {
                        interestingChunks.add(analysis);
                        newFinds.add(analysis);
                        if (analysis.getBaseType() != BaseType.TRAIL) {
                            BaseRecord record = new BaseRecord(
                                    analysis.getCenterBlockPos(),
                                    analysis.getBaseType(),
                                    analysis.getScore(),
                                    analysis.getPlayerBlockCount(),
                                    analysis.getStorageCount(),
                                    analysis.getShulkerCount()
                            );
                            record.setNotes("cluster:" + neighborCount);
                            foundBases.add(record);
                        }
                    }
                }
            }
        }
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
        allAnalyses.clear();
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
    public void setUseEntityScanning(boolean v) { this.useEntityScanning = v; }
    public void setUseClusterScoring(boolean v) { this.useClusterScoring = v; }
    public void setFreshnessEstimator(FreshnessEstimator estimator) { this.freshnessEstimator = estimator; }
}
