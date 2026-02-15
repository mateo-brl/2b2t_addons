package com.basefinder.trail;

import com.basefinder.scanner.ChunkAgeAnalyzer;
import com.basefinder.scanner.NewChunkDetector;
import com.basefinder.util.BlockAnalyzer;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.Vec2d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.rusherhack.client.api.utils.WorldUtils;

import java.util.*;

/**
 * Detects and follows trails that may lead to bases.
 *
 * Uses THREE detection methods:
 *
 * 1. BLOCK TRAIL: Physical trail blocks (ice roads, rails, torches, cobblestone paths)
 * 2. CHUNK TRAIL: Lines of OLD chunks surrounded by NEW chunks.
 *    When a player travels through ungenerated terrain, they create a narrow
 *    corridor of loaded (old) chunks. This trail is invisible in-game but
 *    detectable via NewChunkDetector.
 * 3. VERSION BORDER: Boundaries between chunks generated in different MC versions.
 *    On 2b2t, old chunks (pre-1.18) next to new chunks indicate someone explored
 *    in that direction during an older version.
 */
public class TrailFollower {

    private final Minecraft mc = Minecraft.getInstance();

    // Detection sources
    private NewChunkDetector newChunkDetector;
    private ChunkAgeAnalyzer chunkAgeAnalyzer;

    // State
    private boolean isFollowingTrail = false;
    private Vec2d trailDirection = null;
    private BlockPos lastTrailBlock = null;
    private final List<BlockPos> trailHistory = new ArrayList<>();
    private int ticksSinceLastTrailBlock = 0;
    private int maxLostTicks = 100;
    private int searchRadius = 8;
    private TrailType currentTrailType = TrailType.NONE;

    // Chunk trail tracking
    private final List<ChunkPos> chunkTrailPath = new ArrayList<>();
    private int chunkTrailScanRadius = 5; // chunks around player to scan for trails

    public enum TrailType {
        NONE,
        BLOCK_TRAIL,     // Physical blocks (ice, rails, torches)
        CHUNK_TRAIL,     // Old chunks surrounded by new chunks
        VERSION_BORDER   // Border between pre-1.18 and post-1.18 chunks
    }

    public void setNewChunkDetector(NewChunkDetector detector) {
        this.newChunkDetector = detector;
    }

    public void setChunkAgeAnalyzer(ChunkAgeAnalyzer analyzer) {
        this.chunkAgeAnalyzer = analyzer;
    }

    /**
     * Attempt to detect any type of trail near the player.
     * Tries chunk trails first (most reliable), then block trails.
     */
    public boolean detectTrail(List<ChunkAnalysis> trailChunks) {
        if (mc.player == null || mc.level == null) return false;

        // Method 1: Try chunk trail detection (old chunks surrounded by new chunks)
        if (newChunkDetector != null && newChunkDetector.isEnabled()) {
            Vec2d chunkDir = detectChunkTrail();
            if (chunkDir != null) {
                trailDirection = chunkDir;
                isFollowingTrail = true;
                ticksSinceLastTrailBlock = 0;
                currentTrailType = TrailType.CHUNK_TRAIL;
                trailHistory.clear();
                return true;
            }
        }

        // Method 2: Try version border detection
        if (chunkAgeAnalyzer != null) {
            Vec2d versionDir = detectVersionBorder();
            if (versionDir != null) {
                trailDirection = versionDir;
                isFollowingTrail = true;
                ticksSinceLastTrailBlock = 0;
                currentTrailType = TrailType.VERSION_BORDER;
                trailHistory.clear();
                return true;
            }
        }

        // Method 3: Fall back to physical block trail detection
        return detectBlockTrail(trailChunks);
    }

