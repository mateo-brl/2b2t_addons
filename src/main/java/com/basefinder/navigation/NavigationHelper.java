package com.basefinder.navigation;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Manages navigation waypoints and search patterns for base hunting.
 * Implements a spiral search pattern radiating outward from spawn (0,0).
 */
public class NavigationHelper {

    private final Minecraft mc = Minecraft.getInstance();

    // Search pattern
    private SearchPattern pattern = SearchPattern.SPIRAL;
    private final List<BlockPos> waypoints = new ArrayList<>();
    private int currentWaypointIndex = 0;
    private BlockPos currentTarget = null;
    private BlockPos searchCenter = null;

    // Spiral parameters
    private double spiralRadius = 1000;
    private double spiralStep = 500; // distance between spiral arms
    private double spiralAngle = 0;
    private int spiralRing = 1;

    // Highway search parameters
    private int highwayDistance = 100000; // How far along highways to search
    private int highwayCheckInterval = 1000; // Check every N blocks

    // Random search parameters
    private int searchMinDistance = 5000;
    private int searchMaxDistance = 100000;
    private Random random = new Random();

    // Grid search parameters
    private int gridSize = 1000; // Size of each grid square in blocks
    private int gridRange = 50000; // Total range to cover

    // Zone search parameters
    private int zoneMinX = 0;
    private int zoneMaxX = 500000;
    private int zoneMinZ = 0;
    private int zoneMaxZ = 500000;
    private int zoneSpacing = 1000; // Distance between waypoints in zone mode

    // Zone coverage tracking - uses bounds instead of enumerating all chunks
    // to avoid OOM on large zones (e.g. 500k x 500k = 976M chunks)
    private int zoneChunkMinX, zoneChunkMaxX, zoneChunkMinZ, zoneChunkMaxZ;
    private boolean isZoneMode = false;
    private int zoneMissedPassCount = 0; // How many cleanup passes have been done

    // Stats
    private double totalDistanceTraveled = 0;
    private BlockPos lastPosition = null;

    public enum SearchPattern {
        SPIRAL,      // Spiral out from current position
        GRID,        // Systematic grid coverage in squares
        ZONE,        // Search within specific coordinate bounds
        HIGHWAYS,    // Follow nether highways (X+, X-, Z+, Z-)
        RANDOM,      // Random teleport-distance positions
        RING,        // Search at a specific radius
        CUSTOM       // User-defined waypoints
    }

    /**
     * Initialize the search pattern and generate waypoints.
     */
    public void initializeSearch(SearchPattern pattern, BlockPos center) {
        this.pattern = pattern;
        this.searchCenter = center;
        this.waypoints.clear();
        this.currentWaypointIndex = 0;
        this.isZoneMode = false;

        switch (pattern) {
            case SPIRAL -> generateSpiralWaypoints(center);
            case GRID -> generateGridWaypoints(center);
            case ZONE -> generateZoneWaypoints();
            case HIGHWAYS -> generateHighwayWaypoints(center);
            case RANDOM -> generateRandomWaypoints(center);
            case RING -> generateRingWaypoints(center);
            case CUSTOM -> {} // user adds waypoints manually
        }

        if (!waypoints.isEmpty()) {
            currentTarget = waypoints.get(0);
        }
    }

    private void generateSpiralWaypoints(BlockPos center) {
        // Generate a spiral pattern of waypoints
        double angle = 0;
        double radius = spiralStep;

        for (int i = 0; i < 200; i++) { // 200 waypoints
            int x = center.getX() + (int) (Math.cos(angle) * radius);
            int z = center.getZ() + (int) (Math.sin(angle) * radius);
            waypoints.add(new BlockPos(x, 200, z));

            angle += Math.PI / 4; // 45 degree increments
            radius += spiralStep / 8; // gradually increase radius
        }
    }

