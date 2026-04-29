package com.basefinder.scanner;

import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BlockAnalyzer;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.LagDetector;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;

/**
 * Scans loaded chunks for signs of player activity.
 *
 * fastutil-backed (audit/05 §5 étape 8) : {@code scannedChunks} et
 * {@code deferredChunks} sont des {@code LongOpenHashSet}, {@code allAnalyses}
 * un {@code Long2ObjectOpenHashMap}. Clés = packed long (x << 32 | z & mask),
 * voir {@link ChunkId#pack(int, int)}.
 *
 * ~6 octets/entrée vs ~64 pour HashSet&lt;ChunkPos&gt; → cap 1M chunks possible.
 */
public class ChunkScanner {

    private final Minecraft mc = Minecraft.getInstance();
    private final LongOpenHashSet scannedChunks = new LongOpenHashSet();
    private final List<ChunkAnalysis> interestingChunks = Collections.synchronizedList(new ArrayList<>());
    private int foundBasesCount = 0;
    private final List<ChunkAnalysis> trailChunks = Collections.synchronizedList(new ArrayList<>());

    private final Long2ObjectOpenHashMap<ChunkAnalysis> allAnalyses = new Long2ObjectOpenHashMap<>();

    private final EntityScanner entityScanner = new EntityScanner();
    private FreshnessEstimator freshnessEstimator;

    private LagDetector lagDetector;
    private final LongOpenHashSet deferredChunks = new LongOpenHashSet();
    private int skippedCount = 0;

    private double minScore = 20.0;
    private boolean detectMapArt = true;
    private boolean detectStorage = true;
    private boolean detectConstruction = true;
    private boolean detectTrails = true;
    private boolean useEntityScanning = true;
    private boolean useClusterScoring = true;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BaseFinder");
    private int debugCounter = 0;

    private static final int MAX_CHUNKS_PER_TICK = 50;
    private static final int MAX_DEFERRED_RETRIES_PER_TICK = 20;

    private int deferredRetryCounter = 0;
    private static final int DEFERRED_RETRY_INTERVAL = 10;

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
            var chunkSource = mc.level.getChunkSource();
            int renderDist = mc.options.renderDistance().get();
            int playerChunkX = mc.player.chunkPosition().x;
            int playerChunkZ = mc.player.chunkPosition().z;

            debugCounter++;
            if (debugCounter % 10 == 1) {
                LOGGER.info("[ChunkScanner] Player at chunk ({}, {}), render distance: {}", playerChunkX, playerChunkZ, renderDist);
            }

            // Retry deferred chunks périodiquement
            deferredRetryCounter++;
            if (deferredRetryCounter >= DEFERRED_RETRY_INTERVAL && !deferredChunks.isEmpty()) {
                deferredRetryCounter = 0;
                long[] toRetry = deferredChunks.toLongArray();
                int retried = 0;
                for (long deferredKey : toRetry) {
                    if (scannedChunks.contains(deferredKey)) {
                        deferredChunks.remove(deferredKey);
                        continue;
                    }
                    int dx = ChunkId.unpackX(deferredKey);
                    int dz = ChunkId.unpackZ(deferredKey);
                    LevelChunk deferredChunk = chunkSource.getChunk(dx, dz, false);
                    if (deferredChunk != null) {
                        boolean fullyLoaded = lagDetector == null || lagDetector.isChunkFullyLoaded(deferredChunk);
                        if (fullyLoaded) {
                            deferredChunks.remove(deferredKey);
                            scannedChunks.add(deferredKey);
                            ChunkAnalysis analysis = BlockAnalyzer.analyzeChunk(mc.level, deferredChunk);
                            if (useEntityScanning) {
                                entityScanner.scanEntities(analysis);
                            }
                            allAnalyses.put(deferredKey, analysis);
                            newlyScanned.add(analysis);
                            if (freshnessEstimator != null && analysis.isInteresting()) {
                                freshnessEstimator.estimateFreshness(analysis);
                            }
                            if (analysis.getScore() >= minScore && analysis.isInteresting()) {
                                if (shouldDetect(analysis.getBaseType())) {
                                    interestingChunks.add(analysis);
                                    newFinds.add(analysis);
                                    if (analysis.getBaseType() == BaseType.TRAIL) {
                                        trailChunks.add(analysis);
                                    }
                                }
                            }
                            retried++;
                            if (retried >= MAX_DEFERRED_RETRIES_PER_TICK) break;
                        }
                    }
                }
                if (retried > 0) {
                    LOGGER.info("[ChunkScanner] Retried {} deferred chunks, {} remaining", retried, deferredChunks.size());
                }
            }