    /**
     * CHUNK TRAIL DETECTION
     *
     * Scans nearby chunks for a pattern of old chunks forming a line
     * through new chunks. This indicates someone traveled through here before.
     *
     * Algorithm:
     * 1. Collect all old and new chunks in scan radius
     * 2. For each old chunk, check if it has new chunk neighbors
     * 3. If we find a line of old chunks (3+) with new chunks on both sides -> trail
     * 4. Calculate trail direction from the line of old chunks
     */
    private Vec2d detectChunkTrail() {
        if (mc.player == null || newChunkDetector == null) return null;

        ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
        List<ChunkPos> oldChunksNearby = new ArrayList<>();
        Set<ChunkPos> newChunksNearby = new HashSet<>();

        // Gather old and new chunks in scan radius
        for (int dx = -chunkTrailScanRadius; dx <= chunkTrailScanRadius; dx++) {
            for (int dz = -chunkTrailScanRadius; dz <= chunkTrailScanRadius; dz++) {
                ChunkPos check = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                if (newChunkDetector.isOldChunk(check)) {
                    oldChunksNearby.add(check);
                } else if (newChunkDetector.isNewChunk(check)) {
                    newChunksNearby.add(check);
                }
            }
        }

        // Need at least 3 old chunks and some new chunks to detect a trail
        if (oldChunksNearby.size() < 3 || newChunksNearby.size() < 3) return null;

        // Find old chunks that are bordered by new chunks (trail candidates)
        List<ChunkPos> trailCandidates = new ArrayList<>();
        for (ChunkPos oldChunk : oldChunksNearby) {
            int newNeighbors = 0;
            ChunkPos[] neighbors = {
                    new ChunkPos(oldChunk.x + 1, oldChunk.z),
                    new ChunkPos(oldChunk.x - 1, oldChunk.z),
                    new ChunkPos(oldChunk.x, oldChunk.z + 1),
                    new ChunkPos(oldChunk.x, oldChunk.z - 1)
            };
            for (ChunkPos neighbor : neighbors) {
                if (newChunksNearby.contains(neighbor)) {
                    newNeighbors++;
                }
            }
            // A trail chunk has new chunks on at least 2 sides
            if (newNeighbors >= 2) {
                trailCandidates.add(oldChunk);
            }
        }

        if (trailCandidates.size() < 5) return null;

        // Calculate direction from the trail candidates using PCA-like approach
        return calculateChunkTrailDirection(trailCandidates);
    }

    /**
     * VERSION BORDER DETECTION
     *
     * On 2b2t, finds boundaries where pre-1.18 chunks meet post-1.18 chunks.
     * This indicates the edge of someone's exploration during an older version.
     * Following the border often leads to bases.
     */
    private Vec2d detectVersionBorder() {
        if (mc.player == null || mc.level == null || chunkAgeAnalyzer == null) return null;

        ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
        List<ChunkPos> oldGenChunks = new ArrayList<>();
        List<ChunkPos> newGenChunks = new ArrayList<>();

        // Scan nearby chunks for version differences
        List<LevelChunk> loadedChunks = WorldUtils.getChunks();
        for (LevelChunk chunk : loadedChunks) {
            ChunkPos pos = chunk.getPos();
            int dx = Math.abs(pos.x - playerChunk.x);
            int dz = Math.abs(pos.z - playerChunk.z);
            if (dx > chunkTrailScanRadius || dz > chunkTrailScanRadius) continue;

            ChunkAgeAnalyzer.ChunkGeneration gen = chunkAgeAnalyzer.analyzeChunkGeneration(chunk);
            if (gen == ChunkAgeAnalyzer.ChunkGeneration.PRE_1_18
                    || gen == ChunkAgeAnalyzer.ChunkGeneration.PRE_1_16
                    || gen == ChunkAgeAnalyzer.ChunkGeneration.PRE_1_13) {
                oldGenChunks.add(pos);
            } else if (gen == ChunkAgeAnalyzer.ChunkGeneration.POST_1_18
                    || gen == ChunkAgeAnalyzer.ChunkGeneration.CURRENT) {
                newGenChunks.add(pos);
            }
        }

        // Need both old and new generation chunks to find a border
        if (oldGenChunks.size() < 2 || newGenChunks.size() < 2) return null;

        // Find border chunks: old gen chunks adjacent to new gen chunks
        Set<ChunkPos> newGenSet = new HashSet<>(newGenChunks);
        List<ChunkPos> borderChunks = new ArrayList<>();

        for (ChunkPos oldChunk : oldGenChunks) {
            ChunkPos[] neighbors = {
                    new ChunkPos(oldChunk.x + 1, oldChunk.z),
                    new ChunkPos(oldChunk.x - 1, oldChunk.z),
                    new ChunkPos(oldChunk.x, oldChunk.z + 1),
                    new ChunkPos(oldChunk.x, oldChunk.z - 1)
            };
            for (ChunkPos neighbor : neighbors) {
                if (newGenSet.contains(neighbor)) {
                    borderChunks.add(oldChunk);
                    break;
                }
            }
        }

        if (borderChunks.size() < 2) return null;

        // The trail direction follows the border (old gen side)
        // and points towards the center of old gen territory (likely towards a base)
        return calculateDirectionTowardsOldGen(oldGenChunks, borderChunks, playerChunk);
    }

