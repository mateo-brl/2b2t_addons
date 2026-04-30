package com.basefinder.domain.event;

/**
 * Sealed interface des événements émis par le bot vers les sinks de télémétrie.
 *
 * Audit/05 §3 : chaque événement transporte un {@code seq} monotone par bot et
 * une {@code idempotencyKey} stable, pour permettre au backend de dédupliquer.
 *
 * v1 (MVP) : sérialisé en NDJSON via {@code EventSerializer}.
 * v2 (futur) : protobuf, même structure, schémas versionnés par event.
 */
public sealed interface BotEvent
        permits BaseFound, BotTick, ChunksScannedBatch {

    /** Type d'événement (string court — utilisé comme discriminant JSON). */
    String type();

    /** Numéro de séquence monotone par bot (audit/05 §3.1). */
    long seq();

    /** Timestamp UTC en millisecondes depuis l'epoch. */
    long tsUtcMs();

    /** Clé idempotente stable — backend dédupliquera sur reconnexion/replay. */
    String idempotencyKey();
}
