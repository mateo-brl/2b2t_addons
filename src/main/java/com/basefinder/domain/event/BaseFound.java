package com.basefinder.domain.event;

import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.world.ChunkId;

/**
 * Événement émis quand le scanner identifie une nouvelle base sur 2b2t.
 *
 * Idempotency key = chunkId + baseType (audit/05 §3.2) → si on rescanne le
 * même chunk, le backend ne crée pas un doublon.
 *
 * @param chunkId   chunk dans lequel la base est détectée
 * @param baseType  classification (STASH, STORAGE, CONSTRUCTION, etc.)
 * @param score     score numérique post-pénalités
 * @param worldX    coordonnée bloc X (centre du chunk ou plus précise si dispo)
 * @param worldY    coordonnée bloc Y
 * @param worldZ    coordonnée bloc Z
 */
public record BaseFound(
        long seq,
        long tsUtcMs,
        ChunkId chunkId,
        BaseType baseType,
        double score,
        int worldX,
        int worldY,
        int worldZ
) implements BotEvent {

    @Override
    public String type() {
        return "base_found";
    }

    @Override
    public String idempotencyKey() {
        return chunkId.dim().legacyName() + ":" + chunkId.x() + ":" + chunkId.z() + ":" + baseType.name();
    }
}