    /**
     * Calculate direction from border chunks towards the center of old generation territory.
     */
    private Vec2d calculateDirectionTowardsOldGen(List<ChunkPos> oldChunks, List<ChunkPos> borderChunks, ChunkPos playerChunk) {
        // Average position of all old chunks (likely center of explored area / base)
        double avgX = 0, avgZ = 0;
        for (ChunkPos pos : oldChunks) {
            avgX += pos.x;
            avgZ += pos.z;
        }
        avgX /= oldChunks.size();
        avgZ /= oldChunks.size();

        // Direction from player to center of old chunks
        double dx = avgX - playerChunk.x;
        double dz = avgZ - playerChunk.z;
        double len = Math.sqrt(dx * dx + dz * dz);

        if (len < 1) return null;

        return snapToAxis(new Vec2d(dx / len, dz / len));
    }

    /**
     * Calculate trail direction from a set of chunk positions using variance analysis.
     */
    private Vec2d calculateChunkTrailDirection(List<ChunkPos> chunks) {
        if (chunks.size() < 3) return null;

        double avgX = 0, avgZ = 0;
        for (ChunkPos pos : chunks) {
            avgX += pos.x;
            avgZ += pos.z;
        }
        avgX /= chunks.size();
        avgZ /= chunks.size();

        // Covariance matrix for PCA
        double covXX = 0, covXZ = 0, covZZ = 0;
        for (ChunkPos pos : chunks) {
            double dx = pos.x - avgX;
            double dz = pos.z - avgZ;
            covXX += dx * dx;
            covXZ += dx * dz;
            covZZ += dz * dz;
        }

        // Principal component (eigenvector of larger eigenvalue)
        // For 2x2 matrix, use analytical solution
        double trace = covXX + covZZ;
        double det = covXX * covZZ - covXZ * covXZ;
        double eigenvalue1 = trace / 2 + Math.sqrt(trace * trace / 4 - det);

        double vx, vz;
        if (Math.abs(covXZ) > 0.001) {
            vx = eigenvalue1 - covZZ;
            vz = covXZ;
        } else if (covXX > covZZ) {
            vx = 1;
            vz = 0;
        } else {
            vx = 0;
            vz = 1;
        }

        double len = Math.sqrt(vx * vx + vz * vz);
        if (len < 0.001) return null;

        Vec2d dir = new Vec2d(vx / len, vz / len);

        // Orient direction away from player (towards unexplored end of trail)
        if (mc.player != null) {
            ChunkPos playerChunk = new ChunkPos(mc.player.blockPosition());
            // Find the farthest trail chunk from player
            double maxDist = 0;
            ChunkPos farthest = chunks.get(0);
            for (ChunkPos pos : chunks) {
                double d = Math.pow(pos.x - playerChunk.x, 2) + Math.pow(pos.z - playerChunk.z, 2);
                if (d > maxDist) {
                    maxDist = d;
                    farthest = pos;
                }
            }

            double toDirX = farthest.x - playerChunk.x;
            double toDirZ = farthest.z - playerChunk.z;
            double dot = dir.x() * toDirX + dir.z() * toDirZ;
            if (dot < 0) {
                dir = new Vec2d(-dir.x(), -dir.z());
            }
        }

        return snapToAxis(dir);
    }