    private void generateHighwayWaypoints(BlockPos center) {
        // Generate waypoints along the 4 main highways and 4 diagonal highways
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},   // Cardinal
                {1, 1}, {-1, 1}, {1, -1}, {-1, -1}    // Diagonal
        };

        for (int[] dir : directions) {
            for (int dist = highwayCheckInterval; dist <= highwayDistance; dist += highwayCheckInterval) {
                int x = center.getX() + dir[0] * dist;
                int z = center.getZ() + dir[1] * dist;
                waypoints.add(new BlockPos(x, 200, z));
            }
        }
    }

    private void generateRandomWaypoints(BlockPos center) {
        for (int i = 0; i < 100; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = searchMinDistance + random.nextDouble() * (searchMaxDistance - searchMinDistance);
            int x = center.getX() + (int) (Math.cos(angle) * dist);
            int z = center.getZ() + (int) (Math.sin(angle) * dist);
            waypoints.add(new BlockPos(x, 200, z));
        }
    }

    private void generateRingWaypoints(BlockPos center) {
        // Search around a ring at spiralRadius distance
        int pointCount = (int) (2 * Math.PI * spiralRadius / spiralStep);
        pointCount = Math.max(8, Math.min(pointCount, 360));

        for (int i = 0; i < pointCount; i++) {
            double angle = (2 * Math.PI * i) / pointCount;
            int x = center.getX() + (int) (Math.cos(angle) * spiralRadius);
            int z = center.getZ() + (int) (Math.sin(angle) * spiralRadius);
            waypoints.add(new BlockPos(x, 200, z));
        }
    }

    /**
     * Grid mode: divide area into squares of gridSize, zigzag through each.
     * Starts from center and expands outward in concentric rings of grid cells.
     * Uses a boustrophedon (zigzag) pattern for efficient coverage.
     */
    private void generateGridWaypoints(BlockPos center) {
        int halfRange = gridRange / 2;
        int cellsPerSide = (gridRange / gridSize) + 1;
        int startX = center.getX() - halfRange;
        int startZ = center.getZ() - halfRange;

        // Zigzag pattern: odd rows go left, even rows go right
        for (int row = 0; row < cellsPerSide; row++) {
            int z = startZ + row * gridSize + gridSize / 2;
            if (row % 2 == 0) {
                // Left to right
                for (int col = 0; col < cellsPerSide; col++) {
                    int x = startX + col * gridSize + gridSize / 2;
                    waypoints.add(new BlockPos(x, 200, z));
                }
            } else {
                // Right to left
                for (int col = cellsPerSide - 1; col >= 0; col--) {
                    int x = startX + col * gridSize + gridSize / 2;
                    waypoints.add(new BlockPos(x, 200, z));
                }
            }
        }
    }

    /**
     * Zone mode: generate waypoints in zigzag within specific coordinate bounds.
     * Covers the entire area defined by X début/fin and Z début/fin.
     *
     * IMPORTANT: Auto-caps spacing to match render distance to ensure ALL chunks
     * in the zone are scanned. Tracks expected chunks for coverage verification.
     */
    private void generateZoneWaypoints() {
        isZoneMode = true;
        zoneMissedPassCount = 0;

        int minX = Math.min(zoneMinX, zoneMaxX);
        int maxX = Math.max(zoneMinX, zoneMaxX);
        int minZ = Math.min(zoneMinZ, zoneMaxZ);
        int maxZ = Math.max(zoneMinZ, zoneMaxZ);

        // Auto-calculate spacing based on render distance to ensure COMPLETE chunk coverage.
        // The player scans chunks within render distance while flying.
        // To cover all chunks, spacing must be <= 2 * renderDistance * 16 (blocks).
        // We use a safety margin (0.75x) to account for flight path deviations.
        int renderDist = mc.options != null ? mc.options.renderDistance().get() : 8;
        int maxSpacingForCoverage = (int) (renderDist * 16 * 2 * 0.75);
        // On 2b2t server render distance is typically 6, so max = 6*16*2*0.75 = 144 blocks
        // Clamp to at least 64 blocks spacing minimum
        maxSpacingForCoverage = Math.max(64, maxSpacingForCoverage);

        int effectiveSpacing = Math.min(zoneSpacing, maxSpacingForCoverage);

        // Store zone chunk bounds for coverage calculations (O(1) instead of O(n^2) memory)
        zoneChunkMinX = minX >> 4;
        zoneChunkMaxX = maxX >> 4;
        zoneChunkMinZ = minZ >> 4;
        zoneChunkMaxZ = maxZ >> 4;

        // Generate zigzag waypoints with effective spacing
        int row = 0;
        for (int z = minZ + effectiveSpacing / 2; z <= maxZ; z += effectiveSpacing) {
            if (row % 2 == 0) {
                for (int x = minX + effectiveSpacing / 2; x <= maxX; x += effectiveSpacing) {
                    waypoints.add(new BlockPos(x, 200, z));
                }
            } else {
                for (int x = maxX - effectiveSpacing / 2; x >= minX; x -= effectiveSpacing) {
                    waypoints.add(new BlockPos(x, 200, z));
                }
            }
            row++;
        }
    }

    /**
     * Get the number of expected zone chunks (only valid in ZONE mode).
     */
    public int getExpectedZoneChunkCount() {
        long count = (long)(zoneChunkMaxX - zoneChunkMinX + 1) * (zoneChunkMaxZ - zoneChunkMinZ + 1);
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /**
     * Check zone coverage: returns the set of zone chunks that have NOT been scanned yet.
     */
    public Set<ChunkPos> getMissedZoneChunks(Set<ChunkPos> scannedChunks) {
        if (!isZoneMode) return Collections.emptySet();
        long totalExpected = (long)(zoneChunkMaxX - zoneChunkMinX + 1) * (zoneChunkMaxZ - zoneChunkMinZ + 1);
        if (totalExpected <= 0) return Collections.emptySet();

        // For small zones, enumerate all missed chunks directly
        if (totalExpected <= 100_000) {
            Set<ChunkPos> missed = new HashSet<>();
            for (int cx = zoneChunkMinX; cx <= zoneChunkMaxX; cx++) {
                for (int cz = zoneChunkMinZ; cz <= zoneChunkMaxZ; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    if (!scannedChunks.contains(pos)) {
                        missed.add(pos);
                    }
                }
            }
            return missed;
        }

        // For large zones, sample gaps near waypoints instead of enumerating all chunks
        int renderDist = mc.options != null ? mc.options.renderDistance().get() : 8;
        Set<ChunkPos> missed = new HashSet<>();
        for (BlockPos wp : waypoints) {
            int wcx = wp.getX() >> 4;
            int wcz = wp.getZ() >> 4;
            for (int dx = -renderDist; dx <= renderDist; dx++) {
                for (int dz = -renderDist; dz <= renderDist; dz++) {
                    int cx = wcx + dx;
                    int cz = wcz + dz;
                    if (cx >= zoneChunkMinX && cx <= zoneChunkMaxX && cz >= zoneChunkMinZ && cz <= zoneChunkMaxZ) {
                        ChunkPos pos = new ChunkPos(cx, cz);
                        if (!scannedChunks.contains(pos)) {
                            missed.add(pos);
                        }
                    }
                }
            }
            if (missed.size() > 10_000) break;
        }
        return missed;
    }

    /**
     * Get zone coverage percentage.
     */
    public double getZoneCoveragePercent(Set<ChunkPos> scannedChunks) {
        if (!isZoneMode) return 100.0;
        long totalExpected = (long)(zoneChunkMaxX - zoneChunkMinX + 1) * (zoneChunkMaxZ - zoneChunkMinZ + 1);
        if (totalExpected <= 0) return 100.0;
        // Count scanned chunks that fall within zone bounds
        long covered = scannedChunks.stream()
                .filter(cp -> cp.x >= zoneChunkMinX && cp.x <= zoneChunkMaxX
                           && cp.z >= zoneChunkMinZ && cp.z <= zoneChunkMaxZ)
                .count();
        return (double) covered / totalExpected * 100.0;
    }

    /**
     * Generate additional waypoints to cover missed zone chunks.
     * Groups missed chunks and generates waypoints at their centers.
     * Returns true if new waypoints were added.
     */
    public boolean generateMissedChunkWaypoints(Set<ChunkPos> scannedChunks) {
        Set<ChunkPos> missed = getMissedZoneChunks(scannedChunks);
        if (missed.isEmpty()) return false;

        zoneMissedPassCount++;

        // Group missed chunks into clusters and place waypoints at cluster centers
        List<ChunkPos> sortedMissed = new ArrayList<>(missed);
        // Sort by Z then X for efficient zigzag coverage
        sortedMissed.sort((a, b) -> a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.x, b.x));

        int renderDist = mc.options != null ? mc.options.renderDistance().get() : 8;
        int groupSize = Math.max(1, renderDist); // Group chunks within render distance

        // Create waypoints for each group of missed chunks
        for (int i = 0; i < sortedMissed.size(); i += groupSize) {
            int endIdx = Math.min(i + groupSize, sortedMissed.size());
            // Place waypoint at the center of this group
            int sumX = 0, sumZ = 0;
            for (int j = i; j < endIdx; j++) {
                sumX += sortedMissed.get(j).getMiddleBlockX();
                sumZ += sortedMissed.get(j).getMiddleBlockZ();
            }
            int count = endIdx - i;
            waypoints.add(new BlockPos(sumX / count, 200, sumZ / count));
        }

        return true;
    }

    public boolean isZoneMode() { return isZoneMode; }
    public int getZoneMissedPassCount() { return zoneMissedPassCount; }

    /**
     * Add a custom waypoint.
     */
    public void addWaypoint(BlockPos pos) {
        waypoints.add(pos);
        if (currentTarget == null) {
            currentTarget = pos;
        }
    }

    /**
     * Get the current navigation target.
     */
    public BlockPos getCurrentTarget() {
        return currentTarget;
    }

    /**
     * Called when the player reaches (close to) the current target.
     * Advances to the next waypoint.
     */
    public boolean advanceToNext() {
        currentWaypointIndex++;
        if (currentWaypointIndex >= waypoints.size()) {
            currentTarget = null;
            return false; // No more waypoints
        }
        currentTarget = waypoints.get(currentWaypointIndex);
        return true;
    }

    /**
     * Check if player is near the current target.
     */
    public boolean isNearTarget(double threshold) {
        if (mc.player == null || currentTarget == null) return false;
        double dx = mc.player.getX() - currentTarget.getX();
        double dz = mc.player.getZ() - currentTarget.getZ();
        return Math.sqrt(dx * dx + dz * dz) < threshold;
    }

    /**
     * Update distance tracking.
     */
    public void updateTracking() {
        if (mc.player == null) return;
        BlockPos currentPos = mc.player.blockPosition();
        if (lastPosition != null) {
            totalDistanceTraveled += Math.sqrt(
                    Math.pow(currentPos.getX() - lastPosition.getX(), 2) +
                    Math.pow(currentPos.getZ() - lastPosition.getZ(), 2)
            );
        }
        lastPosition = currentPos;
    }

    /**
     * Find the nearest waypoint to the player's current position and skip to it.
     * Returns the index of the nearest waypoint, or 0 if no waypoints or no player.
     */
    public int skipToNearest() {
        if (mc.player == null || waypoints.isEmpty()) return 0;

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double minDist = Double.MAX_VALUE;
        int nearestIndex = 0;

        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos wp = waypoints.get(i);
            double dx = playerX - wp.getX();
            double dz = playerZ - wp.getZ();
            double dist = dx * dx + dz * dz; // No need for sqrt, just comparing
            if (dist < minDist) {
                minDist = dist;
                nearestIndex = i;
            }
        }

        skipTo(nearestIndex);
        return nearestIndex;
    }

    /**
     * Skip to a specific waypoint index.
     */
    public void skipTo(int index) {
        if (index >= 0 && index < waypoints.size()) {
            currentWaypointIndex = index;
            currentTarget = waypoints.get(index);
        }
    }

    public void reset() {
        waypoints.clear();
        currentWaypointIndex = 0;
        currentTarget = null;
        totalDistanceTraveled = 0;
        lastPosition = null;
    }

    // Getters/Setters
    public int getWaypointCount() { return waypoints.size(); }
    public int getCurrentWaypointIndex() { return currentWaypointIndex; }
    public double getTotalDistanceTraveled() { return totalDistanceTraveled; }
    public void setTotalDistanceTraveled(double distance) { this.totalDistanceTraveled = distance; }
    public SearchPattern getPattern() { return pattern; }
    public BlockPos getSearchCenter() { return searchCenter; }
    public List<BlockPos> getWaypoints() { return Collections.unmodifiableList(waypoints); }

    public void setSpiralRadius(double radius) { this.spiralRadius = radius; }
    public void setSpiralStep(double step) { this.spiralStep = step; }
    public void setHighwayDistance(int dist) { this.highwayDistance = dist; }
    public void setHighwayCheckInterval(int interval) { this.highwayCheckInterval = interval; }
    public void setSearchMinDistance(int dist) { this.searchMinDistance = dist; }
    public void setSearchMaxDistance(int dist) { this.searchMaxDistance = dist; }
    public void setGridSize(int size) { this.gridSize = size; }
    public void setGridRange(int range) { this.gridRange = range; }
    public void setZoneBounds(int minX, int maxX, int minZ, int maxZ) {
        this.zoneMinX = minX;
        this.zoneMaxX = maxX;
        this.zoneMinZ = minZ;
        this.zoneMaxZ = maxZ;
    }
    public void setZoneSpacing(int spacing) { this.zoneSpacing = spacing; }
}
