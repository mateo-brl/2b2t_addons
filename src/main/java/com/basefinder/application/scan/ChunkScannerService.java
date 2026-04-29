package com.basefinder.application.scan;

import com.basefinder.domain.scan.ChunkClassifier;
import com.basefinder.domain.scan.ChunkCounts;
import com.basefinder.domain.scan.ScoringContext;
import com.basefinder.domain.scan.ScoringResult;
import com.basefinder.domain.world.ChunkId;

import java.util.Optional;

/**
 * Use case d'analyse d'un chunk : récupère les comptes via le port
 * {@link ChunkSource} puis applique la logique pure {@link ChunkClassifier}.
 *
 * Pas d'import Minecraft, pas d'import RusherHack. Testable en JUnit avec un
 * fake ChunkSource. Audit/05 §5 étape 3.
 */
public final class ChunkScannerService {

    private final ChunkSource source;

    public ChunkScannerService(ChunkSource source) {
        this.source = source;
    }

    /**
     * Analyse un chunk si la source peut le fournir, sinon retourne
     * {@link ScoringResult#NONE}.
     *
     * @param id  identifiant du chunk à analyser
     * @param ctx contexte de scoring (dimension, biome penalty, dist spawn,
     *            seuil cave air)
     */
    public ScoringResult scan(ChunkId id, ScoringContext ctx) {
        Optional<ChunkCounts> counts = source.countsFor(id);
        return counts.map(c -> ChunkClassifier.classify(c, ctx))
                .orElse(ScoringResult.NONE);
    }

    public ChunkSource source() {
        return source;
    }
}