            scanLoop:
            for (int x = playerChunkX - renderDist; x <= playerChunkX + renderDist; x++) {
                for (int z = playerChunkZ - renderDist; z <= playerChunkZ + renderDist; z++) {
                    try {
                        LevelChunk chunk = chunkSource.getChunk(x, z, false);
                        if (chunk == null) continue;

                        chunksFound++;
                        long key = ChunkId.pack(x, z);

                        if (scannedChunks.contains(key)) continue;

                        if (lagDetector != null && !lagDetector.isChunkFullyLoaded(chunk)) {
                            if (deferredChunks.size() < 5000) {
                                deferredChunks.add(key);
                            }
                            skippedCount++;
                            continue;
                        }

                        deferredChunks.remove(key);

                        scannedChunks.add(key);
                        chunksScanned++;

                        ChunkAnalysis analysis = BlockAnalyzer.analyzeChunk(mc.level, chunk);

                        if (useEntityScanning) {
                            entityScanner.scanEntities(analysis);
                        }

                        allAnalyses.put(key, analysis);
                        newlyScanned.add(analysis);

                        if (freshnessEstimator != null && analysis.isInteresting()) {
                            freshnessEstimator.estimateFreshness(analysis);
                        }

                        if (analysis != null && analysis.getScore() >= minScore && analysis.isInteresting()) {
                            if (!shouldDetect(analysis.getBaseType())) continue;

                            interestingChunks.add(analysis);
                            newFinds.add(analysis);
                            ChunkPos pos = chunk.getPos();
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
                                foundBasesCount++;
                            }
                        }

                        if (chunksScanned >= MAX_CHUNKS_PER_TICK) {
                            break scanLoop;
                        }
                    } catch (Exception e) {
                        LOGGER.error("[ChunkScanner] Error scanning chunk ({}, {}): {}", x, z, e.getMessage());
                    }
                }
            }

            if (useClusterScoring && !newlyScanned.isEmpty()) {
                applyClusterScoring(newlyScanned, newFinds);
            }

            if (debugCounter % 10 == 1) {
                LOGGER.info("[ChunkScanner] Found {} chunks, scanned {} new chunks, total scanned: {}",
                    chunksFound, chunksScanned, scannedChunks.size());
            }

        } catch (Exception e) {
            LOGGER.error("[ChunkScanner] Fatal error in scanLoadedChunks: {}", e.getMessage(), e);
        }

        return newFinds;
    }

    private void applyClusterScoring(List<ChunkAnalysis> newlyScanned, List<ChunkAnalysis> newFinds) {
        for (ChunkAnalysis analysis : newlyScanned) {
            if (analysis.getScore() < 5) continue;

            ChunkPos pos = analysis.getChunkPos();
            double neighborScore = 0;
            int neighborCount = 0;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long neighborKey = ChunkId.pack(pos.x + dx, pos.z + dz);
                    ChunkAnalysis neighborAnalysis = allAnalyses.get(neighborKey);
                    if (neighborAnalysis != null && neighborAnalysis.getScore() > 0) {
                        neighborScore += neighborAnalysis.getScore();
                        neighborCount++;
                    }
                }
            }

            boolean hasCentralEvidence = analysis.getPlayerBlockCount() > 0 || analysis.getStorageCount() > 0;
            if (neighborCount >= 3 && neighborScore >= 30 && hasCentralEvidence) {
                double clusterBonus = neighborScore * 0.15;
                clusterBonus = Math.min(clusterBonus, minScore * 0.5);
                analysis.setClusterScore(neighborScore);
                analysis.setClusterSize(neighborCount);
                double newScore = analysis.getScore() + clusterBonus;
                analysis.setScore(newScore);

                LOGGER.info("[ChunkScanner] Cluster detected at ({}, {}) - {} neighbors, bonus: {}, new score: {}",
                        pos.x, pos.z, neighborCount, String.format("%.1f", clusterBonus), String.format("%.1f", newScore));

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
                            foundBasesCount++;
                        }
                    }
                }
            }
        }
    }

    private boolean detectStash = true;
    private boolean detectFarm = true;
    private boolean detectPortal = true;
    private boolean detectCaveMining = true;

    private boolean shouldDetect(BaseType type) {
        return switch (type) {
            case MAP_ART -> detectMapArt;
            case STORAGE -> detectStorage;
            case CONSTRUCTION -> detectConstruction;
            case TRAIL -> detectTrails;
            case STASH -> detectStash;
            case FARM -> detectFarm;
            case PORTAL -> detectPortal;
            case CAVE_MINING -> detectCaveMining;
            case NONE -> false;
        };
    }

    private int cleanupCounter = 0;
    private static final int CLEANUP_INTERVAL = 200;
    private static final int MAX_ANALYSES_SIZE = 25000;

    public void cleanupMemory() {
        cleanupCounter++;
        if (cleanupCounter % CLEANUP_INTERVAL != 0) return;
        if (allAnalyses.size() < MAX_ANALYSES_SIZE) return;

        if (mc.player == null) return;

        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int purgeDistance = 128;

        int removed = 0;
        LongIterator it = allAnalyses.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int dx = Math.abs(ChunkId.unpackX(key) - playerChunkX);
            int dz = Math.abs(ChunkId.unpackZ(key) - playerChunkZ);
            if (dx > purgeDistance || dz > purgeDistance) {
                it.remove();
                removed++;
            }
        }

        if (removed > 0) {
            LOGGER.info("[ChunkScanner] Memory cleanup: purged {} old analyses, {} remaining",
                    removed, allAnalyses.size());
        }
    }

    public void reset() {
        scannedChunks.clear();
        interestingChunks.clear();
        foundBasesCount = 0;
        trailChunks.clear();
        allAnalyses.clear();
        deferredChunks.clear();
        skippedCount = 0;
    }

    /**
     * Restore previously scanned chunk positions from saved state.
     */
    public void restoreScannedChunks(long[] packedKeys) {
        if (packedKeys != null && packedKeys.length > 0) {
            scannedChunks.addAll(LongOpenHashSet.of(packedKeys));
            LOGGER.info("[ChunkScanner] Restored {} scanned chunks from saved state", packedKeys.length);
        }
    }

    /**
     * Snapshot des longs scannés pour le save async (copie défensive).
     */
    public long[] snapshotScannedChunks() {
        return scannedChunks.toLongArray();
    }

    public int getScannedCount() { return scannedChunks.size(); }

    /**
     * Vue read-only en {@link ChunkPos} pour les callers (NavigationHelper)
     * qui n'ont pas encore migré vers les longs. À supprimer quand ces callers
     * seront refactorés.
     */
    public Set<ChunkPos> getScannedChunksSet() {
        Set<ChunkPos> view = new HashSet<>(scannedChunks.size() * 2);
        LongIterator it = scannedChunks.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            view.add(new ChunkPos(ChunkId.unpackX(key), ChunkId.unpackZ(key)));
        }
        return Collections.unmodifiableSet(view);
    }

    public int getDeferredCount() { return deferredChunks.size(); }
    public int getSkippedCount() { return skippedCount; }
    public List<ChunkAnalysis> getInterestingChunks() { return Collections.unmodifiableList(interestingChunks); }
    public int getFoundBasesCount() { return foundBasesCount; }
    public List<ChunkAnalysis> getTrailChunks() { return Collections.unmodifiableList(trailChunks); }

    public void setMinScore(double minScore) { this.minScore = minScore; }
    public void setDetectMapArt(boolean v) { this.detectMapArt = v; }
    public void setDetectStorage(boolean v) { this.detectStorage = v; }
    public void setDetectConstruction(boolean v) { this.detectConstruction = v; }
    public void setDetectTrails(boolean v) { this.detectTrails = v; }
    public void setUseEntityScanning(boolean v) { this.useEntityScanning = v; }
    public void setUseClusterScoring(boolean v) { this.useClusterScoring = v; }
    public void setDetectStash(boolean v) { this.detectStash = v; }
    public void setDetectFarm(boolean v) { this.detectFarm = v; }
    public void setDetectPortal(boolean v) { this.detectPortal = v; }
    public void setDetectCaveMining(boolean v) { this.detectCaveMining = v; }
    public void setFreshnessEstimator(FreshnessEstimator estimator) { this.freshnessEstimator = estimator; }
    public void setLagDetector(LagDetector detector) { this.lagDetector = detector; }
}
