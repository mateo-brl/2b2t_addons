package com.basefinder.scanner;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects new chunks vs old chunks using the flowing liquid exploit.
 *
 * When a chunk is generated for the first time, liquids (water/lava) that were placed
 * by world generation have not yet "flowed". These pending fluid ticks are processed
 * when the chunk is first loaded by a player, causing block update packets to be sent.
 *
 * - If we receive liquid flow updates in a chunk -> it's a NEW chunk (never visited)
 * - If a chunk loads without liquid flow updates -> it's an OLD chunk (previously visited)
 *
 * This is the same technique used by Wurst, Future, and other clients.
 */
public class NewChunkDetector {

    private final Minecraft mc = Minecraft.getInstance();

    /** Chunks confirmed as NEW (first time loaded, liquids are flowing) */
    private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();

    /** Chunks confirmed as OLD (previously loaded, no liquid updates) */
    private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();

    /** Chunks we've seen load but haven't classified yet */
    private final Set<ChunkPos> pendingChunks = ConcurrentHashMap.newKeySet();

    /** Tracks recently loaded chunks to give time for fluid updates to arrive */
    private final Map<ChunkPos, Long> chunkLoadTimes = new ConcurrentHashMap<>();

    /** How many ticks to wait before classifying a chunk as "old" (no fluid updates received) */
    private int classificationDelay = 40; // 2 seconds

    private boolean enabled = false;

    /**
     * Called when a chunk is loaded. Starts tracking it.
     */
    public void onChunkLoad(ChunkPos pos) {
        if (!enabled) return;
        if (newChunks.contains(pos) || oldChunks.contains(pos)) return;

        pendingChunks.add(pos);
        chunkLoadTimes.put(pos, System.currentTimeMillis());
    }

    /**
     * Called when a chunk is unloaded.
     */
    public void onChunkUnload(ChunkPos pos) {
        pendingChunks.remove(pos);
        chunkLoadTimes.remove(pos);
    }

    /**
     * Process a single block update packet.
     * If the update is a flowing liquid in a pending chunk, mark it as NEW.
     */
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!enabled) return;

        ChunkPos chunkPos = new ChunkPos(pos);

        // Check if this is a liquid flow event
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() && !fluidState.isSource()) {
            // This is a flowing liquid (not a source block) -> chunk is NEW
            if (pendingChunks.remove(chunkPos)) {
                newChunks.add(chunkPos);
                chunkLoadTimes.remove(chunkPos);
            } else if (!oldChunks.contains(chunkPos)) {
                // Even if not pending, if we see liquid flow it's new
                newChunks.add(chunkPos);
            }
        }
    }

    /**
     * Process a multi-block update packet (SectionBlocksUpdate / ChunkDeltaUpdate).
     */
    public void onMultiBlockUpdate(ChunkPos chunkPos, short[] positions, BlockState[] states) {
        if (!enabled) return;

        for (int i = 0; i < states.length; i++) {
            FluidState fluidState = states[i].getFluidState();
            if (!fluidState.isEmpty() && !fluidState.isSource()) {
                if (pendingChunks.remove(chunkPos)) {
                    newChunks.add(chunkPos);
                    chunkLoadTimes.remove(chunkPos);
                } else if (!oldChunks.contains(chunkPos)) {
                    newChunks.add(chunkPos);
                }
                return; // One flowing liquid is enough
            }
        }
    }

    /**
     * Called every tick to classify pending chunks that haven't received fluid updates.
     * After the classification delay, assume chunks are OLD.
     */
    public void tick() {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        long delayMs = classificationDelay * 50L; // convert ticks to ms

        Iterator<Map.Entry<ChunkPos, Long>> it = chunkLoadTimes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Long> entry = it.next();
            if (now - entry.getValue() > delayMs) {
                ChunkPos pos = entry.getKey();
                if (pendingChunks.remove(pos)) {
                    // No fluid updates received in time -> OLD chunk
                    oldChunks.add(pos);
                }
                it.remove();
            }
        }
    }

    /**
     * Check if a chunk is classified as NEW.
     */
    public boolean isNewChunk(ChunkPos pos) {
        return newChunks.contains(pos);
    }

    /**
     * Check if a chunk is classified as OLD.
     */
    public boolean isOldChunk(ChunkPos pos) {
        return oldChunks.contains(pos);
    }

    /**
     * Check if a chunk is still pending classification.
     */
    public boolean isPending(ChunkPos pos) {
        return pendingChunks.contains(pos);
    }

    public Set<ChunkPos> getNewChunks() { return Collections.unmodifiableSet(newChunks); }
    public Set<ChunkPos> getOldChunks() { return Collections.unmodifiableSet(oldChunks); }
    public int getNewChunkCount() { return newChunks.size(); }
    public int getOldChunkCount() { return oldChunks.size(); }
    public int getPendingCount() { return pendingChunks.size(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
    public void setClassificationDelay(int ticks) { this.classificationDelay = ticks; }

    public void reset() {
        newChunks.clear();
        oldChunks.clear();
        pendingChunks.clear();
        chunkLoadTimes.clear();
    }
}
