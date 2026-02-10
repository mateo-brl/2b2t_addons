package com.basefinder.navigation;

import com.basefinder.util.Vec2d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

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

    // Stats
    private double totalDistanceTraveled = 0;
    private BlockPos lastPosition = null;

    public enum SearchPattern {
        SPIRAL,      // Spiral out from current position
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
        this.waypoints.clear();
        this.currentWaypointIndex = 0;

        switch (pattern) {
            case SPIRAL -> generateSpiralWaypoints(center);
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
    public SearchPattern getPattern() { return pattern; }
    public List<BlockPos> getWaypoints() { return Collections.unmodifiableList(waypoints); }

    public void setSpiralRadius(double radius) { this.spiralRadius = radius; }
    public void setSpiralStep(double step) { this.spiralStep = step; }
    public void setHighwayDistance(int dist) { this.highwayDistance = dist; }
    public void setHighwayCheckInterval(int interval) { this.highwayCheckInterval = interval; }
    public void setSearchMinDistance(int dist) { this.searchMinDistance = dist; }
    public void setSearchMaxDistance(int dist) { this.searchMaxDistance = dist; }
}
