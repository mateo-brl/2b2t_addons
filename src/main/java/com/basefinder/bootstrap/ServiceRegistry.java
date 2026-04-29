package com.basefinder.bootstrap;

import com.basefinder.adapter.baritone.BaritoneApi;
import com.basefinder.adapter.io.telemetry.NdjsonFileSink;
import com.basefinder.application.telemetry.EmitBaseFoundUseCase;
import com.basefinder.application.telemetry.EmitBotTickUseCase;
import com.basefinder.application.telemetry.EventSequenceCounter;
import com.basefinder.application.telemetry.TelemetrySink;
import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.logger.DiscordNotifier;
import com.basefinder.scanner.ChunkScanner;

import java.nio.file.Path;

/**
 * Composition root.
 *
 * Une seule instance des services partagés entre modules. Avant cette classe :
 * 4× ElytraBot, 2× ChunkScanner, 2× BaseLogger, 2× DiscordNotifier. Voir
 * audit/01-domain-map.md §6 et audit/05-target-architecture.md §5 étape 2.
 *
 * Construit dans BaseFinderPlugin.onLoad() après que RusherHack ait initialisé
 * Minecraft.getInstance() (BaseLogger touche au file system du game dir).
 */
public final class ServiceRegistry {

    private final DiscordNotifier discordNotifier;
    private final BaseLogger baseLogger;
    private final ElytraBot elytraBot;
    private final ChunkScanner chunkScanner;
    private final BaritoneApi baritoneApi;
    private final TelemetrySink telemetrySink;
    private final EventSequenceCounter eventSequence;
    private final EmitBaseFoundUseCase emitBaseFoundUseCase;
    private final EmitBotTickUseCase emitBotTickUseCase;

    /**
     * @param telemetryFile chemin du fichier NDJSON de télémétrie ; si {@code null}
     *                      le sink est NOOP (utile pour tests).
     */
    public ServiceRegistry(Path telemetryFile) {
        this.discordNotifier = new DiscordNotifier();
        this.elytraBot = new ElytraBot();
        this.chunkScanner = new ChunkScanner();
        this.baritoneApi = new BaritoneApi();
        this.telemetrySink = telemetryFile != null
                ? new NdjsonFileSink(telemetryFile)
                : TelemetrySink.NOOP;
        this.eventSequence = new EventSequenceCounter();
        this.emitBaseFoundUseCase = new EmitBaseFoundUseCase(telemetrySink, eventSequence);
        this.emitBotTickUseCase = new EmitBotTickUseCase(telemetrySink, eventSequence);
        this.baseLogger = new BaseLogger(discordNotifier, emitBaseFoundUseCase);
    }

    /** Surcharge sans télémétrie — utile pour les tests d'intégration. */
    public ServiceRegistry() {
        this(null);
    }

    public DiscordNotifier discordNotifier() { return discordNotifier; }
    public BaseLogger baseLogger() { return baseLogger; }
    public ElytraBot elytraBot() { return elytraBot; }
    public ChunkScanner chunkScanner() { return chunkScanner; }
    public BaritoneApi baritoneApi() { return baritoneApi; }
    public TelemetrySink telemetrySink() { return telemetrySink; }
    public EventSequenceCounter eventSequence() { return eventSequence; }
    public EmitBaseFoundUseCase emitBaseFoundUseCase() { return emitBaseFoundUseCase; }
    public EmitBotTickUseCase emitBotTickUseCase() { return emitBotTickUseCase; }
}
