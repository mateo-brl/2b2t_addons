package com.basefinder.application.telemetry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Source de numéros de séquence monotones pour les {@link com.basefinder.domain.event.BotEvent}.
 *
 * Un compteur partagé par bot (≈ tout le module BaseFinder dans une JVM).
 * Resetté à zéro au démarrage de la session — on accepte les recoupements
 * inter-sessions car {@code session_id + seq} est unique côté backend.
 */
public final class EventSequenceCounter {

    private final AtomicLong counter = new AtomicLong(0);

    public long next() {
        return counter.getAndIncrement();
    }

    public long current() {
        return counter.get();
    }
}
