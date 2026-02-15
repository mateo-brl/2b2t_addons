package com.basefinder.terrain;

import com.basefinder.scanner.ChunkAgeAnalyzer;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalInt;

/**
 * Combines HeightmapCache (exact observed data) and SeedTerrainGenerator (seed prediction)
 * to provide the best possible terrain height estimate at any position.
 *
 * Priority: cache exact > cache interpolated > seed prediction.
 */
public class TerrainPredictor {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("TerrainPredictor");

    private final HeightmapCache cache;
    private final SeedTerrainGenerator seedGen;
    private final ChunkAgeAnalyzer ageAnalyzer;

    // Track which source was used for last prediction (for HUD)
    private String lastSource = "none";
    private ChunkAgeAnalyzer.ChunkGeneration lastGenType = ChunkAgeAnalyzer.ChunkGeneration.UNKNOWN;

    public TerrainPredictor(HeightmapCache cache, SeedTerrainGenerator seedGen, ChunkAgeAnalyzer ageAnalyzer) {
        this.cache = cache;
        this.seedGen = seedGen;
        this.ageAnalyzer = ageAnalyzer;
    }

    /**
     * Predict the surface height at a position using the best available source.
     *
     * @param blockX world X
     * @param blockZ world Z
     * @return estimated surface Y
     */
    public int predictHeight(int blockX, int blockZ) {
        // 1. Check exact cache
        OptionalInt cached = cache.getHeight(blockX, blockZ);
        if (cached.isPresent()) {
            lastSource = "cache";
            return cached.getAsInt();
        }

        // 2. Try interpolation from nearby cached data
        OptionalInt interpolated = cache.interpolateHeight(blockX, blockZ);
        if (interpolated.isPresent()) {
            lastSource = "interpolated";
            return interpolated.getAsInt();
        }

        // 3. Fallback: seed-based prediction
        ChunkAgeAnalyzer.ChunkGeneration gen = determineGeneration(blockX, blockZ);
        lastGenType = gen;
        lastSource = "seed(" + gen.name() + ")";
        return seedGen.predictSurfaceHeight(blockX, blockZ, gen);
    }

    /**
     * Get the maximum terrain height along a flight corridor.
     *
     * @param position current player position
     * @param direction normalized movement direction
     * @param distance how far ahead to check (blocks)
     * @return max predicted height in the corridor
     */
    public int getMaxHeightAhead(Vec3 position, Vec3 direction, int distance) {
        double hSpeed = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (hSpeed < 0.001) return 64; // Not moving

        double nx = direction.x / hSpeed;
        double nz = direction.z / hSpeed;

        int maxH = 0;
        for (int d = 0; d < distance; d += 16) {
            int px = (int) (position.x + nx * d);
            int pz = (int) (position.z + nz * d);
            int h = predictHeight(px, pz);
            maxH = Math.max(maxH, h);
        }
        return maxH;
    }

    /**
     * Determine whether a position is likely pre-1.18 or post-1.18 terrain.
     * Uses distance from spawn as heuristic: chunks near spawn are more likely old.
     * If auto-switch is disabled, defaults to UNKNOWN (seed gen will use new gen).
     */
    private ChunkAgeAnalyzer.ChunkGeneration determineGeneration(int blockX, int blockZ) {
        // On 2b2t, chunks within ~3M of spawn are generally pre-1.18
        // Chunks further out are more likely to be post-1.18
        double distFromSpawn = Math.sqrt((double) blockX * blockX + (double) blockZ * blockZ);

        if (distFromSpawn < 500000) {
            // Close to spawn - very likely old chunks
            return ChunkAgeAnalyzer.ChunkGeneration.PRE_1_18;
        } else if (distFromSpawn < 3000000) {
            // Medium distance - could be either, assume old for safety (higher terrain)
            return ChunkAgeAnalyzer.ChunkGeneration.PRE_1_18;
        } else {
            // Far from spawn - likely new generation
            return ChunkAgeAnalyzer.ChunkGeneration.POST_1_18;
        }
    }

    // HUD accessors
    public String getLastSource() { return lastSource; }
    public ChunkAgeAnalyzer.ChunkGeneration getLastGenType() { return lastGenType; }
    public HeightmapCache getCache() { return cache; }
}
