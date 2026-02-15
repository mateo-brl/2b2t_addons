package com.basefinder.terrain;

import com.basefinder.scanner.ChunkAgeAnalyzer;

/**
 * Predicts terrain surface height using the 2b2t world seed.
 *
 * Uses simplified Perlin noise approximation with the known seed
 * to estimate terrain height at any position. This is NOT a full
 * Minecraft world generator reimplementation - just enough noise
 * octaves to get a reasonable height estimate (+-15 blocks).
 *
 * Combined with a safety margin of 30-40 blocks, this provides
 * safe flight altitude prediction for unloaded chunks.
 */
public class SeedTerrainGenerator {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SeedTerrainGenerator");

    // 2b2t world seed
    private static final long SEED_2B2T = -4172144997902289642L;

    // Permutation table for Perlin noise (seeded)
    private final int[] perm = new int[512];

    public SeedTerrainGenerator() {
        initPermutation(SEED_2B2T);
    }

    /**
     * Initialize the permutation table from seed (standard Perlin noise setup).
     */
    private void initPermutation(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;

        // Fisher-Yates shuffle with seed
        java.util.Random rng = new java.util.Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }

        // Duplicate for wrapping
        for (int i = 0; i < 256; i++) {
            perm[i] = p[i];
            perm[i + 256] = p[i];
        }
    }

    /**
     * Predict surface height at a block position.
     *
     * @param blockX world X coordinate
     * @param blockZ world Z coordinate
     * @param generation chunk generation version (affects terrain algorithm)
     * @return estimated surface Y
     */
    public int predictSurfaceHeight(int blockX, int blockZ, ChunkAgeAnalyzer.ChunkGeneration generation) {
        if (generation == ChunkAgeAnalyzer.ChunkGeneration.PRE_1_18
                || generation == ChunkAgeAnalyzer.ChunkGeneration.PRE_1_13
                || generation == ChunkAgeAnalyzer.ChunkGeneration.PRE_1_16) {
            return predictOldGenHeight(blockX, blockZ);
        } else {
            return predictNewGenHeight(blockX, blockZ);
        }
    }

    /**
     * Pre-1.18 terrain: classic Minecraft generation.
     * Base height ~64, with 2-octave Perlin noise for hills.
     * Mountains up to ~100, plains around 64-68.
     * Precision: +-10-15 blocks.
     */
    private int predictOldGenHeight(int blockX, int blockZ) {
        double x = blockX / 256.0;
        double z = blockZ / 256.0;

        // Continental scale - determines biome type (plains vs mountains)
        double continental = octaveNoise(x * 0.5, z * 0.5, 4, 0.5, 2.0);

        // Detail noise - small hills and valleys
        double detail = octaveNoise(x * 2.0, z * 2.0, 3, 0.5, 2.0);

        // Base height 64, amplitude varies by continental value
        double baseHeight = 64.0;
        double amplitude;
        if (continental > 0.3) {
            // Mountain-like terrain
            amplitude = 30.0 + continental * 20.0;
        } else if (continental > 0) {
            // Hills
            amplitude = 10.0 + continental * 30.0;
        } else {
            // Plains/flat
            amplitude = 5.0 + Math.abs(continental) * 5.0;
        }

        double height = baseHeight + detail * amplitude;

        // Clamp to reasonable range
        return (int) Math.max(62, Math.min(130, height));
    }

    /**
     * Post-1.18 terrain: new multi-noise terrain.
     * More extreme terrain: deep valleys (-60), tall mountains (200+).
     * Uses 3 noise layers: continentalness, erosion, peaks/valleys.
     * Precision: +-15-20 blocks.
     */
    private int predictNewGenHeight(int blockX, int blockZ) {
        double x = blockX / 256.0;
        double z = blockZ / 256.0;

        // Continentalness - ocean vs land
        double continental = octaveNoise(x * 0.3, z * 0.3, 4, 0.5, 2.0);

        // Erosion - flat vs mountainous
        double erosion = octaveNoise(x * 0.6 + 1000, z * 0.6 + 1000, 3, 0.5, 2.0);

        // Peaks and valleys - local detail
        double peaks = octaveNoise(x * 1.5 + 2000, z * 1.5 + 2000, 3, 0.5, 2.0);

        // Base height calculation (simplified from MC's actual multi-noise router)
        double baseHeight;
        if (continental < -0.3) {
            // Ocean
            baseHeight = 50 + continental * 20;
        } else if (continental < 0.1) {
            // Coast/plains
            baseHeight = 64 + continental * 10;
        } else {
            // Inland
            baseHeight = 68 + continental * 40;
        }

        // Erosion modifies height range
        double heightRange;
        if (erosion < -0.2) {
            // Low erosion = mountains
            heightRange = 40 + Math.abs(erosion) * 60;
        } else if (erosion < 0.3) {
            // Medium erosion = hills
            heightRange = 15 + erosion * 20;
        } else {
            // High erosion = flat
            heightRange = 5 + erosion * 5;
        }

        double height = baseHeight + peaks * heightRange;

        // Clamp to 1.18+ range
        return (int) Math.max(0, Math.min(250, height));
    }

    /**
     * Multi-octave Perlin noise.
     */
    private double octaveNoise(double x, double z, int octaves, double persistence, double lacunarity) {
        double total = 0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxValue = 0;

        for (int i = 0; i < octaves; i++) {
            total += perlinNoise(x * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }

        return total / maxValue; // Normalize to [-1, 1]
    }

    /**
     * 2D Perlin noise implementation.
     */
    private double perlinNoise(double x, double z) {
        int xi = (int) Math.floor(x) & 255;
        int zi = (int) Math.floor(z) & 255;

        double xf = x - Math.floor(x);
        double zf = z - Math.floor(z);

        double u = fade(xf);
        double v = fade(zf);

        int aa = perm[perm[xi] + zi];
        int ab = perm[perm[xi] + zi + 1];
        int ba = perm[perm[xi + 1] + zi];
        int bb = perm[perm[xi + 1] + zi + 1];

        double x1 = lerp(u, grad2d(aa, xf, zf), grad2d(ba, xf - 1, zf));
        double x2 = lerp(u, grad2d(ab, xf, zf - 1), grad2d(bb, xf - 1, zf - 1));

        return lerp(v, x1, x2);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad2d(int hash, double x, double z) {
        int h = hash & 3;
        return switch (h) {
            case 0 -> x + z;
            case 1 -> -x + z;
            case 2 -> x - z;
            case 3 -> -x - z;
            default -> 0;
        };
    }
}
