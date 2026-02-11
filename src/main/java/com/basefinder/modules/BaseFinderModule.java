package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.navigation.NavigationHelper;
import com.basefinder.scanner.ChunkScanner;
import com.basefinder.scanner.FreshnessEstimator;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.Lang;
import com.basefinder.util.WaypointExporter;
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
 * 1. Scans loaded chunks for player activity (blocks + entities)
 * 2. If a trail is found, follows it
 * 3. Uses elytra + fireworks to navigate between waypoints
 * 4. Logs all found bases to file and chat
 * 5. Estimates freshness (active/abandoned/ancient)
 * 6. Auto-screenshots on detection
 */
public class BaseFinderModule extends ToggleableModule {

    // Components
    private final ChunkScanner scanner = new ChunkScanner();
    private final TrailFollower trailFollower = new TrailFollower();
    private final ElytraBot elytraBot = new ElytraBot();
    private final NavigationHelper navigation = new NavigationHelper();
    private final BaseLogger logger = new BaseLogger();
    private final FreshnessEstimator freshnessEstimator = new FreshnessEstimator();

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

    // --- OPTIMISATIONS : Nouvelles fonctionnalités ---
    private final NullSetting optimGroup = new NullSetting("Optimisations");
    private final BooleanSetting useEntityScanning = new BooleanSetting("Scan entités", true);
    private final BooleanSetting useClusterScoring = new BooleanSetting("Score cluster", true);
    private final BooleanSetting autoScreenshot = new BooleanSetting("Auto capture", false);
    private final BooleanSetting antiKickNoise = new BooleanSetting("Anti-kick bruit", true);

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

    // --- LANGUE / LANGUAGE ---
    private final BooleanSetting langFr = new BooleanSetting("Français", true);

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
        optimGroup.addSubSettings(useEntityScanning, useClusterScoring, autoScreenshot, antiKickNoise);
        flightGroup.addSubSettings(cruiseAltitude, minAltitude, fireworkInterval, minElytraDurability);
        advancedGroup.addSubSettings(followTrails, useChunkTrails, useVersionBorders, scanIntervalSetting,
                waypointThreshold, spiralStep, spiralRadius, searchMinDist, searchMaxDist, highwayDist, highwayInterval);

