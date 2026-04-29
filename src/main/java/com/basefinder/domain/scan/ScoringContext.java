package com.basefinder.domain.scan;

import com.basefinder.domain.world.Dimension;

/**
 * Contexte d'environnement injecté dans la logique de scoring pure.
 *
 * Contient les facteurs qui ne sont pas dans les counts bruts mais qui modulent
 * le score final : dimension, pénalité biome, distance au spawn (multiplier).
 *
 * @param dimension        dimension du chunk scanné
 * @param biomePenalty     1.0 = aucune pénalité, &lt;1.0 = chunk dans biome de structure
 *                         (village, trial chamber, ancient city profond, etc.)
 * @param distanceFromSpawn distance euclidienne au spawn (0,0) en blocs ;
 *                         multiplier appliqué selon paliers (audit/01 §3)
 * @param minScore         seuil minimum pour qu'un score soit retenu après pénalité
 *                         (défaut 10.0 d'après BlockAnalyzer.analyzeChunk)
 */
public record ScoringContext(
        Dimension dimension,
        double biomePenalty,
        double distanceFromSpawn,
        double minScore,
        double caveAirReportThreshold
) {
    public static final double DEFAULT_MIN_SCORE = 10.0;

    public static ScoringContext of(Dimension dim, double biomePenalty, double distFromSpawn,
                                    double caveAirReportThreshold) {
        return new ScoringContext(dim, biomePenalty, distFromSpawn, DEFAULT_MIN_SCORE,
                caveAirReportThreshold);
    }
}
