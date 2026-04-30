package com.basefinder.application.telemetry;

import com.basefinder.domain.event.ChunksScannedBatch;

/**
 * Use case appelé périodiquement (≈ toutes les 10 s) pour publier la liste
 * des chunks scannés depuis le dernier emit. Sert à alimenter la coverage
 * layer du dashboard.
 *
 * Idempotency key = "scan:&lt;dim&gt;:&lt;seq&gt;". Le backend dédup sur cette
 * clé pour absorber les retries HTTP.
 */
public final class EmitChunksScannedUseCase {

    private final TelemetrySink sink;
    private final EventSequenceCounter sequence;

    public EmitChunksScannedUseCase(TelemetrySink sink, EventSequenceCounter sequence) {
        this.sink = sink;
        this.sequence = sequence;
    }

    public void emit(String dimension, long[] chunks) {
        if (chunks == null || chunks.length == 0) return;
        ChunksScannedBatch event = new ChunksScannedBatch(
                sequence.next(),
                System.currentTimeMillis(),
                dimension,
                chunks);
        sink.publish(event);
    }
}
