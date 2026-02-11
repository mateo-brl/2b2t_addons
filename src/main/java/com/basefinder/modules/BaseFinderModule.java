package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.navigation.NavigationHelper;
import com.basefinder.scanner.ChunkScanner;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;
import org.rusherhack.core.setting.NullSetting;

import java.util.List;

/**
 * Main BaseFinder module - orchestrates the entire automated base hunting process.
 *
 * State machine:
 * SCANNING -> TRAIL_FOLLOWING -> FLYING -> SCANNING -> ...
 *
 * When enabled, it:
 * 1. Scans loaded chunks for player activity
 * 2. If a trail is found, follows it
 * 3. Uses elytra + fireworks to navigate between waypoints
 * 4. Logs all found bases to file and chat
 */
public class BaseFinderModule extends ToggleableModule {

    // Components
    private final ChunkScanner scanner = new ChunkScanner();
    private final TrailFollower trailFollower = new TrailFollower();
    private final ElytraBot elytraBot = new ElytraBot();
    private final NavigationHelper navigation = new NavigationHelper();
    private final BaseLogger logger = new BaseLogger();

    // State
    private FinderState state = FinderState.IDLE;
    private int tickCounter = 0;
    private int scanInterval = 20; // scan every second
    private int investigationStartTick = 0;

    // === Settings ===

    // --- MODE : Paramètres de recherche ---
    private final NullSetting modeGroup = new NullSetting("Mode");
    private final BooleanSetting useElytra = new BooleanSetting("Vol Elytra", "Vol automatique entre les zones", true);
    private final StringSetting searchMode = new StringSetting("Mode de recherche", "SPIRAL");

    // --- DÉTECTION : Quoi chercher ---
    private final NullSetting detectGroup = new NullSetting("Détection");
    private final BooleanSetting detectConstruction = new BooleanSetting("Bases", "Structures construites par des joueurs", true);
    private final BooleanSetting detectStorage = new BooleanSetting("Stashes", "Shulkers, ender chests, stockage", true);
    private final BooleanSetting detectMapArt = new BooleanSetting("Map Art", true);
    private final BooleanSetting detectTrails = new BooleanSetting("Pistes", "Autoroutes de glace, chemins d'obsidienne", true);
    private final NumberSetting<Double> minScore = new NumberSetting<>("Sensibilité", 25.0, 5.0, 200.0);

    // --- VOL : Paramètres elytra ---
    private final NullSetting flightGroup = new NullSetting("Vol");
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude", 200.0, 50.0, 350.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Atterrir sous", 100.0, 30.0, 200.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Délai fusées", 40, 10, 100);
    private final NumberSetting<Integer> minElytraDurability = new NumberSetting<>("Durabilité échange", 10, 1, 100);

    // --- AVANCÉ : Réglages fins ---
    private final NullSetting advancedGroup = new NullSetting("Avancé");
    private final BooleanSetting followTrails = new BooleanSetting("Suivi auto pistes", true);
    private final BooleanSetting useChunkTrails = new BooleanSetting("Détection âge chunks", true);
    private final BooleanSetting useVersionBorders = new BooleanSetting("Bordures de version", true);
    private final NumberSetting<Integer> scanIntervalSetting = new NumberSetting<>("Vitesse scan", 20, 5, 100);
    private final NumberSetting<Double> waypointThreshold = new NumberSetting<>("Rayon waypoint", 100.0, 20.0, 500.0);
    private final NumberSetting<Double> spiralStep = new NumberSetting<>("Espacement waypoints", 500.0, 100.0, 5000.0);
    private final NumberSetting<Double> spiralRadius = new NumberSetting<>("Rayon anneau", 5000.0, 500.0, 200000.0);
    private final NumberSetting<Integer> searchMinDist = new NumberSetting<>("Distance min aléatoire", 5000, 100, 50000);
    private final NumberSetting<Integer> searchMaxDist = new NumberSetting<>("Distance max aléatoire", 100000, 10000, 500000);
    private final NumberSetting<Integer> highwayDist = new NumberSetting<>("Distance autoroute", 100000, 10000, 500000);
    private final NumberSetting<Integer> highwayInterval = new NumberSetting<>("Intervalle autoroute", 1000, 100, 10000);

    // --- LOG ---
    private final BooleanSetting logToChat = new BooleanSetting("Alertes chat", true);
    private final BooleanSetting logToFile = new BooleanSetting("Sauvegarder", true);

