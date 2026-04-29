package com.basefinder.domain.scan;

import com.basefinder.util.BaseType;

/**
 * Résultat du scoring pur d'un chunk : type de base détecté + score numérique.
 *
 * Pas de dépendance MC. Construit par {@link ChunkClassifier#classify}.
 */
public record ScoringResult(BaseType baseType, double score) {

    public static final ScoringResult NONE = new ScoringResult(BaseType.NONE, 0.0);

    public boolean isInteresting() {
        return baseType != BaseType.NONE;
    }
}