        this.registerSettings(
                modeGroup,
                detectGroup,
                optimGroup,
                flightGroup,
                advancedGroup,
                logToChat,
                logToFile,
                langFr
        );
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Must be in a world!", "Vous devez être dans un monde !"));
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
            ChatUtils.print("[BaseHunter] " + Lang.t("Unknown mode: ", "Mode inconnu : ") + searchMode.getValue());
            ChatUtils.print("[BaseHunter] " + Lang.t("Available: SPIRAL, HIGHWAYS, RANDOM, RING", "Disponibles : SPIRAL, HIGHWAYS, RANDOM, RING"));
            pattern = NavigationHelper.SearchPattern.SPIRAL;
        }

        // Apply ring radius
        navigation.setSpiralRadius(spiralRadius.getValue());
        navigation.initializeSearch(pattern, mc.player.blockPosition());

        state = FinderState.SCANNING;
        tickCounter = 0;
        scanner.reset();

        // Show mode info
        String modeDesc = switch (pattern) {
            case SPIRAL -> Lang.t("Spiral outward from your position", "Spirale depuis votre position");
            case HIGHWAYS -> Lang.t("Follow all 8 highways (cardinal + diagonal)", "Suivre les 8 autoroutes (cardinales + diagonales)");
            case RANDOM -> Lang.t("Random positions within ", "Positions aléatoires entre ") + searchMinDist.getValue() + Lang.t("-", " et ") + searchMaxDist.getValue() + Lang.t(" blocks", " blocs");
            case RING -> Lang.t("Circle at ", "Cercle à ") + String.format("%.0f", spiralRadius.getValue()) + Lang.t(" blocks radius", " blocs de rayon");
            case CUSTOM -> Lang.t("Custom waypoints", "Waypoints personnalisés");
        };
        ChatUtils.print("[BaseHunter] " + Lang.t("Started! Mode: ", "Démarré ! Mode : ") + pattern.name());
        ChatUtils.print("[BaseHunter] " + modeDesc);

        // Show optimization status
        StringBuilder optimStatus = new StringBuilder();
        if (useEntityScanning.getValue()) optimStatus.append(Lang.t("Entities", "Entités")).append(" ");
        if (useClusterScoring.getValue()) optimStatus.append(Lang.t("Clusters", "Clusters")).append(" ");
        if (autoScreenshot.getValue()) optimStatus.append(Lang.t("Screenshot", "Capture")).append(" ");
        if (antiKickNoise.getValue()) optimStatus.append(Lang.t("AntiKick", "AntiKick")).append(" ");
        if (!optimStatus.isEmpty()) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Optimizations: ", "Optimisations : ") + optimStatus.toString().trim());
        }

        ChatUtils.print("[BaseHunter] " + navigation.getWaypointCount() + Lang.t(" waypoints generated. Click [GOTO] on alerts to navigate with Baritone.", " waypoints générés. Cliquez [ALLER] sur les alertes pour naviguer avec Baritone."));
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
            ChatUtils.print("[BaseHunter] " + Lang.t("Stopped. Found ", "Arrêté. ") + logger.getCount() + Lang.t(" bases. Scanned ", " bases trouvées. ") + scanner.getScannedCount() + Lang.t(" chunks.", " chunks scannés."));
        }
    }

    private void applySettings() {
        scanner.setMinScore(minScore.getValue());
        scanner.setDetectConstruction(detectConstruction.getValue());
        scanner.setDetectStorage(detectStorage.getValue());
        scanner.setDetectMapArt(detectMapArt.getValue());
        scanner.setDetectTrails(detectTrails.getValue());

        // New optimization settings
        scanner.setUseEntityScanning(useEntityScanning.getValue());
        scanner.setUseClusterScoring(useClusterScoring.getValue());
        scanner.setFreshnessEstimator(freshnessEstimator);

        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setMinAltitude(minAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setMinElytraDurability(minElytraDurability.getValue());
        elytraBot.setUseFlightNoise(antiKickNoise.getValue());

        navigation.setSpiralStep(spiralStep.getValue());
        navigation.setSearchMinDistance(searchMinDist.getValue());
        navigation.setSearchMaxDistance(searchMaxDist.getValue());
        navigation.setHighwayDistance(highwayDist.getValue());
        navigation.setHighwayCheckInterval(highwayInterval.getValue());

        logger.setLogToChat(logToChat.getValue());
        logger.setLogToFile(logToFile.getValue());
        logger.setAutoScreenshot(autoScreenshot.getValue());

        scanInterval = scanIntervalSetting.getValue();
    }

    /**
     * Connect TrailFollower and FreshnessEstimator to NewChunks module's detector and analyzer
     * so they can use chunk age data for trail detection and freshness estimation.
     */
    private void connectToNewChunksModule() {
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("ChunkHistory").orElse(null);
        if (ncModule instanceof NewChunksModule newChunksModule) {
            if (useChunkTrails.getValue()) {
                trailFollower.setNewChunkDetector(newChunksModule.getDetector());
                ChatUtils.print("[BaseHunter] " + Lang.t("Connected to NewChunks - chunk trail detection enabled", "Connecté à NewChunks - détection pistes de chunks activée"));
            }
            if (useVersionBorders.getValue()) {
                trailFollower.setChunkAgeAnalyzer(newChunksModule.getAgeAnalyzer());
                ChatUtils.print("[BaseHunter] " + Lang.t("Connected to NewChunks - version border detection enabled", "Connecté à NewChunks - détection bordures de version activée"));
            }

            // Connect freshness estimator
            freshnessEstimator.setNewChunkDetector(newChunksModule.getDetector());
            freshnessEstimator.setChunkAgeAnalyzer(newChunksModule.getAgeAnalyzer());
            ChatUtils.print("[BaseHunter] " + Lang.t("Freshness estimation enabled", "Estimation fraîcheur activée"));
        } else {
            if (useChunkTrails.getValue() || useVersionBorders.getValue()) {
                ChatUtils.print("[BaseHunter] " + Lang.t("Enable NewChunks module for chunk trail & version border detection", "Activez le module NewChunks pour la détection des pistes et bordures"));
            }
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        // Sync language setting
        Lang.setFrench(langFr.getValue());

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
            ChatUtils.print("[BaseHunter] " + Lang.t("Scanned: ", "Scannés : ") + scanner.getScannedCount() + Lang.t(" chunks | Found: ", " chunks | Trouvés : ") + logger.getCount() + " bases");
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
                // Add enriched notes from analysis
                addAnalysisNotes(record, analysis);
                logger.logBase(record);
            }
        }

        // Check for trails to follow
        if (followTrails.getValue() && !scanner.getTrailChunks().isEmpty()) {
            if (trailFollower.detectTrail(scanner.getTrailChunks())) {
                state = FinderState.TRAIL_FOLLOWING;
                ChatUtils.print("[BaseHunter] " + Lang.t("Trail detected! Following...", "Piste détectée ! Poursuite..."));
                return;
            }
        }

        // If scanning is done (all loaded chunks scanned), fly to next waypoint
        if (useElytra.getValue() && navigation.getCurrentTarget() != null) {
            state = FinderState.FLYING_TO_WAYPOINT;
            elytraBot.startFlight(navigation.getCurrentTarget());
        }
    }

    /**
     * Add enriched metadata from analysis to the base record notes.
     */
    private void addAnalysisNotes(BaseRecord record, ChunkAnalysis analysis) {
        StringBuilder notes = new StringBuilder();

        // Freshness
        if (analysis.getFreshness() != ChunkAnalysis.Freshness.UNKNOWN) {
            notes.append(analysis.getFreshness().name());
        }

        // Entity info
        if (analysis.getEntityCount() > 0) {
            if (!notes.isEmpty()) notes.append(", ");
            notes.append("entities:").append(analysis.getEntityCount());
        }

        // Cluster info
        if (analysis.getClusterSize() > 0) {
            if (!notes.isEmpty()) notes.append(", ");
            notes.append("cluster:").append(analysis.getClusterSize());
        }

        // Distance from spawn
        if (analysis.getDistanceFromSpawn() > 0) {
            if (!notes.isEmpty()) notes.append(", ");
            notes.append(String.format("dist:%.0fk", analysis.getDistanceFromSpawn() / 1000));
        }

        if (!notes.isEmpty()) {
            record.setNotes(notes.toString());
        }
    }

    private void handleTrailFollowing() {
        BlockPos target = trailFollower.getNextTrailTarget();

        if (target == null) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Trail lost after ", "Piste perdue après ") + trailFollower.getTrailLength() + Lang.t(" blocks. Resuming scan.", " blocs. Reprise du scan."));
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
                    addAnalysisNotes(record, analysis);
                    logger.logBase(record);

                    // Si on trouve quelque chose d'important, investiguer
                    if (analysis.getScore() >= minScore.getValue() * 2) {
                        state = FinderState.INVESTIGATING;
                        investigationStartTick = tickCounter;
                        ChatUtils.print("[BaseHunter] " + Lang.t("Significant find! Investigating...", "Découverte importante ! Investigation..."));
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
                    addAnalysisNotes(record, analysis);
                    logger.logBase(record);
                }
            }

            // Check for trails
            if (followTrails.getValue() && !scanner.getTrailChunks().isEmpty()) {
                if (trailFollower.detectTrail(scanner.getTrailChunks())) {
                    elytraBot.stop();
                    state = FinderState.TRAIL_FOLLOWING;
                    ChatUtils.print("[BaseHunter] " + Lang.t("Trail detected while flying! Following...", "Piste détectée en vol ! Poursuite..."));
                    return;
                }
            }
        }

        // Check if we reached the waypoint
        if (navigation.isNearTarget(waypointThreshold.getValue())) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Reached waypoint ", "Waypoint atteint ") + (navigation.getCurrentWaypointIndex() + 1) + "/" + navigation.getWaypointCount());
            if (!navigation.advanceToNext()) {
                ChatUtils.print("[BaseHunter] " + Lang.t("All waypoints visited! Total bases found: ", "Tous les waypoints visités ! Bases trouvées : ") + logger.getCount());
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
            ChatUtils.print("[BaseHunter] " + Lang.t("Investigation complete. Continuing search.", "Investigation terminée. Reprise de la recherche."));
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
            ChatUtils.print("[BaseHunter] " + Lang.t("Paused.", "En pause."));
        }
    }

    public void resume() {
        if (state == FinderState.PAUSED) {
            state = FinderState.SCANNING;
            ChatUtils.print("[BaseHunter] " + Lang.t("Resumed.", "Repris."));
        }
    }

    public void skipWaypoint() {
        if (navigation.advanceToNext()) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Skipped to waypoint ", "Sauté au waypoint ") + (navigation.getCurrentWaypointIndex() + 1));
            state = FinderState.SCANNING;
        }
    }

    /**
     * Export waypoints to minimap mod formats.
     */
    public void exportWaypoints(String format) {
        List<BaseRecord> bases = logger.getRecords();
        if ("xaero".equalsIgnoreCase(format)) {
            WaypointExporter.exportXaero(bases, "2b2t.org");
        } else if ("voxelmap".equalsIgnoreCase(format)) {
            WaypointExporter.exportVoxelMap(bases, "2b2t.org");
        } else {
            WaypointExporter.exportAll(bases, "2b2t.org");
        }
    }
}
