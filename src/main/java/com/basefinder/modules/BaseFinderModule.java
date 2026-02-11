package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.navigation.NavigationHelper;
import com.basefinder.persistence.StateManager;
import com.basefinder.scanner.ChunkScanner;
import com.basefinder.scanner.FreshnessEstimator;
import com.basefinder.survival.SurvivalManager;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.LagDetector;
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
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
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
    private final SurvivalManager survivalManager = new SurvivalManager();
    private final StateManager stateManager = new StateManager();
    private final LagDetector lagDetector = new LagDetector();

    // State
    private FinderState state = FinderState.IDLE;
    private int tickCounter = 0;
    private int scanInterval = 20; // scan every second
    private int investigationStartTick = 0;

    // === Settings ===

    // --- MODE DE RECHERCHE ---
    private final NullSetting modeGroup = new NullSetting("Mode de recherche");
    private final EnumSetting<NavigationHelper.SearchPattern> searchMode = new EnumSetting<>("Mode", NavigationHelper.SearchPattern.SPIRAL);
    // Paramètres SPIRAL
    private final NumberSetting<Double> spiralStep = new NumberSetting<>("Espacement spiral", 500.0, 100.0, 5000.0);
    // Paramètres GRID (quadrillage)
    private final NumberSetting<Integer> gridSize = new NumberSetting<>("Taille carrés", 1000, 200, 10000);
    private final NumberSetting<Integer> gridRange = new NumberSetting<>("Zone totale", 50000, 5000, 500000);
    // Paramètres ZONE (coordonnées exactes) - incremental pour pouvoir taper la valeur
    private final NumberSetting<Integer> zoneMinX = new NumberSetting<>("Zone min X", -10000, -30000000, 30000000).incremental(1.0);
    private final NumberSetting<Integer> zoneMaxX = new NumberSetting<>("Zone max X", 10000, -30000000, 30000000).incremental(1.0);
    private final NumberSetting<Integer> zoneMinZ = new NumberSetting<>("Zone min Z", -10000, -30000000, 30000000).incremental(1.0);
    private final NumberSetting<Integer> zoneMaxZ = new NumberSetting<>("Zone max Z", 10000, -30000000, 30000000).incremental(1.0);
    // Paramètres RANDOM
    private final NumberSetting<Integer> searchMinDist = new NumberSetting<>("Distance min", 5000, 100, 50000);
    private final NumberSetting<Integer> searchMaxDist = new NumberSetting<>("Distance max", 100000, 10000, 500000);
    // Paramètres RING
    private final NumberSetting<Double> spiralRadius = new NumberSetting<>("Rayon anneau", 5000.0, 500.0, 200000.0);
    // Paramètres HIGHWAYS
    private final NumberSetting<Integer> highwayDist = new NumberSetting<>("Distance autoroute", 100000, 10000, 500000);
    private final NumberSetting<Integer> highwayInterval = new NumberSetting<>("Intervalle scan", 1000, 100, 10000);

    // --- VOL ELYTRA ---
    private final NullSetting flightGroup = new NullSetting("Vol Elytra");
    private final BooleanSetting useElytra = new BooleanSetting("Activer le vol", "Voler en elytra entre les zones de scan", true);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude de vol", 200.0, 50.0, 350.0);
    private final NumberSetting<Double> minAltitude = new NumberSetting<>("Altitude atterrissage", 100.0, 30.0, 200.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Délai entre fusées (ticks)", 40, 10, 100);
    private final NumberSetting<Integer> minElytraDurability = new NumberSetting<>("Durabilité min elytra", 10, 1, 100);
    private final BooleanSetting enableObstacleAvoidance = new BooleanSetting("Éviter les obstacles", "Monter automatiquement devant le terrain", true);
    private final BooleanSetting antiKickNoise = new BooleanSetting("Anti-kick AFK", "Mouvements subtils pour éviter d'être kick", true);

    // --- DÉTECTION ---
    private final NullSetting detectGroup = new NullSetting("Détection");
    private final BooleanSetting detectConstruction = new BooleanSetting("Bases", "Structures construites par des joueurs", true);
    private final BooleanSetting detectStorage = new BooleanSetting("Stashes", "Shulkers, ender chests, stockage", true);
    private final BooleanSetting detectMapArt = new BooleanSetting("Map Art", "Détecter les map arts au sol", true);
    private final BooleanSetting detectTrails = new BooleanSetting("Pistes", "Autoroutes de glace, chemins d'obsidienne", true);
    private final NumberSetting<Double> minScore = new NumberSetting<>("Sensibilité", 25.0, 5.0, 200.0);
    private final BooleanSetting useEntityScanning = new BooleanSetting("Scan entités", "Scanner véhicules, animaux dressés, armures", true);
    private final BooleanSetting useClusterScoring = new BooleanSetting("Score cluster", "Regrouper les blocs proches pour un meilleur score", true);
    private final BooleanSetting followTrails = new BooleanSetting("Suivre les pistes", "Suivre automatiquement les pistes détectées", true);
    private final BooleanSetting autoScreenshot = new BooleanSetting("Auto capture", "Capturer l'écran à chaque découverte", false);

    // --- SURVIE 24/7 ---
    private final NullSetting survivalGroup = new NullSetting("Survie 24/7");
    private final BooleanSetting enableAutoTotem = new BooleanSetting("Auto totem", "Garder un totem en offhand automatiquement", true);
    private final BooleanSetting enableAutoEat = new BooleanSetting("Auto manger", "Manger quand la faim est basse", true);
    private final NumberSetting<Integer> healthThreshold = new NumberSetting<>("Seuil santé", 10, 2, 20);
    private final BooleanSetting enablePlayerDetection = new BooleanSetting("Radar joueurs", "Se déconnecter si un joueur est détecté", true);
    private final NumberSetting<Double> playerDetectRange = new NumberSetting<>("Portée radar (blocs)", 200.0, 50.0, 500.0);
    private final BooleanSetting enableFireworkResupply = new BooleanSetting("Réappro fusées", "Prendre des fusées dans les shulkers", true);
    private final NumberSetting<Integer> resupplyThreshold = new NumberSetting<>("Réappro quand < fusées", 16, 4, 64);
    private final BooleanSetting enable2b2tLag = new BooleanSetting("Compensation lag 2b2t", "Adapter les timings quand le serveur lag", true);
    private final BooleanSetting enableAutoSave = new BooleanSetting("Sauvegarde auto", "Sauvegarder la session toutes les 5 min", true);

    // --- AVANCÉ ---
    private final NullSetting advancedGroup = new NullSetting("Avancé");
    private final BooleanSetting useChunkTrails = new BooleanSetting("Détection âge chunks", "Utiliser l'âge des chunks pour détecter les pistes", true);
    private final BooleanSetting useVersionBorders = new BooleanSetting("Bordures de version", "Détecter les frontières entre versions de Minecraft", true);
    private final NumberSetting<Integer> scanIntervalSetting = new NumberSetting<>("Vitesse scan (ticks)", 20, 5, 100);
    private final NumberSetting<Double> waypointThreshold = new NumberSetting<>("Rayon waypoint", 100.0, 20.0, 500.0);

    // --- LOG ---
    private final BooleanSetting logToChat = new BooleanSetting("Alertes chat", "Afficher les découvertes dans le chat", true);
    private final BooleanSetting logToFile = new BooleanSetting("Sauvegarder", "Sauvegarder les bases dans un fichier .csv", true);

    // --- LANGUE / LANGUAGE ---
    private final BooleanSetting langFr = new BooleanSetting("Français", "Interface en français (off = English)", true);

    public enum FinderState {
        IDLE,
        SCANNING,
        TRAIL_FOLLOWING,
        FLYING_TO_WAYPOINT,
        INVESTIGATING,
        PAUSED
    }

    public BaseFinderModule() {
        super("BaseHunter", "Chasse de bases automatique sur 2b2t", ModuleCategory.EXTERNAL);

        // Mode de recherche avec paramètres spécifiques par mode
        modeGroup.addSubSettings(searchMode, spiralStep,
                gridSize, gridRange,
                zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ,
                searchMinDist, searchMaxDist,
                spiralRadius,
                highwayDist, highwayInterval);

        // Vol elytra
        flightGroup.addSubSettings(useElytra, cruiseAltitude, minAltitude, fireworkInterval,
                minElytraDurability, enableObstacleAvoidance, antiKickNoise);

        // Détection
        detectGroup.addSubSettings(detectConstruction, detectStorage, detectMapArt, detectTrails,
                minScore, useEntityScanning, useClusterScoring, followTrails, autoScreenshot);

        // Survie
        survivalGroup.addSubSettings(enableAutoTotem, enableAutoEat, healthThreshold,
                enablePlayerDetection, playerDetectRange,
                enableFireworkResupply, resupplyThreshold,
                enable2b2tLag, enableAutoSave);

        // Avancé
        advancedGroup.addSubSettings(useChunkTrails, useVersionBorders, scanIntervalSetting, waypointThreshold);

        this.registerSettings(
                modeGroup,
                flightGroup,
                detectGroup,
                survivalGroup,
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
        NavigationHelper.SearchPattern pattern = searchMode.getValue();

        // Apply mode-specific parameters
        navigation.setSpiralStep(spiralStep.getValue());
        navigation.setSpiralRadius(spiralRadius.getValue());
        navigation.setSearchMinDistance(searchMinDist.getValue());
        navigation.setSearchMaxDistance(searchMaxDist.getValue());
        navigation.setHighwayDistance(highwayDist.getValue());
        navigation.setHighwayCheckInterval(highwayInterval.getValue());
        navigation.setGridSize(gridSize.getValue());
        navigation.setGridRange(gridRange.getValue());
        navigation.setZoneBounds(zoneMinX.getValue(), zoneMaxX.getValue(), zoneMinZ.getValue(), zoneMaxZ.getValue());

        navigation.initializeSearch(pattern, mc.player.blockPosition());

        state = FinderState.SCANNING;
        tickCounter = 0;
        scanner.reset();

        // Show mode info
        String modeDesc = switch (pattern) {
            case SPIRAL -> Lang.t("Spiral outward from your position", "Spirale depuis votre position");
            case GRID -> Lang.t("Grid: " + gridSize.getValue() + "x" + gridSize.getValue() + " squares over " + gridRange.getValue() + " blocks",
                    "Grille : carrés de " + gridSize.getValue() + "x" + gridSize.getValue() + " sur " + gridRange.getValue() + " blocs");
            case ZONE -> Lang.t("Zone: X[" + zoneMinX.getValue() + " to " + zoneMaxX.getValue() + "] Z[" + zoneMinZ.getValue() + " to " + zoneMaxZ.getValue() + "]",
                    "Zone : X[" + zoneMinX.getValue() + " à " + zoneMaxX.getValue() + "] Z[" + zoneMinZ.getValue() + " à " + zoneMaxZ.getValue() + "]");
            case HIGHWAYS -> Lang.t("Follow all 8 highways", "Suivre les 8 autoroutes");
            case RANDOM -> Lang.t("Random within " + searchMinDist.getValue() + "-" + searchMaxDist.getValue() + " blocks",
                    "Aléatoire entre " + searchMinDist.getValue() + " et " + searchMaxDist.getValue() + " blocs");
            case RING -> Lang.t("Ring at " + String.format("%.0f", spiralRadius.getValue()) + " blocks radius",
                    "Anneau à " + String.format("%.0f", spiralRadius.getValue()) + " blocs de rayon");
            case CUSTOM -> Lang.t("Custom waypoints", "Waypoints personnalisés");
        };
        ChatUtils.print("[BaseHunter] " + Lang.t("Started! Mode: ", "Démarré ! Mode : ") + pattern.name());
        ChatUtils.print("[BaseHunter] " + modeDesc);

        // Initialize survival systems
        survivalManager.onEnable();

        // Try to load previous session state
        if (enableAutoSave.getValue()) {
            StateManager.SessionData savedState = stateManager.loadState();
            if (savedState != null && !savedState.bases.isEmpty()) {
                ChatUtils.print("[BaseHunter] " + Lang.t("Previous session restored!", "Session précédente restaurée !"));
            }
        }

        // Show optimization status
        StringBuilder optimStatus = new StringBuilder();
        if (useEntityScanning.getValue()) optimStatus.append(Lang.t("Entities", "Entités")).append(" ");
        if (useClusterScoring.getValue()) optimStatus.append(Lang.t("Clusters", "Clusters")).append(" ");
        if (autoScreenshot.getValue()) optimStatus.append(Lang.t("Screenshot", "Capture")).append(" ");
        if (antiKickNoise.getValue()) optimStatus.append(Lang.t("AntiKick", "AntiKick")).append(" ");
        if (!optimStatus.isEmpty()) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Optimizations: ", "Optimisations : ") + optimStatus.toString().trim());
        }

        // Show survival status
        StringBuilder survivalStatus = new StringBuilder();
        if (enableAutoTotem.getValue()) survivalStatus.append(Lang.t("Totem", "Totem")).append(" ");
        if (enableAutoEat.getValue()) survivalStatus.append(Lang.t("Eat", "Manger")).append(" ");
        if (enablePlayerDetection.getValue()) survivalStatus.append(Lang.t("Radar", "Radar")).append(" ");
        if (enableFireworkResupply.getValue()) survivalStatus.append(Lang.t("Resupply", "Réappro")).append(" ");
        if (enableObstacleAvoidance.getValue()) survivalStatus.append(Lang.t("Avoid", "Éviter")).append(" ");
        if (enableAutoSave.getValue()) survivalStatus.append(Lang.t("Save", "Sauvegarde")).append(" ");
        if (!survivalStatus.isEmpty()) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Survival 24/7: ", "Survie 24/7 : ") + survivalStatus.toString().trim());
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

        // Save state on disable
        if (enableAutoSave.getValue()) {
            stateManager.saveState(
                    logger.getRecords(),
                    navigation.getCurrentWaypointIndex(),
                    navigation.getTotalDistanceTraveled(),
                    scanner.getScannedCount(),
                    searchMode.getValue().name()
            );
        }

        if (mc.level != null) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Stopped. Found ", "Arrêté. ") + logger.getCount() + Lang.t(" bases. Scanned ", " bases trouvées. ") + scanner.getScannedCount() + Lang.t(" chunks.", " chunks scannés."));
            long uptime = survivalManager.getUptimeSeconds();
            if (uptime > 60) {
                ChatUtils.print("[BaseHunter] " + Lang.t("Uptime: ", "Temps en ligne : ") + formatUptime(uptime));
            }
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

        // Survival settings
        survivalManager.setEnableAutoTotem(enableAutoTotem.getValue());
        survivalManager.setEnableAutoEat(enableAutoEat.getValue());
        survivalManager.setEnablePlayerDetection(enablePlayerDetection.getValue());
        survivalManager.setPlayerDetectRange(playerDetectRange.getValue());
        survivalManager.setEnableFireworkResupply(enableFireworkResupply.getValue());
        survivalManager.setResupplyThreshold(resupplyThreshold.getValue());
        survivalManager.setHealthThreshold(healthThreshold.getValue());

        // Obstacle avoidance
        elytraBot.setUseObstacleAvoidance(enableObstacleAvoidance.getValue());

        // 2b2t lag compensation
        if (enable2b2tLag.getValue()) {
            scanner.setLagDetector(lagDetector);
            elytraBot.setLagDetector(lagDetector);
            survivalManager.getFireworkResupply().setLagDetector(lagDetector);
        } else {
            scanner.setLagDetector(null);
            elytraBot.setLagDetector(null);
            survivalManager.getFireworkResupply().setLagDetector(null);
        }

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

        // Lag detection tick (TPS estimation)
        if (enable2b2tLag.getValue()) {
            lagDetector.tick();
        }

        // Survival systems tick (highest priority)
        boolean disconnected = survivalManager.tick();
        if (disconnected) {
            // Player detected - we disconnected, stop everything
            state = FinderState.PAUSED;
            elytraBot.stop();
            return;
        }

        // Handle firework resupply coordination with ElytraBot
        if (survivalManager.needsFireworkResupply() && elytraBot.isFlying()) {
            // Signal elytrabot to land for resupply
            ChatUtils.print("[BaseHunter] " + Lang.t("Landing for firework resupply...", "Atterrissage pour réapprovisionnement en fusées..."));
            elytraBot.stop();
            survivalManager.getFireworkResupply().startResupply();
        }

        // If resupplying, don't fly
        if (survivalManager.isResupplying()) {
            return;
        }

        // Auto-save state periodically
        if (enableAutoSave.getValue() && stateManager.shouldAutoSave()) {
            stateManager.saveState(
                    logger.getRecords(),
                    navigation.getCurrentWaypointIndex(),
                    navigation.getTotalDistanceTraveled(),
                    scanner.getScannedCount(),
                    searchMode.getValue().name()
            );
        }

        // Memory cleanup
        scanner.cleanupMemory();

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
    public SurvivalManager getSurvivalManager() { return survivalManager; }
    public StateManager getStateManager() { return stateManager; }
    public LagDetector getLagDetector() { return lagDetector; }

    private String formatUptime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

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