    /**
     * BLOCK TRAIL DETECTION (original method)
     * Detects physical trail blocks (ice roads, rails, torches, etc.)
     */
    private boolean detectBlockTrail(List<ChunkAnalysis> trailChunks) {
        if (mc.player == null || mc.level == null) return false;

        BlockPos playerPos = mc.player.blockPosition();
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

        if (nearbyTrailBlocks.size() < 10) return false;

        trailDirection = calculateBlockTrailDirection(nearbyTrailBlocks);
        if (trailDirection == null) return false;

        lastTrailBlock = nearbyTrailBlocks.get(nearbyTrailBlocks.size() - 1);
        isFollowingTrail = true;
        ticksSinceLastTrailBlock = 0;
        currentTrailType = TrailType.BLOCK_TRAIL;
        trailHistory.clear();
        trailHistory.addAll(nearbyTrailBlocks);

        return true;
    }

    private Vec2d calculateBlockTrailDirection(List<BlockPos> blocks) {
        if (blocks.size() < 3) return null;

        double avgX = 0, avgZ = 0;
        for (BlockPos pos : blocks) {
            avgX += pos.getX();
            avgZ += pos.getZ();
        }
        avgX /= blocks.size();
        avgZ /= blocks.size();

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

        return snapToAxis(new Vec2d(dx / len, dz / len));
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

        // For chunk trails, verify we're still on the trail
        if (currentTrailType == TrailType.CHUNK_TRAIL && newChunkDetector != null) {
            ChunkPos currentChunk = new ChunkPos(playerPos);
            if (newChunkDetector.isOldChunk(currentChunk)) {
                ticksSinceLastTrailBlock = 0;
                chunkTrailPath.add(currentChunk);
            }
            // If we enter a new chunk and the trail continues ahead, keep going
            if (newChunkDetector.isNewChunk(currentChunk)) {
                // Check if there are old chunks ahead in our direction
                ChunkPos ahead = new ChunkPos(
                        currentChunk.x + (int) Math.signum(trailDirection.x()),
                        currentChunk.z + (int) Math.signum(trailDirection.z())
                );
                if (newChunkDetector.isOldChunk(ahead)) {
                    ticksSinceLastTrailBlock = 0;
                }
            }
        }

        // For block trails, look ahead for more trail blocks
        if (currentTrailType == TrailType.BLOCK_TRAIL) {
            BlockPos ahead = lookAhead(playerPos);
            if (ahead != null) {
                lastTrailBlock = ahead;
                ticksSinceLastTrailBlock = 0;
                trailHistory.add(ahead);
            }
        }

        if (ticksSinceLastTrailBlock > maxLostTicks) {
            isFollowingTrail = false;
            return null;
        }

        // Target is ahead in trail direction
        double targetX = playerPos.getX() + trailDirection.x() * 16;
        double targetZ = playerPos.getZ() + trailDirection.z() * 16;
        return new BlockPos((int) targetX, playerPos.getY(), (int) targetZ);
    }

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

    public float getTrailYaw() {
        if (trailDirection == null) return 0;
        return (float) (Math.toDegrees(Math.atan2(-trailDirection.x(), trailDirection.z())));
    }

    public void stopFollowing() {
        isFollowingTrail = false;
        trailDirection = null;
        lastTrailBlock = null;
        trailHistory.clear();
        chunkTrailPath.clear();
        ticksSinceLastTrailBlock = 0;
        currentTrailType = TrailType.NONE;
    }

    // Getters
    public boolean isFollowingTrail() { return isFollowingTrail; }
    public Vec2d getTrailDirection() { return trailDirection; }
    public int getTrailLength() { return trailHistory.size() + chunkTrailPath.size(); }
    public TrailType getCurrentTrailType() { return currentTrailType; }
    public List<ChunkPos> getChunkTrailPath() { return Collections.unmodifiableList(chunkTrailPath); }

    // Setters
    public void setMaxLostTicks(int ticks) { this.maxLostTicks = ticks; }
    public void setSearchRadius(int radius) { this.searchRadius = radius; }
    public void setChunkTrailScanRadius(int radius) { this.chunkTrailScanRadius = radius; }
}
