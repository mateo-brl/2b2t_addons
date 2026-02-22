package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.navigation.NavigationHelper;
import com.basefinder.persistence.StateManager;
import com.basefinder.scanner.ChunkAgeAnalyzer;
import com.basefinder.scanner.ChunkScanner;
import com.basefinder.scanner.FreshnessEstimator;
import com.basefinder.survival.SurvivalManager;
import com.basefinder.terrain.HeightmapCache;
import com.basefinder.terrain.SeedTerrainGenerator;
import com.basefinder.terrain.TerrainPredictor;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.BaritoneController;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.LagDetector;
import com.basefinder.util.Lang;
import com.basefinder.util.WaypointExporter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
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
import java.util.Set;

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
    private final BaritoneController baritoneController = new BaritoneController();
    private final HeightmapCache heightmapCache = new HeightmapCache();
    private final ChunkAgeAnalyzer chunkAgeAnalyzer = new ChunkAgeAnalyzer();
    private TerrainPredictor terrainPredictor = null;

    // State
    private FinderState state = FinderState.IDLE;
    private int tickCounter = 0;
    private int scanInterval = 20; // scan every second
    private int investigationStartTick = 0;

    // Base approach state
    private BaseRecord pendingBaseApproach = null;
    private int approachTicks = 0;
    private boolean approachReachedBase = false;
    private boolean approachScreenshotDone = false;
    private int approachLandedTick = 0;
    private FinderState stateBeforeApproach = FinderState.SCANNING;
    private static final int APPROACH_DISTANCE = 150;
    private static final int APPROACH_WAIT_TICKS = 60;
    private static final int APPROACH_TIMEOUT = 400;

    // === Settings ===

    // --- MODE DE RECHERCHE ---
    private final NullSetting modeGroup = new NullSetting("Mode de recherche");
    private final EnumSetting<NavigationHelper.SearchPattern> searchMode = new EnumSetting<>("Mode", NavigationHelper.SearchPattern.SPIRAL);

    // Paramètres SPIRAL
    private final NumberSetting<Double> spiralStep = new NumberSetting<>("Espacement spiral", 500.0, 100.0, 5000.0);

    // Paramètres GRID (quadrillage) - simple : taille d'un carré + rayon total
    private final NumberSetting<Integer> gridSize = new NumberSetting<>("Taille carrés", 5000, 500, 50000).incremental(500);
    private final NumberSetting<Integer> gridRange = new NumberSetting<>("Rayon total", 100000, 5000, 500000).incremental(5000);

    // Paramètres ZONE - intuitif : "de X à X, de Z à Z"
    // Range réduit à ±2M pour un slider utilisable, commande *basefinder zone pour valeurs exactes
    private final NumberSetting<Integer> zoneMinX = new NumberSetting<>("X début", 0, -2000000, 2000000).incremental(10000);
    private final NumberSetting<Integer> zoneMaxX = new NumberSetting<>("X fin", 500000, -2000000, 2000000).incremental(10000);
    private final NumberSetting<Integer> zoneMinZ = new NumberSetting<>("Z début", 0, -2000000, 2000000).incremental(10000);
    private final NumberSetting<Integer> zoneMaxZ = new NumberSetting<>("Z fin", 500000, -2000000, 2000000).incremental(10000);
    private final NumberSetting<Integer> zoneSpacing = new NumberSetting<>("Espacement zone", 1000, 200, 10000).incremental(100);

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

    // --- ATTERRISSAGE BARITONE ---
    private final NullSetting landingGroup = new NullSetting("Atterrissage");
    private final BooleanSetting useBaritoneAutoLand = new BooleanSetting("Atterrissage Baritone", "Déléguer l'atterrissage à Baritone pour plus de fiabilité", true);
    private final NumberSetting<Integer> acceptedFallDamage = new NumberSetting<>("Dégâts chute acceptés", 3, 0, 10);

    // --- MODE ATTENTE / CIRCLING ---
    private final NullSetting circlingGroup = new NullSetting("Mode attente");
    private final BooleanSetting enableCircling = new BooleanSetting("Mode attente", "Tourner en rond quand les chunks ne chargent pas", true);
    private final NumberSetting<Double> circleRadius = new NumberSetting<>("Rayon attente", 300.0, 100.0, 1000.0);
    private final NumberSetting<Integer> circleTimeout = new NumberSetting<>("Timeout attente (s)", 30, 5, 120);

    // --- PRÉDICTION TERRAIN ---
    private final NullSetting terrainGroup = new NullSetting("Prédiction terrain");
    private final BooleanSetting useTerrainPrediction = new BooleanSetting("Prédiction terrain", "Utiliser la seed 2b2t pour anticiper le relief", true);
    private final NumberSetting<Integer> terrainSafetyMargin = new NumberSetting<>("Marge altitude (blocs)", 40, 10, 100);

    // --- DÉTECTION ---
    private final NullSetting detectGroup = new NullSetting("Détection");
    private final BooleanSetting detectConstruction = new BooleanSetting("Bases", "Structures construites par des joueurs", true);
    private final BooleanSetting detectStorage = new BooleanSetting("Stashes", "Shulkers, ender chests, stockage", true);
    private final BooleanSetting detectMapArt = new BooleanSetting("Map Art", "Détecter les map arts au sol", true);
    private final BooleanSetting detectTrails = new BooleanSetting("Pistes", "Autoroutes de glace, chemins d'obsidienne", true);
    private final BooleanSetting detectStash = new BooleanSetting("Stashs", "Shulkers isolés sans construction", true);
    private final BooleanSetting detectFarm = new BooleanSetting("Fermes", "Fermes d'animaux, halls de villageois", true);
    private final BooleanSetting detectPortal = new BooleanSetting("Portails", "Portails du Nether (obsidienne >= 10)", true);
    private final NumberSetting<Double> minScore = new NumberSetting<>("Sensibilité", 30.0, 5.0, 200.0);
    private final BooleanSetting useEntityScanning = new BooleanSetting("Scan entités", "Scanner véhicules, animaux dressés, armures", true);
    private final BooleanSetting useClusterScoring = new BooleanSetting("Score cluster", "Regrouper les blocs proches pour un meilleur score", true);
    private final BooleanSetting followTrails = new BooleanSetting("Suivre les pistes", "Suivre automatiquement les pistes détectées", true);
    private final BooleanSetting autoScreenshot = new BooleanSetting("Auto capture", "Capturer l'écran à chaque découverte", false);
    private final BooleanSetting visitBases = new BooleanSetting("Visiter les bases", "Voler vers les bases détectées pour les photographier", true);

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
        APPROACHING_BASE,
        PAUSED
    }

    public BaseFinderModule() {
        super("BaseHunter", "Chasse de bases automatique sur 2b2t", ModuleCategory.EXTERNAL);

        // Mode de recherche avec paramètres spécifiques par mode
        modeGroup.addSubSettings(searchMode, spiralStep,
                gridSize, gridRange,
                zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ, zoneSpacing,
                searchMinDist, searchMaxDist,
                spiralRadius,
                highwayDist, highwayInterval);

        // Vol elytra
        flightGroup.addSubSettings(useElytra, cruiseAltitude, minAltitude, fireworkInterval,
                minElytraDurability, enableObstacleAvoidance, antiKickNoise);

        // Atterrissage Baritone
        landingGroup.addSubSettings(useBaritoneAutoLand, acceptedFallDamage);

        // Mode attente / Circling
        circlingGroup.addSubSettings(enableCircling, circleRadius, circleTimeout);

        // Prédiction terrain
        terrainGroup.addSubSettings(useTerrainPrediction, terrainSafetyMargin);

        // Détection
        detectGroup.addSubSettings(detectConstruction, detectStorage, detectMapArt, detectTrails,
                detectStash, detectFarm, detectPortal,
                minScore, useEntityScanning, useClusterScoring, followTrails, autoScreenshot, visitBases);

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
                landingGroup,
                circlingGroup,
                terrainGroup,
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
        navigation.setZoneSpacing(zoneSpacing.getValue());

        // Load saved state to restore waypoint position
        BlockPos searchCenter = mc.player.blockPosition();
        StateManager.SessionData savedState = null;
        if (enableAutoSave.getValue()) {
            savedState = stateManager.loadState();
            if (savedState != null && savedState.searchMode.equals(pattern.name()) && savedState.waypointIndex > 0) {
                // Always use saved center to regenerate identical waypoints
                searchCenter = new BlockPos(savedState.centerX, 200, savedState.centerZ);
            }
        }

        navigation.initializeSearch(pattern, searchCenter);

        // Restore waypoint index from saved session, or skip to nearest waypoint
        boolean sessionRestored = false;
        if (savedState != null && savedState.searchMode.equals(pattern.name()) && savedState.waypointIndex > 0) {
            navigation.skipTo(savedState.waypointIndex);
            if (savedState.distanceTraveled > 0) {
                navigation.setTotalDistanceTraveled(savedState.distanceTraveled);
            }
            if (savedState.uptimeSeconds > 0) {
                survivalManager.setPreviousUptime(savedState.uptimeSeconds);
            }
            sessionRestored = true;
            ChatUtils.print("[BaseHunter] " + Lang.t(
                    "Session restored! Resuming from waypoint " + (savedState.waypointIndex + 1) + "/" + navigation.getWaypointCount(),
                    "Session restaurée ! Reprise au waypoint " + (savedState.waypointIndex + 1) + "/" + navigation.getWaypointCount()));
        }

        // Restore distance/uptime even when saved WP was 0
        if (!sessionRestored && savedState != null) {
            if (savedState.distanceTraveled > 0) {
                navigation.setTotalDistanceTraveled(savedState.distanceTraveled);
            }
            if (savedState.uptimeSeconds > 0) {
                survivalManager.setPreviousUptime(savedState.uptimeSeconds);
            }
        }

        // If no saved WP, skip to the nearest waypoint to avoid flying 100+km to WP 1
        if (!sessionRestored && navigation.getWaypointCount() > 1) {
            int nearest = navigation.skipToNearest();
            if (nearest > 0) {
                ChatUtils.print("[BaseHunter] " + Lang.t(
                        "Skipped to nearest waypoint " + (nearest + 1) + "/" + navigation.getWaypointCount(),
                        "Sauté au waypoint le plus proche " + (nearest + 1) + "/" + navigation.getWaypointCount()));
            }
        }

        state = FinderState.SCANNING;
        tickCounter = 0;
        scanner.reset();

        // Restore scanned chunks and bases from saved state
        if (savedState != null && enableAutoSave.getValue()) {
            Set<ChunkPos> savedChunks = stateManager.loadScannedChunks();
            if (!savedChunks.isEmpty()) {
                scanner.restoreScannedChunks(savedChunks);
                ChatUtils.print("[BaseHunter] " + Lang.t(
                        "Restored " + savedChunks.size() + " scanned chunks from previous session",
                        "Restauré " + savedChunks.size() + " chunks scannés de la session précédente"));
            }
            // Restore found bases into the logger
            if (!savedState.bases.isEmpty()) {
                for (BaseRecord base : savedState.bases) {
                    logger.restoreRecord(base);
                }
                ChatUtils.print("[BaseHunter] " + Lang.t(
                        "Restored " + savedState.bases.size() + " bases from previous session",
                        "Restauré " + savedState.bases.size() + " bases de la session précédente"));
            }
        }

        // Show mode info with clear description
        String modeName;
        String modeDesc;
        switch (pattern) {
            case SPIRAL -> {
                modeName = Lang.t("Spiral", "Spirale");
                modeDesc = Lang.t("Spiral outward from your position, " + String.format("%.0f", spiralStep.getValue()) + " blocks apart",
                        "Spirale depuis votre position, " + String.format("%.0f", spiralStep.getValue()) + " blocs d'espacement");
            }
            case GRID -> {
                modeName = Lang.t("Grid", "Quadrillage");
                int totalArea = gridRange.getValue() * 2;
                int numSquares = totalArea / gridSize.getValue();
                modeDesc = Lang.t("Squares of " + gridSize.getValue() + "x" + gridSize.getValue() + " blocks, " + numSquares + "x" + numSquares + " squares (" + totalArea + "x" + totalArea + " total)",
                        "Carrés de " + gridSize.getValue() + "x" + gridSize.getValue() + " blocs, " + numSquares + "x" + numSquares + " carrés (" + totalArea + "x" + totalArea + " total)");
            }
            case ZONE -> {
                modeName = "Zone";
                int spanX = Math.abs(zoneMaxX.getValue() - zoneMinX.getValue());
                int spanZ = Math.abs(zoneMaxZ.getValue() - zoneMinZ.getValue());
                int totalChunks = navigation.getExpectedZoneChunkCount();
                modeDesc = Lang.t("X from " + zoneMinX.getValue() + " to " + zoneMaxX.getValue() + ", Z from " + zoneMinZ.getValue() + " to " + zoneMaxZ.getValue() + " (" + spanX + "x" + spanZ + " area, " + totalChunks + " chunks to scan)",
                        "X de " + zoneMinX.getValue() + " à " + zoneMaxX.getValue() + ", Z de " + zoneMinZ.getValue() + " à " + zoneMaxZ.getValue() + " (" + spanX + "x" + spanZ + " blocs, " + totalChunks + " chunks à scanner)");
            }
            case HIGHWAYS -> {
                modeName = Lang.t("Highways", "Autoroutes");
                modeDesc = Lang.t("Following all 8 highways up to " + highwayDist.getValue() + " blocks",
                        "Suivi des 8 autoroutes jusqu'à " + highwayDist.getValue() + " blocs");
            }
            case RANDOM -> {
                modeName = Lang.t("Random", "Aléatoire");
                modeDesc = Lang.t("Random points between " + searchMinDist.getValue() + " and " + searchMaxDist.getValue() + " blocks",
                        "Points aléatoires entre " + searchMinDist.getValue() + " et " + searchMaxDist.getValue() + " blocs");
            }
            case RING -> {
                modeName = Lang.t("Ring", "Anneau");
                modeDesc = Lang.t("Ring at " + String.format("%.0f", spiralRadius.getValue()) + " blocks radius",
                        "Anneau à " + String.format("%.0f", spiralRadius.getValue()) + " blocs de rayon");
            }
            default -> {
                modeName = Lang.t("Custom", "Personnalisé");
                modeDesc = Lang.t("Custom waypoints", "Waypoints personnalisés");
            }
        }
        ChatUtils.print("[BaseHunter] " + Lang.t("Started! Mode: ", "Démarré ! Mode : ") + modeName);
        ChatUtils.print("[BaseHunter] " + modeDesc);

        // Initialize survival systems
        survivalManager.onEnable();

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
        survivalManager.stop();

        // Release movement keys safely
        try {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
        } catch (Exception e) {
            // Ignore - game may be closing
        }

        // Save state on disable
        if (enableAutoSave.getValue()) {
            BlockPos center = navigation.getSearchCenter();
            stateManager.saveState(
                    logger.getRecords(),
                    navigation.getCurrentWaypointIndex(),
                    navigation.getTotalDistanceTraveled(),
                    scanner.getScannedCount(),
                    searchMode.getValue().name(),
                    center != null ? center.getX() : 0,
                    center != null ? center.getZ() : 0,
                    survivalManager.getUptimeSeconds()
            );
            stateManager.saveScannedChunks(scanner.getScannedChunksSet());
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
        scanner.setDetectStash(detectStash.getValue());
        scanner.setDetectFarm(detectFarm.getValue());
        scanner.setDetectPortal(detectPortal.getValue());

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

        // Baritone landing
        elytraBot.setUseBaritoneLanding(useBaritoneAutoLand.getValue());
        elytraBot.setAcceptedFallDamage(acceptedFallDamage.getValue());
        elytraBot.setBaritoneController(baritoneController);

        // Circling
        elytraBot.setEnableCircling(enableCircling.getValue());
        elytraBot.setCircleRadius(circleRadius.getValue());
        elytraBot.setCircleTimeout(circleTimeout.getValue() * 20); // Convert seconds to ticks

        // Terrain prediction
        if (useTerrainPrediction.getValue()) {
            SeedTerrainGenerator seedGen = new SeedTerrainGenerator();
            terrainPredictor = new TerrainPredictor(heightmapCache, seedGen, chunkAgeAnalyzer);
            elytraBot.setTerrainPredictor(terrainPredictor);
            elytraBot.setTerrainSafetyMargin(terrainSafetyMargin.getValue());
        } else {
            terrainPredictor = null;
            elytraBot.setTerrainPredictor(null);
        }

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
            BlockPos center = navigation.getSearchCenter();
            stateManager.saveState(
                    logger.getRecords(),
                    navigation.getCurrentWaypointIndex(),
                    navigation.getTotalDistanceTraveled(),
                    scanner.getScannedCount(),
                    searchMode.getValue().name(),
                    center != null ? center.getX() : 0,
                    center != null ? center.getZ() : 0,
                    survivalManager.getUptimeSeconds()
            );
            stateManager.saveScannedChunks(scanner.getScannedChunksSet());
        }

        // Record chunk heights for terrain prediction
        if (terrainPredictor != null && tickCounter % 20 == 0) {
            recordNearbyChunkHeights();
        }

        // Memory cleanup
        scanner.cleanupMemory();

        switch (state) {
            case SCANNING -> handleScanning();
            case TRAIL_FOLLOWING -> handleTrailFollowing();
            case FLYING_TO_WAYPOINT -> handleFlying();
            case INVESTIGATING -> handleInvestigating();
            case APPROACHING_BASE -> handleApproachingBase();
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
            String statusMsg = "[BaseHunter] " + Lang.t("Scanned: ", "Scannés : ") + scanner.getScannedCount() + Lang.t(" chunks | Found: ", " chunks | Trouvés : ") + logger.getCount() + " bases";
            if (navigation.isZoneMode() && navigation.getExpectedZoneChunkCount() > 0) {
                double coverage = navigation.getZoneCoveragePercent(scanner.getScannedChunksSet());
                statusMsg += Lang.t(" | Zone: ", " | Zone : ") + String.format("%.1f", coverage) + "%";
            }
            ChatUtils.print(statusMsg);
        }

        BaseRecord bestApproach = null;
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

                if (visitBases.getValue() && (bestApproach == null || record.getScore() > bestApproach.getScore())) {
                    bestApproach = record;
                }
            }
        }

        // Visit best base found
        if (bestApproach != null) {
            startBaseApproach(bestApproach);
            return;
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

        // Signs with text
        if (analysis.getSignCount() > 0) {
            if (!notes.isEmpty()) notes.append(", ");
            notes.append("signs:").append(analysis.getSignCount());
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
            BaseRecord bestApproach = null;
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

                    if (visitBases.getValue() && (bestApproach == null || record.getScore() > bestApproach.getScore())) {
                        bestApproach = record;
                    } else if (!visitBases.getValue() && analysis.getScore() >= minScore.getValue() * 2) {
                        // Si on trouve quelque chose d'important, investiguer (si visite désactivée)
                        state = FinderState.INVESTIGATING;
                        investigationStartTick = tickCounter;
                        ChatUtils.print("[BaseHunter] " + Lang.t("Significant find! Investigating...", "Découverte importante ! Investigation..."));
                        return;
                    }
                }
            }

            // Visit best base found
            if (bestApproach != null) {
                startBaseApproach(bestApproach);
                return;
            }
        }
    }

    private void handleFlying() {
        elytraBot.tick();

        // Keep scanning while flying
        if (tickCounter % scanInterval == 0) {
            List<ChunkAnalysis> newFinds = scanner.scanLoadedChunks();
            BaseRecord bestApproach = null;
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

                    if (visitBases.getValue() && (bestApproach == null || record.getScore() > bestApproach.getScore())) {
                        bestApproach = record;
                    }
                }
            }

            // Visit best base found
            if (bestApproach != null) {
                startBaseApproach(bestApproach);
                return;
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
                // All waypoints visited - check zone coverage if in zone mode
                if (navigation.isZoneMode() && navigation.getExpectedZoneChunkCount() > 0) {
                    double coverage = navigation.getZoneCoveragePercent(scanner.getScannedChunksSet());
                    ChatUtils.print("[BaseHunter] " + Lang.t(
                            "Zone pass complete! Coverage: " + String.format("%.1f", coverage) + "% (" + scanner.getScannedCount() + "/" + navigation.getExpectedZoneChunkCount() + " chunks)",
                            "Passe de zone terminée ! Couverture : " + String.format("%.1f", coverage) + "% (" + scanner.getScannedCount() + "/" + navigation.getExpectedZoneChunkCount() + " chunks)"));

                    // If coverage is not 100%, generate additional waypoints for missed chunks
                    if (coverage < 99.0 && navigation.getZoneMissedPassCount() < 3) {
                        boolean hasMore = navigation.generateMissedChunkWaypoints(scanner.getScannedChunksSet());
                        if (hasMore) {
                            int newWp = navigation.getWaypointCount() - navigation.getCurrentWaypointIndex();
                            ChatUtils.print("[BaseHunter] " + Lang.t(
                                    "Generating " + newWp + " additional waypoints for missed chunks (pass " + navigation.getZoneMissedPassCount() + "/3)...",
                                    "Génération de " + newWp + " waypoints supplémentaires pour les chunks manqués (passe " + navigation.getZoneMissedPassCount() + "/3)..."));
                            state = FinderState.SCANNING;
                            elytraBot.stop();
                            return;
                        }
                    }
                }
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

    // ===== BASE APPROACH =====

    private void startBaseApproach(BaseRecord record) {
        pendingBaseApproach = record;
        approachTicks = 0;
        approachReachedBase = false;
        approachScreenshotDone = false;
        approachLandedTick = 0;
        stateBeforeApproach = state;
        state = FinderState.APPROACHING_BASE;

        if (useElytra.getValue()) {
            elytraBot.startFlight(record.getPosition());
        }

        ChatUtils.print("[BaseHunter] " + Lang.t(
                "Flying to base... (" + record.toShortString() + ")",
                "Vol vers la base... (" + record.toShortString() + ")"));
    }

    private void handleApproachingBase() {
        if (mc.player == null || pendingBaseApproach == null) {
            finishApproach();
            return;
        }

        approachTicks++;
        BlockPos basePos = pendingBaseApproach.getPosition();
        double dx = mc.player.getX() - basePos.getX();
        double dz = mc.player.getZ() - basePos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Let ElytraBot handle ALL flight control safely - NEVER override rotations while in flight
        if (useElytra.getValue() && elytraBot.isFlying()) {
            elytraBot.tick();
        }

        // === PHASE 1: Flying toward base ===
        if (!approachReachedBase) {
            if (dist <= APPROACH_DISTANCE) {
                // Close enough - mark arrival, ElytraBot will land safely
                approachReachedBase = true;
                ChatUtils.print("[BaseHunter] " + Lang.t(
                        "Near base! Safe landing in progress...",
                        "Proche de la base ! Atterrissage sécurisé en cours..."));
                // ElytraBot continues with destination=basePos
                // Its normal safe chain handles landing: CRUISING→DESCENDING→FLARING→LANDING→BARITONE_LANDING
            } else if (!useElytra.getValue()) {
                // Ground movement toward base
                float yaw = (float) Math.toDegrees(Math.atan2(-(basePos.getX() - mc.player.getX()), basePos.getZ() - mc.player.getZ()));
                mc.player.setYRot(yaw);
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(true);
            }
            if (approachTicks > APPROACH_TIMEOUT) {
                ChatUtils.print("[BaseHunter] " + Lang.t("Approach timeout, continuing search.", "Approche timeout, reprise de la recherche."));
                finishApproach();
            }
            return;
        }

        // === PHASE 2: Aerial screenshot done - waiting for ElytraBot to land safely ===
        if (elytraBot.isFlying()) {
            // ElytraBot handles the entire descent safely - just wait
            if (approachTicks > APPROACH_TIMEOUT * 2) {
                ChatUtils.print("[BaseHunter] " + Lang.t("Landing timeout, resuming safely.", "Timeout atterrissage, reprise sécurisée."));
                finishApproach();
            }
            return;
        }

        // === PHASE 3: Landed - use Baritone to walk to base if needed, then ground screenshot ===
        if (approachLandedTick == 0) {
            approachLandedTick = approachTicks;
            // If we're far from the base and Baritone is available, walk there
            if (dist > 10 && baritoneController.isAvailable()) {
                baritoneController.configureForFastLanding();
                baritoneController.landAt(basePos);
                ChatUtils.print("[BaseHunter] " + Lang.t(
                        "Walking to base via Baritone...",
                        "Marche vers la base via Baritone..."));
            }
        }

        // Check if Baritone arrived or we're close enough
        boolean arrivedAtBase = dist < 10 || (baritoneController.isAvailable() && baritoneController.isLandingComplete());
        boolean walkTimeout = (approachTicks - approachLandedTick) > APPROACH_TIMEOUT;

        if (arrivedAtBase || walkTimeout) {
            baritoneController.cancelLanding();

            if (!approachScreenshotDone) {
                approachScreenshotDone = true;
                // Look at the base for ground-level screenshot
                float yaw = (float) Math.toDegrees(Math.atan2(-(basePos.getX() - mc.player.getX()), basePos.getZ() - mc.player.getZ()));
                mc.player.setYRot(yaw);
                mc.player.setXRot(10); // Slight downward look
            }

            // Wait for chunks to render before taking screenshot
            int ticksSinceLook = approachTicks - approachLandedTick;
            if (arrivedAtBase && ticksSinceLook >= APPROACH_WAIT_TICKS) {
                logger.takeScreenshot(pendingBaseApproach);
                ChatUtils.print("[BaseHunter] " + Lang.t("Base photographed from ground!", "Base photographiée depuis le sol !"));
                finishApproach();
            } else if (walkTimeout) {
                ChatUtils.print("[BaseHunter] " + Lang.t("Walk timeout, continuing search.", "Timeout marche, reprise de la recherche."));
                finishApproach();
            }
        }
    }

    private void finishApproach() {
        pendingBaseApproach = null;
        baritoneController.cancelLanding();
        state = (stateBeforeApproach == FinderState.APPROACHING_BASE) ? FinderState.SCANNING : stateBeforeApproach;

        // Resume flight to current waypoint
        if (useElytra.getValue() && navigation.getCurrentTarget() != null) {
            state = FinderState.FLYING_TO_WAYPOINT;
            elytraBot.startFlight(navigation.getCurrentTarget());
        }
    }

    /**
     * Record heights of nearby loaded chunks into the heightmap cache.
     */
    private void recordNearbyChunkHeights() {
        if (mc.player == null || mc.level == null || heightmapCache == null) return;

        var chunkSource = mc.level.getChunkSource();
        int playerChunkX = mc.player.chunkPosition().x;
        int playerChunkZ = mc.player.chunkPosition().z;
        int radius = Math.min(mc.options.renderDistance().get(), 8);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = chunkSource.getChunk(playerChunkX + dx, playerChunkZ + dz, false);
                if (chunk != null) {
                    heightmapCache.recordChunkHeight(chunk);
                }
            }
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
    public TerrainPredictor getTerrainPredictor() { return terrainPredictor; }
    public HeightmapCache getHeightmapCache() { return heightmapCache; }

    public int[] getZoneBounds() {
        return new int[]{ zoneMinX.getValue(), zoneMaxX.getValue(), zoneMinZ.getValue(), zoneMaxZ.getValue() };
    }

    public void setZoneBounds(int minX, int maxX, int minZ, int maxZ) {
        zoneMinX.setValue(minX);
        zoneMaxX.setValue(maxX);
        zoneMinZ.setValue(minZ);
        zoneMaxZ.setValue(maxZ);
    }

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
            try {
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
            } catch (Exception e) {
                // Ignore
            }
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