    public enum FinderState {
        IDLE,
        SCANNING,
        TRAIL_FOLLOWING,
        FLYING_TO_WAYPOINT,
        INVESTIGATING,
        PAUSED
    }

    public BaseFinderModule() {
        super("BaseHunter", "Chasse de bases automatique - scan des chunks, suivi de pistes, vol elytra", ModuleCategory.EXTERNAL);

        // Register settings with groups
        modeGroup.addSubSettings(useElytra, searchMode);
        detectGroup.addSubSettings(detectConstruction, detectStorage, detectMapArt, detectTrails, minScore);
        flightGroup.addSubSettings(cruiseAltitude, minAltitude, fireworkInterval, minElytraDurability);
        advancedGroup.addSubSettings(followTrails, useChunkTrails, useVersionBorders, scanIntervalSetting,
                waypointThreshold, spiralStep, spiralRadius, searchMinDist, searchMaxDist, highwayDist, highwayInterval);

        this.registerSettings(
                modeGroup,
                detectGroup,
                flightGroup,
                advancedGroup,
                logToChat,
                logToFile
        );
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[BaseHunter] Vous devez être dans un monde !");
            this.toggle();
            return;
        }

        // Apply settings to components
        applySettings();

        // Connect to NewChunks module if it's active
        connectToNewChunksModule();

        // Initialize navigation with selected search mode
        NavigationHelper.SearchPattern pattern;
        try {
            pattern = NavigationHelper.SearchPattern.valueOf(searchMode.getValue().toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            ChatUtils.print("[BaseHunter] Mode inconnu : " + searchMode.getValue());
            ChatUtils.print("[BaseHunter] Disponibles : SPIRAL, HIGHWAYS, RANDOM, RING");
            pattern = NavigationHelper.SearchPattern.SPIRAL;
        }

        // Apply ring radius
        navigation.setSpiralRadius(spiralRadius.getValue());
        navigation.initializeSearch(pattern, mc.player.blockPosition());

        state = FinderState.SCANNING;
        tickCounter = 0;
        scanner.reset();

        // Afficher info du mode
        String modeDesc = switch (pattern) {
            case SPIRAL -> "Spirale depuis votre position";
            case HIGHWAYS -> "Suivre les 8 autoroutes (cardinales + diagonales)";
            case RANDOM -> "Positions aléatoires entre " + searchMinDist.getValue() + " et " + searchMaxDist.getValue() + " blocs";
            case RING -> "Cercle à " + String.format("%.0f", spiralRadius.getValue()) + " blocs de rayon";
            case CUSTOM -> "Waypoints personnalisés";
        };
        ChatUtils.print("[BaseHunter] Démarré ! Mode : " + pattern.name());
        ChatUtils.print("[BaseHunter] " + modeDesc);
        ChatUtils.print("[BaseHunter] " + navigation.getWaypointCount() + " waypoints générés. Cliquez [ALLER] sur les alertes pour naviguer avec Baritone.");
    }

    @Override
    public void onDisable() {
        state = FinderState.IDLE;
        elytraBot.stop();
        trailFollower.stopFollowing();

        // Release movement keys
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);

