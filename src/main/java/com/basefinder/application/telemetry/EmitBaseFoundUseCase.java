package com.basefinder.application.telemetry;

import com.basefinder.domain.event.BaseFound;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.world.ChunkId;

/**
 * Use case appelé par le BaseLogger quand une base est confirmée.
 * Construit l'événement immuable et le pousse vers le sink.
 */
public final class EmitBaseFoundUseCase {

    private final TelemetrySink sink;
    private final EventSequenceCounter sequence;

    public EmitBaseFoundUseCase(TelemetrySink sink, EventSequenceCounter sequence) {
        this.sink = sink;
        this.sequence = sequence;
    }

    public void emit(ChunkId chunkId, BaseType baseType, double score,
                     int worldX, int worldY, int worldZ) {
        BaseFound event = new BaseFound(
                sequence.next(),
                System.currentTimeMillis(),
                chunkId,
                baseType,
                score,
                worldX, worldY, worldZ);
        sink.publish(event);
    }
}
