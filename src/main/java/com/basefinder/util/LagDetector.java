package com.basefinder.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Monitors server TPS and lag on 2b2t.
 *
 * 2b2t runs at variable TPS (often 10-15 instead of 20).
 * This affects:
 * - Chunk loading speed (chunks ahead may not be loaded)
 * - Inventory interactions (placement, breaking delays)
 * - Entity spawning and updates
 *
 * Uses tick timing to estimate TPS and provides lag multipliers
 * for other systems to adapt their timeouts.
 */
public class LagDetector {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("LagDetector");
    private final Minecraft mc = Minecraft.getInstance();

    // TPS estimation via client tick timing
    private final long[] tickTimes = new long[100]; // Last 100 ticks
    private int tickIndex = 0;
    private long lastTickTime = 0;
    private boolean initialized = false;

    // Chunk loading monitoring
    private int unloadedChunksAhead = 0;
    private int lastChunkLoadCheck = 0;

    // Derived values
    private double estimatedTPS = 20.0;
    private double lagMultiplier = 1.0; // 1.0 = no lag, 2.0 = double timeouts

    /**
     * Call every tick to update TPS estimation.
     */
    public void tick() {
        long now = System.currentTimeMillis();

        if (!initialized) {
            lastTickTime = now;
            initialized = true;
            return;
        }

        long delta = now - lastTickTime;
        lastTickTime = now;

        // Store tick delta
        tickTimes[tickIndex] = delta;
        tickIndex = (tickIndex + 1) % tickTimes.length;

        // Calculate TPS every 20 ticks
        if (tickIndex % 20 == 0) {
            updateTPS();
        }

        // Check chunk loading ahead every second
        lastChunkLoadCheck++;
        if (lastChunkLoadCheck >= 20) {
            lastChunkLoadCheck = 0;
            checkChunkLoadingAhead();
        }
    }

    /**
     * Calculate estimated TPS from tick timing data.
     * Perfect TPS = 20 (50ms per tick). Lower TPS means lag.
     */
    private void updateTPS() {
        long totalDelta = 0;
        int count = 0;

        for (long delta : tickTimes) {
            if (delta > 0 && delta < 5000) { // Ignore outliers (>5s = probably paused)
                totalDelta += delta;
                count++;
            }
        }

        if (count < 20) return; // Not enough data

        double avgTickMs = (double) totalDelta / count;
        // TPS = 1000ms / avgTickMs, capped at 20
        estimatedTPS = Math.min(20.0, 1000.0 / avgTickMs);

        // Lag multiplier: at 20 TPS = 1.0, at 10 TPS = 2.0, at 5 TPS = 4.0
        lagMultiplier = 20.0 / Math.max(1.0, estimatedTPS);

        // Clamp to reasonable range
        lagMultiplier = Math.min(lagMultiplier, 5.0);
    }

    /**
     * Check how many chunks ahead of the player's movement direction are not loaded.
     * On 2b2t, when flying fast, you can outrun chunk loading.
     */
    private void checkChunkLoadingAhead() {
        if (mc.player == null || mc.level == null) return;

        var velocity = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (hSpeed < 0.5) {
            unloadedChunksAhead = 0;
            return;
        }

        // Direction of movement
        double nx = velocity.x / hSpeed;
        double nz = velocity.z / hSpeed;

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        int unloaded = 0;
        var chunkSource = mc.level.getChunkSource();

        // Check 4 chunks ahead (64-256 blocks depending on speed)
        for (int i = 1; i <= 4; i++) {
            double checkDist = i * 48; // ~3 chunks apart
            double checkX = px + nx * checkDist;
            double checkZ = pz + nz * checkDist;
            int chunkX = (int) Math.floor(checkX) >> 4;
            int chunkZ = (int) Math.floor(checkZ) >> 4;

            LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                unloaded++;
            }
        }

        unloadedChunksAhead = unloaded;

        if (unloaded >= 2) {
            LOGGER.info("[LagDetector] {} unloaded chunks ahead (TPS: {})",
                    unloaded, String.format("%.1f", estimatedTPS));
        }
    }

    /**
     * Check if a specific chunk is fully loaded and ready to scan.
     * On 2b2t, chunks can be partially loaded with missing sections.
     */
    public boolean isChunkFullyLoaded(LevelChunk chunk) {
        if (chunk == null) return false;

        // Check that the chunk has sections (not empty/placeholder)
        var sections = chunk.getSections();
        if (sections == null || sections.length == 0) return false;

        // A chunk with ALL empty sections is likely not properly loaded
        boolean hasAnyNonEmpty = false;
        for (var section : sections) {
            if (section != null && !section.hasOnlyAir()) {
                hasAnyNonEmpty = true;
                break;
            }
        }

        // Void chunks (in the end, or far from land) can have all air
        // but normal overworld chunks should have at least stone/dirt
        return hasAnyNonEmpty;
    }

    /**
     * Check if chunks in the player's flight path are loaded.
     * Returns true if it's safe to fly (chunks ahead are loaded).
     */
    public boolean isFlightPathLoaded() {
        return unloadedChunksAhead <= 1;
    }

    /**
     * Returns true if the server is significantly lagging (TPS < 15).
     */
    public boolean isLagging() {
        return estimatedTPS < 15.0;
    }

    /**
     * Returns true if the server is severely lagging (TPS < 10).
     */
    public boolean isSeverelyLagging() {
        return estimatedTPS < 10.0;
    }

    // Getters
    public double getEstimatedTPS() { return estimatedTPS; }
    public double getLagMultiplier() { return lagMultiplier; }
    public int getUnloadedChunksAhead() { return unloadedChunksAhead; }

    /**
     * Adjust a timeout value based on current lag.
     * Example: adjustTimeout(20) returns 20 at 20 TPS, 40 at 10 TPS.
     */
    public int adjustTimeout(int baseTicks) {
        return (int) Math.ceil(baseTicks * lagMultiplier);
    }
}
