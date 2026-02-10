package com.basefinder.trail;

import com.basefinder.util.BlockAnalyzer;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.Vec2d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Detects and follows trails that may lead to bases.
 * Trails include: highways, ice roads, rail lines, torch paths, cobblestone paths.
 */
public class TrailFollower {

    private final Minecraft mc = Minecraft.getInstance();

    private boolean isFollowingTrail = false;
    private Vec2d trailDirection = null;
    private BlockPos lastTrailBlock = null;
    private final List<BlockPos> trailHistory = new ArrayList<>();
    private int ticksSinceLastTrailBlock = 0;
    private int maxLostTicks = 100; // Give up after ~5 seconds of no trail
    private int searchRadius = 8;

    /**
     * Attempt to detect a trail direction from trail chunks near the player.
     */
    public boolean detectTrail(List<ChunkAnalysis> trailChunks) {
        if (mc.player == null || mc.level == null) return false;

        BlockPos playerPos = mc.player.blockPosition();

        // Search around the player for trail blocks
        List<BlockPos> nearbyTrailBlocks = new ArrayList<>();
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                for (int y = -3; y <= 3; y++) {
                    BlockPos check = playerPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(check);
                    if (BlockAnalyzer.isTrailBlock(state.getBlock())) {
                        nearbyTrailBlocks.add(check);
                    }
                }
            }
        }

        if (nearbyTrailBlocks.size() < 5) return false;

        // Determine trail direction using linear regression on XZ
        trailDirection = calculateTrailDirection(nearbyTrailBlocks);
        if (trailDirection == null) return false;

        lastTrailBlock = nearbyTrailBlocks.get(nearbyTrailBlocks.size() - 1);
        isFollowingTrail = true;
        ticksSinceLastTrailBlock = 0;
        trailHistory.clear();
        trailHistory.addAll(nearbyTrailBlocks);

        return true;
    }

    /**
     * Calculate the primary direction of a trail from block positions.
     */
    private Vec2d calculateTrailDirection(List<BlockPos> blocks) {
        if (blocks.size() < 3) return null;

        double avgX = 0, avgZ = 0;
        for (BlockPos pos : blocks) {
            avgX += pos.getX();
            avgZ += pos.getZ();
        }
        avgX /= blocks.size();
        avgZ /= blocks.size();

        // Simple direction: from average to the farthest block
        double maxDist = 0;
        BlockPos farthest = blocks.get(0);
        for (BlockPos pos : blocks) {
            double dx = pos.getX() - avgX;
            double dz = pos.getZ() - avgZ;
            double dist = dx * dx + dz * dz;
            if (dist > maxDist) {
                maxDist = dist;
                farthest = pos;
            }
        }

        double dx = farthest.getX() - avgX;
        double dz = farthest.getZ() - avgZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return null;

        // Snap to cardinal/diagonal if close
        Vec2d raw = new Vec2d(dx / len, dz / len);
        return snapToAxis(raw);
    }

    /**
     * Snap a direction to the nearest cardinal or diagonal axis if close enough.
     */
    private Vec2d snapToAxis(Vec2d dir) {
        double[][] axes = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {0.707, 0.707}, {-0.707, 0.707}, {0.707, -0.707}, {-0.707, -0.707}
        };

        double bestDot = -1;
        Vec2d best = dir;
        for (double[] axis : axes) {
            double dot = dir.x() * axis[0] + dir.z() * axis[1];
            if (dot > bestDot) {
                bestDot = dot;
                best = new Vec2d(axis[0], axis[1]);
            }
        }

        // Only snap if we're within 20 degrees
        if (bestDot > 0.94) return best;
        return dir;
    }

    /**
     * Called every tick while following a trail.
     * Returns the target position to move towards, or null if trail is lost.
     */
    public BlockPos getNextTrailTarget() {
        if (!isFollowingTrail || mc.player == null || mc.level == null || trailDirection == null) {
            return null;
        }

        BlockPos playerPos = mc.player.blockPosition();
        ticksSinceLastTrailBlock++;

        // Check if we can still see trail blocks ahead
        BlockPos ahead = lookAhead(playerPos);
        if (ahead != null) {
            lastTrailBlock = ahead;
            ticksSinceLastTrailBlock = 0;
            trailHistory.add(ahead);
        }

        if (ticksSinceLastTrailBlock > maxLostTicks) {
            isFollowingTrail = false;
            return null;
        }

        // Target is always ahead in trail direction
        double targetX = playerPos.getX() + trailDirection.x() * 16;
        double targetZ = playerPos.getZ() + trailDirection.z() * 16;
        return new BlockPos((int) targetX, playerPos.getY(), (int) targetZ);
    }

    /**
     * Look ahead in the trail direction for more trail blocks.
     */
    private BlockPos lookAhead(BlockPos from) {
        if (mc.level == null || trailDirection == null) return null;

        for (int dist = 1; dist <= searchRadius * 2; dist++) {
            int x = from.getX() + (int) (trailDirection.x() * dist);
            int z = from.getZ() + (int) (trailDirection.z() * dist);

            for (int y = -2; y <= 2; y++) {
                BlockPos check = new BlockPos(x, from.getY() + y, z);
                BlockState state = mc.level.getBlockState(check);
                if (BlockAnalyzer.isTrailBlock(state.getBlock())) {
                    return check;
                }
            }
        }
        return null;
    }

    /**
     * Get the yaw angle to face the trail direction.
     */
    public float getTrailYaw() {
        if (trailDirection == null) return 0;
        return (float) (Math.toDegrees(Math.atan2(-trailDirection.x(), trailDirection.z())));
    }

    public void stopFollowing() {
        isFollowingTrail = false;
        trailDirection = null;
        lastTrailBlock = null;
        trailHistory.clear();
        ticksSinceLastTrailBlock = 0;
    }

    public boolean isFollowingTrail() { return isFollowingTrail; }
    public Vec2d getTrailDirection() { return trailDirection; }
    public int getTrailLength() { return trailHistory.size(); }
    public void setMaxLostTicks(int ticks) { this.maxLostTicks = ticks; }
    public void setSearchRadius(int radius) { this.searchRadius = radius; }
}