        if (mc.level != null) {
            ChatUtils.print("[BaseHunter] Arrêté. " + logger.getCount() + " bases trouvées. " + scanner.getScannedCount() + " chunks scannés.");
        }
    }

    private void applySettings() {
        scanner.setMinScore(minScore.getValue());
        scanner.setDetectConstruction(detectConstruction.getValue());
        scanner.setDetectStorage(detectStorage.getValue());
        scanner.setDetectMapArt(detectMapArt.getValue());
        scanner.setDetectTrails(detectTrails.getValue());

        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setMinAltitude(minAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setMinElytraDurability(minElytraDurability.getValue());

        navigation.setSpiralStep(spiralStep.getValue());
        navigation.setSearchMinDistance(searchMinDist.getValue());
        navigation.setSearchMaxDistance(searchMaxDist.getValue());
        navigation.setHighwayDistance(highwayDist.getValue());
        navigation.setHighwayCheckInterval(highwayInterval.getValue());

        logger.setLogToChat(logToChat.getValue());
        logger.setLogToFile(logToFile.getValue());

        scanInterval = scanIntervalSetting.getValue();
    }

    /**
     * Connect TrailFollower to NewChunks module's detector and analyzer
     * so it can use chunk age data for trail detection.
     */
    private void connectToNewChunksModule() {
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("ChunkHistory").orElse(null);
        if (ncModule instanceof NewChunksModule newChunksModule) {
            if (useChunkTrails.getValue()) {
                trailFollower.setNewChunkDetector(newChunksModule.getDetector());
                ChatUtils.print("[BaseHunter] Connecté à NewChunks - détection pistes de chunks activée");
            }
            if (useVersionBorders.getValue()) {
                trailFollower.setChunkAgeAnalyzer(newChunksModule.getAgeAnalyzer());
                ChatUtils.print("[BaseHunter] Connecté à NewChunks - détection bordures de version activée");
            }
        } else {
            if (useChunkTrails.getValue() || useVersionBorders.getValue()) {
                ChatUtils.print("[BaseHunter] Activez le module NewChunks pour la détection des pistes et bordures");
            }
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        navigation.updateTracking();

        switch (state) {
            case SCANNING -> handleScanning();
            case TRAIL_FOLLOWING -> handleTrailFollowing();
            case FLYING_TO_WAYPOINT -> handleFlying();
            case INVESTIGATING -> handleInvestigating();
            case PAUSED, IDLE -> {}
        }
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BaseFinder");

    private void handleScanning() {
        // Scan chunks periodically
        if (tickCounter % scanInterval != 0) return;

        LOGGER.info("[BaseHunter] handleScanning called, tickCounter={}", tickCounter);

        List<ChunkAnalysis> newFinds;
        try {
            newFinds = scanner.scanLoadedChunks();
            LOGGER.info("[BaseHunter] scanLoadedChunks returned {} finds, total scanned: {}",
                newFinds != null ? newFinds.size() : "null", scanner.getScannedCount());
        } catch (Exception e) {
            LOGGER.error("[BaseHunter] Error in scanLoadedChunks: {}", e.getMessage());
            e.printStackTrace();
            return;
        }

        // Periodic status update every 10 seconds
        if (tickCounter % 200 == 0) {
            ChatUtils.print("[BaseHunter] Scannés : " + scanner.getScannedCount() + " chunks | Trouvés : " + logger.getCount() + " bases");
        }

        for (ChunkAnalysis analysis : newFinds) {
            if (analysis.getBaseType() != BaseType.TRAIL) {
                BaseRecord record = new BaseRecord(
                        analysis.getCenterBlockPos(),
                        analysis.getBaseType(),
                        analysis.getScore(),
                        analysis.getPlayerBlockCount(),
                        analysis.getStorageCount(),
                        analysis.getShulkerCount()
                );
                logger.logBase(record);
            }
        }

        // Check for trails to follow
        if (followTrails.getValue() && !scanner.getTrailChunks().isEmpty()) {
            if (trailFollower.detectTrail(scanner.getTrailChunks())) {
                state = FinderState.TRAIL_FOLLOWING;
                ChatUtils.print("[BaseHunter] Piste détectée ! Poursuite...");
                return;
            }
        }

        // If scanning is done (all loaded chunks scanned), fly to next waypoint
        if (useElytra.getValue() && navigation.getCurrentTarget() != null) {
            state = FinderState.FLYING_TO_WAYPOINT;
            elytraBot.startFlight(navigation.getCurrentTarget());
        }
    }

    private void handleTrailFollowing() {
        BlockPos target = trailFollower.getNextTrailTarget();

        if (target == null) {
            // Piste perdue
            ChatUtils.print("[BaseHunter] Piste perdue après " + trailFollower.getTrailLength() + " blocs. Reprise du scan.");
            trailFollower.stopFollowing();
            state = FinderState.SCANNING;
            return;
        }

        // Walk/fly towards trail target
        if (useElytra.getValue() && mc.player != null && mc.player.isFallFlying()) {
            elytraBot.startFlight(target);
            elytraBot.tick();
        } else {
            // Ground movement - set player rotation towards target
            if (mc.player != null) {
                float yaw = trailFollower.getTrailYaw();
                mc.player.setYRot(yaw);
                // Auto-sprint forward
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
            }
        }

        // Keep scanning while following trail
        if (tickCounter % scanInterval == 0) {
            List<ChunkAnalysis> newFinds = scanner.scanLoadedChunks();
            for (ChunkAnalysis analysis : newFinds) {
                if (analysis.getBaseType() != BaseType.TRAIL && analysis.getBaseType() != BaseType.NONE) {
                    BaseRecord record = new BaseRecord(
                            analysis.getCenterBlockPos(),
                            analysis.getBaseType(),
                            analysis.getScore(),
                            analysis.getPlayerBlockCount(),
                            analysis.getStorageCount(),
                            analysis.getShulkerCount()
                    );
                    logger.logBase(record);

                    // Si on trouve quelque chose d'important, investiguer
                    if (analysis.getScore() >= minScore.getValue() * 2) {
                        state = FinderState.INVESTIGATING;
                        investigationStartTick = tickCounter;
                        ChatUtils.print("[BaseHunter] Découverte importante ! Investigation...");
                        return;
                    }
                }
            }
        }
    }

    private void handleFlying() {
        elytraBot.tick();

        // Keep scanning while flying
        if (tickCounter % scanInterval == 0) {
            List<ChunkAnalysis> newFinds = scanner.scanLoadedChunks();
            for (ChunkAnalysis analysis : newFinds) {
                if (analysis.getBaseType() != BaseType.NONE && analysis.getBaseType() != BaseType.TRAIL) {
                    BaseRecord record = new BaseRecord(
                            analysis.getCenterBlockPos(),
                            analysis.getBaseType(),
                            analysis.getScore(),
                            analysis.getPlayerBlockCount(),
                            analysis.getStorageCount(),
                            analysis.getShulkerCount()
                    );
                    logger.logBase(record);
                }
            }

            // Check for trails
            if (followTrails.getValue() && !scanner.getTrailChunks().isEmpty()) {
                if (trailFollower.detectTrail(scanner.getTrailChunks())) {
                    elytraBot.stop();
                    state = FinderState.TRAIL_FOLLOWING;
                    ChatUtils.print("[BaseHunter] Piste détectée en vol ! Poursuite...");
                    return;
                }
            }
        }

        // Check if we reached the waypoint
        if (navigation.isNearTarget(waypointThreshold.getValue())) {
            ChatUtils.print("[BaseHunter] Waypoint atteint " + (navigation.getCurrentWaypointIndex() + 1) + "/" + navigation.getWaypointCount());
            if (!navigation.advanceToNext()) {
                ChatUtils.print("[BaseHunter] Tous les waypoints visités ! Bases trouvées : " + logger.getCount());
                this.toggle();
                return;
            }
            state = FinderState.SCANNING;
            elytraBot.stop();
        }

        // Handle elytra bot issues
        if (!elytraBot.isFlying() && useElytra.getValue()) {
            // Try to restart flight
            if (navigation.getCurrentTarget() != null) {
                elytraBot.startFlight(navigation.getCurrentTarget());
            }
        }
    }

    private void handleInvestigating() {
        // Scan the area thoroughly
        if (tickCounter % 10 == 0) {
            scanner.scanLoadedChunks();
        }

        // Stay and investigate for 5 seconds (100 ticks), then continue
        if (tickCounter - investigationStartTick >= 100) {
            state = FinderState.SCANNING;
            ChatUtils.print("[BaseHunter] Investigation terminée. Reprise de la recherche.");
        }
    }

    // Public accessors for HUD and commands
    public FinderState getState() { return state; }
    public ChunkScanner getScanner() { return scanner; }
    public TrailFollower getTrailFollower() { return trailFollower; }
    public ElytraBot getElytraBot() { return elytraBot; }
    public NavigationHelper getNavigation() { return navigation; }
    public BaseLogger getBaseLogger() { return logger; }

    public void pause() {
        if (state != FinderState.IDLE && state != FinderState.PAUSED) {
            state = FinderState.PAUSED;
            elytraBot.stop();
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            ChatUtils.print("[BaseHunter] En pause.");
        }
    }

    public void resume() {
        if (state == FinderState.PAUSED) {
            state = FinderState.SCANNING;
            ChatUtils.print("[BaseHunter] Repris.");
        }
    }

    public void skipWaypoint() {
        if (navigation.advanceToNext()) {
            ChatUtils.print("[BaseHunter] Sauté au waypoint " + (navigation.getCurrentWaypointIndex() + 1));
            state = FinderState.SCANNING;
        }
    }
}
