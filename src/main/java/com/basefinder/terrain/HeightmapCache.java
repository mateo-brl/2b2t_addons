package com.basefinder.terrain;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches observed surface heights from loaded chunks.
 * Provides exact heights for visited areas and interpolation for nearby unknown areas.
 */
public class HeightmapCache {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("HeightmapCache");
    private final Minecraft mc = Minecraft.getInstance();

    // Key: packed chunk coords (chunkX, chunkZ) -> max surface Y in that chunk
    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();

    private static final int MAX_CACHE_SIZE = 100000;

    /**
     * Record the max surface height of a chunk when it's loaded.
     */
    public void recordChunkHeight(LevelChunk chunk) {
        if (chunk == null || mc.level == null) return;

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int maxY = 0;
        // Sample 5 columns: 4 corners + center
        int[][] samples = {{0, 0}, {15, 0}, {0, 15}, {15, 15}, {8, 8}};
        for (int[] sample : samples) {
            int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, minX + sample[0], minZ + sample[1]);
            maxY = Math.max(maxY, y);
        }

        long key = packChunkPos(chunk.getPos().x, chunk.getPos().z);
        heightCache.put(key, maxY);

        // Prevent unbounded growth
        if (heightCache.size() > MAX_CACHE_SIZE) {
            cleanup();
        }
    }

    /**
     * Get the cached height at a block position (chunk-level resolution).
     */
    public OptionalInt getHeight(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        long key = packChunkPos(chunkX, chunkZ);
        Integer height = heightCache.get(key);
        return height != null ? OptionalInt.of(height) : OptionalInt.empty();
    }

    /**
     * Interpolate height from nearby cached chunks for unknown positions.
     * Uses inverse-distance weighted average of the 4 nearest cached neighbors.
     */
    public OptionalInt interpolateHeight(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        double weightedSum = 0;
        double totalWeight = 0;
        int found = 0;

        // Search in a 5-chunk radius for nearby cached heights
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                long key = packChunkPos(chunkX + dx, chunkZ + dz);
                Integer height = heightCache.get(key);
                if (height != null) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist < 0.5) dist = 0.5;
                    double weight = 1.0 / (dist * dist);
                    weightedSum += height * weight;
                    totalWeight += weight;
                    found++;
                    if (found >= 8) break; // Enough samples
                }
            }
            if (found >= 8) break;
        }

        if (found >= 2 && totalWeight > 0) {
            return OptionalInt.of((int) Math.ceil(weightedSum / totalWeight));
        }
        return OptionalInt.empty();
    }

    /**
     * Get the maximum height in a corridor ahead of the player.
     *
     * @param startX  corridor start X
     * @param startZ  corridor start Z
     * @param dirX    normalized direction X
     * @param dirZ    normalized direction Z
     * @param distance corridor length in blocks
     * @return max height found, or -1 if no data
     */
    public int getMaxHeightInCorridor(double startX, double startZ, double dirX, double dirZ, int distance) {
        int maxH = -1;
        for (int d = 0; d < distance; d += 16) {
            int px = (int) (startX + dirX * d);
            int pz = (int) (startZ + dirZ * d);
            OptionalInt h = getHeight(px, pz);
            if (h.isPresent()) {
                maxH = Math.max(maxH, h.getAsInt());
            } else {
                OptionalInt interp = interpolateHeight(px, pz);
                if (interp.isPresent()) {
                    maxH = Math.max(maxH, interp.getAsInt());
                }
            }
        }
        return maxH;
    }

    /**
     * Remove entries far from the player to limit memory usage.
     */
    private void cleanup() {
        if (mc.player == null) return;

        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int purgeDistance = 256; // chunks

        heightCache.entrySet().removeIf(entry -> {
            long key = entry.getKey();
            int cx = (int) (key >> 32);
            int cz = (int) key;
            return Math.abs(cx - playerChunkX) > purgeDistance || Math.abs(cz - playerChunkZ) > purgeDistance;
        });

        LOGGER.info("[HeightmapCache] Cleanup: {} entries remaining", heightCache.size());
    }

    public int getCacheSize() {
        return heightCache.size();
    }

    private static long packChunkPos(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
