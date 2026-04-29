package com.basefinder.bootstrap;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.logger.DiscordNotifier;
import com.basefinder.scanner.ChunkScanner;

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

    public ServiceRegistry() {
        this.discordNotifier = new DiscordNotifier();
        this.baseLogger = new BaseLogger(discordNotifier);
        this.elytraBot = new ElytraBot();
        this.chunkScanner = new ChunkScanner();
    }

    public DiscordNotifier discordNotifier() { return discordNotifier; }
    public BaseLogger baseLogger() { return baseLogger; }
    public ElytraBot elytraBot() { return elytraBot; }
    public ChunkScanner chunkScanner() { return chunkScanner; }
}
