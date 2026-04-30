package com.basefinder.domain.event;

/**
 * Batch d'IDs de chunks scannés par le bot. Émis périodiquement (~ toutes
 * les 10 s ou quand le buffer dépasse une taille) pour alimenter la
 * coverage layer du dashboard sans perdre de précision.
 *
 * Le payload est un tableau de longs packed via {@link com.basefinder.domain.world.ChunkId#pack(int, int)}.
 *
 * @param dimension legacy name : "overworld" / "nether" / "end"
 * @param chunks    chunk keys packed (32 bits x | 32 bits z)
 */
public record ChunksScannedBatch(
        long seq,
        long tsUtcMs,
        String dimension,
        long[] chunks
) implements BotEvent {

    @Override
    public String type() {
        return "chunks_scanned_batch";
    }

    @Override
    public String idempotencyKey() {
        return "scan:" + dimension + ":" + seq;
    }
}
