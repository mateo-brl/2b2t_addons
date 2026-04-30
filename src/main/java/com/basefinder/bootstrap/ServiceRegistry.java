package com.basefinder.bootstrap;

import com.basefinder.adapter.baritone.BaritoneApi;
import com.basefinder.adapter.io.telemetry.CompositeSink;
import com.basefinder.adapter.io.telemetry.HttpJsonLineSink;
import com.basefinder.adapter.io.telemetry.NdjsonFileSink;
import com.basefinder.adapter.io.commands.CommandPoller;
import com.basefinder.adapter.io.screenshots.ScreenshotUploader;
import com.basefinder.adapter.io.zones.ZonePoller;
import com.basefinder.adapter.mc.McChunkSource;
import com.basefinder.application.scan.ChunkScannerService;
import com.basefinder.application.scan.ChunkSource;
import com.basefinder.application.telemetry.EmitBaseFoundUseCase;
import com.basefinder.application.telemetry.EmitBotTickUseCase;
import com.basefinder.application.telemetry.EmitChunksScannedUseCase;
import com.basefinder.application.telemetry.EventSequenceCounter;
import com.basefinder.application.telemetry.TelemetrySink;
import com.basefinder.domain.zone.ZoneFilter;
import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.logger.DiscordNotifier;
import com.basefinder.scanner.ChunkScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private final EmitChunksScannedUseCase emitChunksScannedUseCase;
    private final ChunkSource chunkSource;
    private final ChunkScannerService chunkScannerService;
    private final ZoneFilter zoneFilter;
    private final ZonePoller zonePoller;
    private final CommandPoller commandPoller;
    private final ScreenshotUploader screenshotUploader;

    /**
     * @param telemetryFile chemin du fichier NDJSON de télémétrie ; si {@code null}
     *                      le sink est NOOP (utile pour tests).
     */
    public ServiceRegistry(Path telemetryFile) {
        this.discordNotifier = new DiscordNotifier();
        this.elytraBot = new ElytraBot();
        this.chunkScanner = new ChunkScanner();
        this.baritoneApi = new BaritoneApi();
        this.telemetrySink = buildTelemetrySink(telemetryFile);
        this.eventSequence = new EventSequenceCounter();
        this.emitBaseFoundUseCase = new EmitBaseFoundUseCase(telemetrySink, eventSequence);
        this.emitBotTickUseCase = new EmitBotTickUseCase(telemetrySink, eventSequence);
        this.emitChunksScannedUseCase = new EmitChunksScannedUseCase(telemetrySink, eventSequence);
        this.baseLogger = new BaseLogger(discordNotifier, emitBaseFoundUseCase);
        this.chunkSource = new McChunkSource();
        this.chunkScannerService = new ChunkScannerService(chunkSource);
        this.zoneFilter = new ZoneFilter();
        this.chunkScanner.setZoneFilter(zoneFilter);
        String backendUrl = System.getProperty("basefinder.backend.url");
        if (backendUrl != null && !backendUrl.isBlank()) {
            String trimmedUrl = backendUrl.trim();
            this.zonePoller = new ZonePoller(trimmedUrl, zoneFilter);
            this.commandPoller = new CommandPoller(trimmedUrl);
            this.screenshotUploader = new ScreenshotUploader(trimmedUrl);
            zonePoller.start();
            commandPoller.start();
            this.baseLogger.setScreenshotUploader(screenshotUploader);
        } else {
            this.zonePoller = null;
            this.commandPoller = null;
            this.screenshotUploader = null;
        }
    }

    /**
     * Combine NDJSON file sink (always on if file given) + HTTP sink (if
     * {@code -Dbasefinder.backend.url=...} is set, e.g. {@code http://127.0.0.1:8080}).
     * NOOP if neither is configured.
     */
    private static TelemetrySink buildTelemetrySink(Path telemetryFile) {
        List<TelemetrySink> sinks = new ArrayList<>(2);
        if (telemetryFile != null) {
            sinks.add(new NdjsonFileSink(telemetryFile));
        }
        String backendUrl = System.getProperty("basefinder.backend.url");
        if (backendUrl != null && !backendUrl.isBlank()) {
            sinks.add(new HttpJsonLineSink(backendUrl.trim()));
        }
        if (sinks.isEmpty()) return TelemetrySink.NOOP;
        if (sinks.size() == 1) return sinks.get(0);
        return new CompositeSink(sinks);
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
    public EmitChunksScannedUseCase emitChunksScannedUseCase() { return emitChunksScannedUseCase; }
    public ChunkSource chunkSource() { return chunkSource; }
    public ChunkScannerService chunkScannerService() { return chunkScannerService; }
    public ZoneFilter zoneFilter() { return zoneFilter; }
    public ZonePoller zonePoller() { return zonePoller; }
    public CommandPoller commandPoller() { return commandPoller; }
    public ScreenshotUploader screenshotUploader() { return screenshotUploader; }
}
