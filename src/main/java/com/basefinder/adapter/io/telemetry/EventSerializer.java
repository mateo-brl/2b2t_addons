package com.basefinder.adapter.io.telemetry;

import com.basefinder.domain.event.BaseFound;
import com.basefinder.domain.event.BotEvent;
import com.basefinder.domain.event.BotTick;
import com.basefinder.domain.event.ChunksScannedBatch;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Sérialise un {@link BotEvent} en NDJSON (une ligne JSON par event).
 *
 * Format wire v1 (audit/05 §3.1) — aplati en single-level JSON pour faciliter
 * le parsing côté backend. Champs communs en tête : type, seq, ts_utc_ms,
 * idempotency_key. Payload spécifique à chaque type ensuite.
 */
public final class EventSerializer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private EventSerializer() {
    }

    public static String toNdjsonLine(BotEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("type", event.type());
        json.addProperty("seq", event.seq());
        json.addProperty("ts_utc_ms", event.tsUtcMs());
        json.addProperty("idempotency_key", event.idempotencyKey());

        if (event instanceof BaseFound bf) {
            json.addProperty("chunk_x", bf.chunkId().x());
            json.addProperty("chunk_z", bf.chunkId().z());
            json.addProperty("dimension", bf.chunkId().dim().legacyName());
            json.addProperty("base_type", bf.baseType().name());
            json.addProperty("score", bf.score());
            json.addProperty("world_x", bf.worldX());
            json.addProperty("world_y", bf.worldY());
            json.addProperty("world_z", bf.worldZ());
        } else if (event instanceof BotTick t) {
            json.addProperty("pos_x", t.posX());
            json.addProperty("pos_y", t.posY());
            json.addProperty("pos_z", t.posZ());
            json.addProperty("dimension", t.dimension());
            json.addProperty("hp", t.hp());
            json.addProperty("tps", t.tps());
            json.addProperty("scanned_chunks", t.scannedChunks());
            json.addProperty("bases_found", t.basesFound());
            json.addProperty("flying", t.flying());
            json.addProperty("flight_state", t.flightStateName());
            json.addProperty("wp_index", t.waypointIndex());
            json.addProperty("wp_total", t.waypointTotal());
        } else if (event instanceof ChunksScannedBatch b) {
            json.addProperty("dimension", b.dimension());
            JsonArray arr = new JsonArray(b.chunks().length);
            for (long key : b.chunks()) {
                arr.add(key);
            }
            json.add("chunks", arr);
        } else {
            // Future event types : add a branch here. Default = no payload, just metadata.
        }

        return GSON.toJson(json);
    }

    /**
     * Parse une ligne NDJSON émise par {@link #toNdjsonLine}. Strictement utilisé
     * pour les tests round-trip. Les consommateurs prod liront via leur propre
     * parser (backend Kotlin).
     */
    public static BotEvent fromNdjsonLine(String line) {
        JsonObject json = GSON.fromJson(line, JsonObject.class);
        String type = json.get("type").getAsString();
        long seq = json.get("seq").getAsLong();
        long ts = json.get("ts_utc_ms").getAsLong();

        return switch (type) {
            case "base_found" -> {
                ChunkId id = new ChunkId(
                        json.get("chunk_x").getAsInt(),
                        json.get("chunk_z").getAsInt(),
                        Dimension.fromLegacyName(json.get("dimension").getAsString()));
                yield new BaseFound(
                        seq, ts, id,
                        BaseType.valueOf(json.get("base_type").getAsString()),
                        json.get("score").getAsDouble(),
                        json.get("world_x").getAsInt(),
                        json.get("world_y").getAsInt(),
                        json.get("world_z").getAsInt());
            }
            case "bot_tick" -> new BotTick(
                    seq, ts,
                    json.get("pos_x").getAsInt(),
                    json.get("pos_y").getAsInt(),
                    json.get("pos_z").getAsInt(),
                    json.get("dimension").getAsString(),
                    json.get("hp").getAsInt(),
                    json.get("tps").getAsDouble(),
                    json.get("scanned_chunks").getAsInt(),
                    json.get("bases_found").getAsInt(),
                    json.get("flying").getAsBoolean(),
                    json.get("flight_state").getAsString(),
                    json.get("wp_index").getAsInt(),
                    json.get("wp_total").getAsInt());
            case "chunks_scanned_batch" -> {
                JsonArray arr = json.getAsJsonArray("chunks");
                long[] chunks = new long[arr.size()];
                for (int i = 0; i < arr.size(); i++) chunks[i] = arr.get(i).getAsLong();
                yield new ChunksScannedBatch(
                        seq, ts,
                        json.get("dimension").getAsString(),
                        chunks);
            }
            default -> throw new IllegalArgumentException("Unknown event type: " + type);
        };
    }
}
