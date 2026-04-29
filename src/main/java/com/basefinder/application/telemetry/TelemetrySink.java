package com.basefinder.application.telemetry;

import com.basefinder.domain.event.BotEvent;

/**
 * Port de sortie pour les événements de télémétrie.
 *
 * Implémentations :
 * <ul>
 *   <li>{@code NdjsonFileSink} (v1) : écrit dans un fichier local pour le dev.</li>
 *   <li>{@code WebSocketSink} (v2, étape 7) : envoie vers backend dashboard.</li>
 *   <li>{@code BufferingSink} (futur) : buffer en RAM si réseau down.</li>
 *   <li>{@code FakeSink} (tests) : capture en mémoire.</li>
 * </ul>
 *
 * Contrat : {@link #publish} ne doit jamais lever — un échec d'IO est logué
 * mais n'interrompt pas le bot. Audit/05 §1.3 (events ≠ état mutable).
 */
public interface TelemetrySink extends AutoCloseable {

    void publish(BotEvent event);

    @Override
    default void close() {
        // Most sinks are stateless; override if you hold a resource.
    }

    /**
     * Sink "no-op" — utilisé quand la télémétrie est désactivée.
     */
    TelemetrySink NOOP = new TelemetrySink() {
        @Override
        public void publish(BotEvent event) {
            // discard
        }
    };
}
