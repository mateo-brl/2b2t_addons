package com.basefinder.application.scan;

import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.scan.ChunkCounts;
import com.basefinder.domain.scan.ScoringContext;
import com.basefinder.domain.scan.ScoringResult;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests d'orchestration de {@link ChunkScannerService} avec un fake
 * {@link ChunkSource} en mémoire — preuve que le pipeline scan→classify est
 * pilotable hors Minecraft.
 */
class ChunkScannerServiceTest {

    private static final double NO_BIOME = 1.0;
    private static final double NEAR_SPAWN = 100.0;
    private static final double CAVE_THRESHOLD = 50.0;

    private ScoringContext ctx() {
        return ScoringContext.of(Dimension.OVERWORLD, NO_BIOME, NEAR_SPAWN, CAVE_THRESHOLD);
    }

    @Test
    void emptySource_returnsNoneResult() {
        ChunkSource empty = id -> Optional.empty();
        ChunkScannerService svc = new ChunkScannerService(empty);

        ScoringResult r = svc.scan(new ChunkId(0, 0, Dimension.OVERWORLD), ctx());

        assertSame(ScoringResult.NONE, r);
        assertEquals(BaseType.NONE, r.baseType());
        assertEquals(0.0, r.score());
    }

    @Test
    void shulkerBaseFromMockSource_classifiesAsStorage() {
        ChunkId target = new ChunkId(100, 200, Dimension.OVERWORLD);
        Map<ChunkId, ChunkCounts> data = new HashMap<>();
        data.put(target, ChunkCounts.builder()
                .shulkerCount(10)
                .strongBlockCount(10) // STORAGE (>= 5)
                .build());

        ChunkSource source = id -> Optional.ofNullable(data.get(id));
        ChunkScannerService svc = new ChunkScannerService(source);

        ScoringResult r = svc.scan(target, ctx());

        assertEquals(BaseType.STORAGE, r.baseType());
        // 10*25 + 10*5 = 300, ×0.5 (<500) = 150
        assertEquals(150.0, r.score(), 0.001);
    }

    @Test
    void unrelatedChunkId_returnsNone() {
        ChunkId requested = new ChunkId(100, 200, Dimension.OVERWORLD);
        ChunkId different = new ChunkId(300, 400, Dimension.OVERWORLD);
        Map<ChunkId, ChunkCounts> data = Map.of(different,
                ChunkCounts.builder().shulkerCount(5).build());

        ChunkSource source = id -> Optional.ofNullable(data.get(id));
        ChunkScannerService svc = new ChunkScannerService(source);

        ScoringResult r = svc.scan(requested, ctx());

        assertEquals(BaseType.NONE, r.baseType());
    }

    @Test
    void biomePenaltyFromContext_isAppliedByClassifier() {
        ChunkId target = new ChunkId(0, 0, Dimension.OVERWORLD);
        ChunkCounts counts = ChunkCounts.builder()
                .strongBlockCount(10) // 50 score
                .villageSignatureCount(1) // village → ×0.05 (strong<3 path) ou ×0.3 (strong>=3)
                .build();

        ChunkSource source = id -> Optional.of(counts);
        ChunkScannerService svc = new ChunkScannerService(source);
        ScoringContext heavyPenalty = new ScoringContext(
                Dimension.OVERWORLD, 0.1, 100_000.0, ScoringContext.DEFAULT_MIN_SCORE,
                CAVE_THRESHOLD);

        ScoringResult r = svc.scan(target, heavyPenalty);

        // score = 10*5 = 50 → ×0.3 (village strong>=3) = 15 → ×1.3 (>50k) = 19.5
        // baseType = CONSTRUCTION (10 strong >= 5)
        assertEquals(BaseType.CONSTRUCTION, r.baseType());
        assertTrue(r.score() < 25.0);
    }

    @Test
    void serviceExposesUnderlyingSource() {
        ChunkSource source = id -> Optional.empty();
        ChunkScannerService svc = new ChunkScannerService(source);

        assertSame(source, svc.source());
    }

    @Test
    void caveAirCounts_promoteToCaveMining() {
        ChunkId target = new ChunkId(0, 0, Dimension.OVERWORLD);
        ChunkCounts counts = ChunkCounts.builder().caveAirScore(75.0).build();

        ChunkSource source = id -> Optional.of(counts);
        ChunkScannerService svc = new ChunkScannerService(source);

        ScoringResult r = svc.scan(target, ctx());

        assertEquals(BaseType.CAVE_MINING, r.baseType());
        // 75 × 0.5 (<500) = 37.5
        assertEquals(37.5, r.score(), 0.001);
    }
}
