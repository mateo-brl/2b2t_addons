package com.basefinder.domain.scan;

import com.basefinder.domain.world.Dimension;
import com.basefinder.util.BaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests purs (sans Minecraft) de la logique de scoring extraite de BlockAnalyzer.
 *
 * Audit/05 §5 étape 3 : valider que la logique métier de classification d'un chunk
 * est testable indépendamment du client MC.
 */
class ChunkClassifierTest {

    private static final double EPSILON = 0.01;
    private static final double NO_CAVE = 0.0;
    private static final double LOW_BIOME_PENALTY = 0.1;
    private static final double NO_BIOME_PENALTY = 1.0;
    private static final double FAR_FROM_SPAWN = 100_000.0;
    private static final double NEAR_SPAWN = 100.0;
    private static final double CAVE_REPORT_THRESHOLD = 50.0;

    private ScoringContext defaultContext() {
        return ScoringContext.of(Dimension.OVERWORLD, NO_BIOME_PENALTY, FAR_FROM_SPAWN, CAVE_REPORT_THRESHOLD);
    }

    @Test
    void shulkerCluster_marksAsStashWithFloorScore() {
        // 5 shulkers, peu de blocs forts → STASH (pas STORAGE)
        ChunkCounts counts = ChunkCounts.builder()
                .shulkerCount(5)
                .strongBlockCount(2) // < 5 = STASH
                .build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        assertEquals(BaseType.STASH, r.baseType());
        // 5 shulkers × 25 + 2 strong × 5 = 125 + 10 = 135, × 1.3 (>50k) = 175.5
        assertTrue(r.score() >= 25.0, "Plancher shulker garanti");
        assertEquals(135.0 * 1.3, r.score(), EPSILON);
    }

    @Test
    void wildOres_areIgnored_returnsNoneAtZero() {
        // Aucun bloc significatif (minerais naturels filtrés en amont par BlockAnalyzer)
        ChunkCounts counts = ChunkCounts.builder().build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        assertEquals(BaseType.NONE, r.baseType());
        assertEquals(0.0, r.score(), EPSILON);
    }

    @Test
    void villageSignature_withoutShulker_zeroesScoreAndType() {
        // Village (1 bell, des storage et strong blocks) → forte pénalité, baseType remis à NONE
        ChunkCounts counts = ChunkCounts.builder()
                .strongBlockCount(2) // < 3 → villagePenalty = 0.05
                .storageCount(2)
                .villageSignatureCount(1)
                .build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        // strong=2 → pickBaseType = NONE (car < 5 et < trail threshold)
        assertEquals(BaseType.NONE, r.baseType());
        // score = 2*5 = 10 → ×0.05 = 0.5 → ×1.3 = 0.65, sous minScore
        assertTrue(r.score() < 1.0, "Village squashé près de zéro, score=" + r.score());
    }

    @Test
    void trialChamber_keepsShulkerAsStash() {
        // Trial chamber + 1 shulker → on garde STASH (les shulkers protègent)
        ChunkCounts counts = ChunkCounts.builder()
                .shulkerCount(1)
                .trialChamberSignatureCount(1)
                .build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        assertEquals(BaseType.STASH, r.baseType(), "Shulker présent → STASH même en trial chamber");
        assertTrue(r.score() >= 25.0, "Plancher shulker préservé malgré trial chamber");
    }

    @Test
    void portalDetection_obsidianTriggersPortal() {
        // 12 obsidienne, rien d'autre → PORTAL
        ChunkCounts counts = ChunkCounts.builder()
                .obsidianCount(12)
                .build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        assertEquals(BaseType.PORTAL, r.baseType());
        // significantObsidian = 12 (>5) → 12*2 = 24, ×1.3 (>50k) = 31.2
        assertEquals(24.0 * 1.3, r.score(), EPSILON);
    }

    @Test
    void caveAirAboveThreshold_promotesToCaveMining() {
        ChunkCounts counts = ChunkCounts.builder()
                .caveAirScore(60.0) // > seuil 50
                .build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        assertEquals(BaseType.CAVE_MINING, r.baseType());
        assertEquals(60.0 * 1.3, r.score(), EPSILON);
    }

    @Test
    void spawnDistanceMultiplier_appliesProperBands() {
        assertEquals(0.5, ChunkClassifier.spawnDistanceMultiplier(100), EPSILON);
        assertEquals(0.7, ChunkClassifier.spawnDistanceMultiplier(1500), EPSILON);
        assertEquals(0.8, ChunkClassifier.spawnDistanceMultiplier(8000), EPSILON);
        assertEquals(1.0, ChunkClassifier.spawnDistanceMultiplier(40_000), EPSILON);
        assertEquals(1.3, ChunkClassifier.spawnDistanceMultiplier(150_000), EPSILON);
        assertEquals(1.5, ChunkClassifier.spawnDistanceMultiplier(500_000), EPSILON);
    }

    @Test
    void ancientCityHeavyPenalty_appliesWhenManyDeepDarkBlocks() {
        // 35 blocs deep dark, pas de shulker → pénalité 0.05
        ChunkCounts counts = ChunkCounts.builder()
                .strongBlockCount(10) // base score 50
                .ancientCityBlockCount(35)
                .build();

        ScoringResult r = ChunkClassifier.classify(counts, defaultContext());

        // baseType = CONSTRUCTION (10 strong >= 5)
        // score = 10*5 = 50 → ×0.05 = 2.5 → ×1.3 = 3.25 → < minScore donc baseType = NONE
        assertEquals(BaseType.NONE, r.baseType());
        assertTrue(r.score() < 5.0);
    }
}
