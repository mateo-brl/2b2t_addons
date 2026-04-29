package com.basefinder.application.scan;

import com.basefinder.domain.scan.ChunkCounts;
import com.basefinder.domain.world.ChunkId;

import java.util.Optional;

/**
 * Port d'accès aux chunks à analyser.
 *
 * L'implémentation MC ({@code McChunkSource}) extrait les comptes depuis un
 * LevelChunk vivant. L'implémentation distante (replay backend) lira les
 * comptes pré-agrégés depuis NDJSON. Les tests injectent un fake.
 *
 * Audit/05 §5 étape 3 : "ChunkSource mockable. Fixe BUG-004 (snapshot)."
 */
public interface ChunkSource {

    /**
     * Retourne les comptes pour un chunk donné, ou {@code Optional.empty()} si
     * indisponible (chunk non chargé, dimension non couverte, hors zone).
     *
     * Implémentation côté MC : non-bloquant, ne déclenche jamais le chargement
     * d'un chunk distant — retourne empty si le chunk n'est pas déjà résident.
     */
    Optional<ChunkCounts> countsFor(ChunkId id);
}
