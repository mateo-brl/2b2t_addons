package com.basefinder.adapter.io.telemetry;

import com.basefinder.domain.event.BaseFound;
import com.basefinder.domain.event.BotEvent;
import com.basefinder.domain.event.BotTick;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests round-trip de {@link EventSerializer} : ce qui sort doit pouvoir
 * être re-parsé avec les mêmes valeurs.
 */
class EventSerializerTest {

    @Test
    void baseFound_roundTrip() {
        BaseFound original = new BaseFound(
                42L, 1_700_000_000_000L,
                new ChunkId(123, -456, Dimension.NETHER),
                BaseType.STASH,
                162.5,
                1968, 64, -7296);

        String line = EventSerializer.toNdjsonLine(original);
        BotEvent parsed = EventSerializer.fromNdjsonLine(line);

        assertInstanceOf(BaseFound.class, parsed);
        BaseFound bf = (BaseFound) parsed;
        assertEquals(original.seq(), bf.seq());
        assertEquals(original.tsUtcMs(), bf.tsUtcMs());
        assertEquals(original.chunkId(), bf.chunkId());
        assertEquals(original.baseType(), bf.baseType());
        assertEquals(original.score(), bf.score());
        assertEquals(original.worldX(), bf.worldX());
        assertEquals(original.worldY(), bf.worldY());
        assertEquals(original.worldZ(), bf.worldZ());
        assertEquals(original.idempotencyKey(), bf.idempotencyKey());
    }

    @Test
    void botTick_roundTrip() {
        BotTick original = new BotTick(
                100, 1_700_000_000_000L,
                1500, 200, -2400, "overworld",
                18, 19.5,
                1234, 8,
                true, "CRUISING",
                42, 500);

        String line = EventSerializer.toNdjsonLine(original);
        BotEvent parsed = EventSerializer.fromNdjsonLine(line);

        assertInstanceOf(BotTick.class, parsed);
        BotTick t = (BotTick) parsed;
        assertEquals(original, t);
    }

    @Test
    void serializedLine_isSingleLineCompactJson() {
        BotTick t = new BotTick(1, 2, 100, 64, -100, "nether", 4, 5.0, 6, 7, false, "IDLE", 0, 100);
        String line = EventSerializer.toNdjsonLine(t);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        // Vérification rapide qu'on a bien du JSON
        assertEquals('{', line.charAt(0));
        assertEquals('}', line.charAt(line.length() - 1));
    }

    @Test
    void baseFoundIdempotencyKey_combinesDimAndChunkAndType() {
        BaseFound bf = new BaseFound(0, 0,
                new ChunkId(10, 20, Dimension.OVERWORLD),
                BaseType.STORAGE, 100, 0, 0, 0);
        assertEquals("overworld:10:20:STORAGE", bf.idempotencyKey());
    }
}
