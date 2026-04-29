package com.basefinder.domain.scan;

import com.basefinder.domain.scan.BaseType;

/**
 * Logique pure de classification d'un chunk en BaseType + score, à partir de
 * counts bruts ({@link ChunkCounts}) et d'un contexte ({@link ScoringContext}).
 *
 * Aucune dépendance Minecraft : testable en JUnit pur. Extrait de
 * {@code BlockAnalyzer.analyzeChunk} (audit/05-target-architecture.md §5 étape 3).
 *
 * Règles de scoring (audit/01 §3) :
 * <ul>
 *   <li>caveAir + 25×shulkers + 5×strong + 2×medium + 2×significantObsidian
 *       + 1×mapArt + 0.5×trail + 15×signsWithText
 *   <li>Bonus multi-Y : +8/bloc dans bedrock layer (Y 0-10), +6/bloc en sky (Y &gt; 200)
 *   <li>Pénalités cumulatives : signature village/trial chamber → ×0.05 à ×0.3
 *   <li>Pénalité ancient city OU biome (jamais les deux), modulée par shulkerCount
 *   <li>Multiplier distance au spawn : 0.5× &lt; 500 blocs, 1.5× &gt; 200 000
 *   <li>Plancher : si shulker présent, score ≥ 25 quoi qu'il arrive
 * </ul>
 */
public final class ChunkClassifier {

    private static final double OBSIDIAN_SIGNIFICANT_THRESHOLD = 5;
    private static final double OBSIDIAN_PORTAL_THRESHOLD = 10;
    private static final int VILLAGE_PENALTY_SOFT_THRESHOLD = 3;
    private static final int ANCIENT_CITY_HEAVY = 30;
    private static final int ANCIENT_CITY_MEDIUM = 15;
    private static final int ANCIENT_CITY_LIGHT = 5;
    private static final int MAP_ART_THRESHOLD = 50;
    private static final int TRAIL_THRESHOLD = 15;
    private static final int CONSTRUCTION_THRESHOLD = 5;
    private static final int STORAGE_DUAL_THRESHOLD = 3;
    private static final double SHULKER_FLOOR_SCORE = 25.0;

    private ChunkClassifier() {
        // utility class
    }

    public static ScoringResult classify(ChunkCounts c, ScoringContext ctx) {
        BaseType baseType = pickBaseType(c);

        // CAVE_MINING uniquement si rien d'autre n'a déclenché un type plus prioritaire
        if (c.caveAirScore() >= ctx.caveAirReportThreshold() && baseType == BaseType.NONE) {
            baseType = BaseType.CAVE_MINING;
        }

        double score = computeBaseScore(c);
        score += multiYBonus(c);

        score = applyStructureSignaturePenalty(score, c);
        if (score < ctx.minScore() && hasStructureSignature(c) && c.shulkerCount() == 0) {
            baseType = BaseType.NONE;
        }

        score = applyAncientCityOrBiomePenalty(score, c, ctx);
        if (score < ctx.minScore() && c.ancientCityBlockCount() >= ANCIENT_CITY_LIGHT && c.shulkerCount() == 0) {
            baseType = BaseType.NONE;
        }

        score *= spawnDistanceMultiplier(ctx.distanceFromSpawn());

        if (c.shulkerCount() >= 1) {
            score = Math.max(score, SHULKER_FLOOR_SCORE);
        }

        return new ScoringResult(baseType, score);
    }

    static BaseType pickBaseType(ChunkCounts c) {
        if (c.mapArtBlockCount() > MAP_ART_THRESHOLD) {
            return BaseType.MAP_ART;
        }
        if (c.shulkerCount() >= 1 && c.strongBlockCount() < CONSTRUCTION_THRESHOLD) {
            return BaseType.STASH;
        }
        if (c.shulkerCount() >= 1) {
            return BaseType.STORAGE;
        }
        if (c.obsidianCount() >= OBSIDIAN_PORTAL_THRESHOLD) {
            return BaseType.PORTAL;
        }
        if (c.strongBlockCount() >= STORAGE_DUAL_THRESHOLD && c.storageCount() >= STORAGE_DUAL_THRESHOLD) {
            return BaseType.STORAGE;
        }
        if (c.strongBlockCount() >= CONSTRUCTION_THRESHOLD) {
            return BaseType.CONSTRUCTION;
        }
        if (c.trailBlockCount() >= TRAIL_THRESHOLD) {
            return BaseType.TRAIL;
        }
        return BaseType.NONE;
    }

    private static double computeBaseScore(ChunkCounts c) {
        double significantObsidian = c.obsidianCount() > OBSIDIAN_SIGNIFICANT_THRESHOLD ? c.obsidianCount() : 0;
        return c.caveAirScore()
                + c.shulkerCount() * 25.0
                + c.strongBlockCount() * 5.0
                + c.mediumBlockCount() * 2.0
                + significantObsidian * 2.0
                + c.mapArtBlockCount() * 1.0
                + c.trailBlockCount() * 0.5
                + c.signWithTextCount() * 15.0;
    }

    private static double multiYBonus(ChunkCounts c) {
        double bonus = 0;
        if (c.bedrockLayerBlocks() >= 1) {
            bonus += c.bedrockLayerBlocks() * 8.0;
        }
        if (c.skyLayerBlocks() >= 1) {
            bonus += c.skyLayerBlocks() * 6.0;
        }
        return bonus;
    }

    private static double applyStructureSignaturePenalty(double score, ChunkCounts c) {
        double result = score;
        if (c.villageSignatureCount() >= 1 && c.shulkerCount() == 0) {
            double villagePenalty = c.strongBlockCount() >= VILLAGE_PENALTY_SOFT_THRESHOLD ? 0.3 : 0.05;
            result *= villagePenalty;
        }
        if (c.trialChamberSignatureCount() >= 1 && c.shulkerCount() == 0) {
            result *= 0.05;
        }
        return result;
    }

    private static boolean hasStructureSignature(ChunkCounts c) {
        return c.villageSignatureCount() >= 1 || c.trialChamberSignatureCount() >= 1;
    }

    private static double applyAncientCityOrBiomePenalty(double score, ChunkCounts c, ScoringContext ctx) {
        if (c.ancientCityBlockCount() >= ANCIENT_CITY_LIGHT && c.shulkerCount() == 0) {
            double penalty;
            if (c.ancientCityBlockCount() >= ANCIENT_CITY_HEAVY) {
                penalty = 0.05;
            } else if (c.ancientCityBlockCount() >= ANCIENT_CITY_MEDIUM) {
                penalty = 0.15;
            } else {
                penalty = 0.3;
            }
            return score * penalty;
        }
        if (c.shulkerCount() == 0 && ctx.biomePenalty() < 1.0) {
            double shulkerScore = c.shulkerCount() * 25.0;
            double otherScore = score - shulkerScore;
            return shulkerScore + otherScore * ctx.biomePenalty();
        }
        return score;
    }

    public static double spawnDistanceMultiplier(double distFromSpawn) {
        if (distFromSpawn < 500) {
            return 0.5;
        }
        if (distFromSpawn < 2000) {
            return 0.7;
        }
        if (distFromSpawn < 10000) {
            return 0.8;
        }
        if (distFromSpawn < 50000) {
            return 1.0;
        }
        if (distFromSpawn < 200000) {
            return 1.3;
        }
        return 1.5;
    }
}
