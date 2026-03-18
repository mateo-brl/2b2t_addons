package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.logger.BaseLogger;
import com.basefinder.scanner.ChunkScanner;
import com.basefinder.survival.SurvivalManager;
import com.basefinder.util.BaritoneController;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.ChunkAnalysis;
import com.basefinder.util.Lang;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PortalHunter — Automated nether portal base scanning.
 *
 * State machine:
 * IDLE → SCANNING_NETHER → TRAVELING_TO_PORTAL → ENTERING_PORTAL
 *      → OVERWORLD_SWEEP → RETURNING_TO_PORTAL → EXITING_PORTAL → (next portal)
 *
 * Scans loaded nether chunks for portal blocks, flies to each one,
 * enters the overworld, sweeps a circle around the exit point scanning
 * for bases, then returns through the portal and repeats.
 */
public class PortalHunterModule extends ToggleableModule {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PortalHunter");

    // === COMPONENTS ===
    private final ElytraBot elytraBot = new ElytraBot();
    private final BaritoneController baritoneController = new BaritoneController();
    private final ChunkScanner chunkScanner = new ChunkScanner();
    private final BaseLogger baseLogger = new BaseLogger();
    private final SurvivalManager survivalManager = new SurvivalManager();

    // === SETTINGS ===
    private final NumberSetting<Integer> scanRadius = new NumberSetting<>("Rayon scan Nether", 500, 100, 5000).incremental(100);
    private final NumberSetting<Integer> sweepRadius = new NumberSetting<>("Rayon sweep Overworld", 500, 100, 2000).incremental(100);
    private final NumberSetting<Integer> sweepDuration = new NumberSetting<>("Durée sweep (s)", 300, 60, 1800).incremental(30);
    private final NumberSetting<Double> minScore = new NumberSetting<>("Score min détection", 30.0, 5.0, 200.0);
    private final BooleanSetting revisitPortals = new BooleanSetting("Revisiter portails", "Revisiter les portails déjà explorés", false);
    private final BooleanSetting useElytra = new BooleanSetting("Utiliser Elytra", "Vol elytra dans le Nether", true);
    private final BooleanSetting logToChat = new BooleanSetting("Alertes chat", "Afficher les événements dans le chat", true);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude croisière", 200.0, 50.0, 350.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Intervalle fusées (ticks)", 40, 10, 100);
    private final BooleanSetting langFr = new BooleanSetting("Français", "Interface en français (off = English)", true);

    // === STATE MACHINE ===
    private enum HunterState {
        IDLE,
        SCANNING_NETHER,
        TRAVELING_TO_PORTAL,
        ENTERING_PORTAL,
        OVERWORLD_SWEEP,
        RETURNING_TO_PORTAL,
        EXITING_PORTAL
    }

    private HunterState state = HunterState.IDLE;
    private int tickCounter = 0;

    // Portal queue
    private final LinkedList<BlockPos> portalQueue = new LinkedList<>();
    private BlockPos currentPortalNether = null;     // Nether-side coords of current portal
    private BlockPos currentPortalOverworld = null;   // Expected overworld-side coords
    private BlockPos overworldArrivalPos = null;       // Actual overworld position after teleport

    // Dimension tracking
    private String lastDimension = "";

    // Portal entry
    private int portalWaitTimer = 0;
    private static final int PORTAL_TIMEOUT_TICKS = 300;

    // Sweep state
    private final List<BlockPos> sweepWaypoints = new ArrayList<>();
    private int currentSweepWaypoint = 0;
    private int sweepStartTick = 0;
    private boolean sweepBaritoneActive = false;
    private int sweepStuckTimer = 0;
    private Vec3 lastSweepPos = null;
    private static final int SWEEP_NUM_WAYPOINTS = 8;
    private static final int SWEEP_STUCK_THRESHOLD = 200;

    // Elytra landing for portal approach
    private boolean landingPhase = false;
    private int landingTimer = 0;
    private static final int LANDING_TIMEOUT = 400;

    // Walking towards portal
    private int walkStuckTimer = 0;
    private Vec3 lastWalkPos = null;

    // Persistence
    private final List<VisitedPortal> visitedPortals = new ArrayList<>();
    private Path visitedPortalsFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Stats
    private int portalsVisited = 0;
    private int basesFound = 0;

    // Message throttle
    private int messageThrottle = 0;

    public PortalHunterModule() {
        super("PortalHunter", "Scan automatique de bases via portails du Nether", ModuleCategory.EXTERNAL);

        this.registerSettings(
                scanRadius, sweepRadius, sweepDuration, minScore,
                revisitPortals, useElytra, logToChat,
                cruiseAltitude, fireworkInterval, langFr
        );
    }

    // =================================================================
    // LIFECYCLE
    // =================================================================

    @Override
    public void onEnable() {
        Lang.setFrench(langFr.getValue());

        if (mc.player == null || mc.level == null) {
            printChat(Lang.t("Must be in a world!", "Vous devez être dans un monde !"));
            this.toggle();
            return;
        }

        if (!isNether(getCurrentDimension())) {
            printChat(Lang.t("ERROR: You must be in the Nether to start!", "ERREUR : Vous devez être dans le Nether pour commencer !"));
            this.toggle();
            return;
        }

        if (isElytraBotInUse()) {
            printChat(Lang.t(
                    "ERROR: Another module using ElytraBot is already active!",
                    "ERREUR : Un autre module utilisant ElytraBot est déjà actif !"));
            this.toggle();
            return;
        }

        // Initialize persistence
        try {
            Path pluginDir = mc.gameDirectory.toPath().resolve("rusherhack").resolve("basefinder");
            Files.createDirectories(pluginDir);
            visitedPortalsFile = pluginDir.resolve("visited_portals.json");
            loadVisitedPortals();
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Failed to init persistence: {}", e.getMessage());
        }

        // Configure components
        configureElytraBot();
        chunkScanner.setMinScore(minScore.getValue());
        chunkScanner.setDetectConstruction(true);
        chunkScanner.setDetectStorage(true);
        chunkScanner.setDetectStash(true);
        chunkScanner.setDetectFarm(true);
        chunkScanner.setDetectPortal(true);
        chunkScanner.setDetectMapArt(true);
        chunkScanner.setDetectTrails(true);
        chunkScanner.setUseEntityScanning(true);
        chunkScanner.setUseClusterScoring(true);

        baseLogger.setLogToChat(logToChat.getValue());
        baseLogger.setLogToFile(true);

        survivalManager.setEnableAutoTotem(true);
        survivalManager.setEnableAutoEat(true);
        survivalManager.setEnablePlayerDetection(true);
        survivalManager.onEnable();

        // Reset state
        portalQueue.clear();
        currentPortalNether = null;
        currentPortalOverworld = null;
        overworldArrivalPos = null;
        sweepWaypoints.clear();
        currentSweepWaypoint = 0;
        sweepBaritoneActive = false;
        landingPhase = false;
        portalsVisited = 0;
        basesFound = 0;
        tickCounter = 0;
        messageThrottle = 0;
        lastDimension = getCurrentDimension();

        state = HunterState.SCANNING_NETHER;

        printChat(Lang.t(
                "Started! Scanning nether for portals (radius: " + scanRadius.getValue() + ")",
                "Démarré ! Scan du Nether pour portails (rayon : " + scanRadius.getValue() + ")"));
    }

    @Override
    public void onDisable() {
        elytraBot.stop();
        baritoneController.cancelAll();
        survivalManager.stop();
        releaseMovementKeys();
        state = HunterState.IDLE;

        if (mc.level != null) {
            printChat(String.format(Lang.t(
                    "Stopped. Portals visited: %d | Bases found: %d",
                    "Arrêté. Portails visités : %d | Bases trouvées : %d"),
                    portalsVisited, basesFound));
        }
    }

    // =================================================================
    // MAIN TICK
    // =================================================================

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        if (messageThrottle > 0) messageThrottle--;

        // Survival systems (highest priority)
        boolean disconnected = survivalManager.tick();
        if (disconnected) {
            elytraBot.stop();
            baritoneController.cancelAll();
            state = HunterState.IDLE;
            return;
        }

        // Dimension change detection
        String currentDim = getCurrentDimension();
        if (!currentDim.equals(lastDimension)) {
            onDimensionChanged(lastDimension, currentDim);
            lastDimension = currentDim;
        }

        // Progress log every 15 seconds
        if (tickCounter % 300 == 0 && state != HunterState.IDLE) {
            logProgress();
        }

        try {
            switch (state) {
                case IDLE -> {}
                case SCANNING_NETHER -> handleScanningNether();
                case TRAVELING_TO_PORTAL -> handleTravelingToPortal();
                case ENTERING_PORTAL -> handleEnteringPortal();
                case OVERWORLD_SWEEP -> handleOverworldSweep();
                case RETURNING_TO_PORTAL -> handleReturningToPortal();
                case EXITING_PORTAL -> handleExitingPortal();
            }
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Error in state {}: {}", state, e.getMessage());
            e.printStackTrace();
        }
    }

    // =================================================================
    // STATE: SCANNING_NETHER
    // =================================================================

    private void handleScanningNether() {
        // Scan periodically (every 2 seconds)
        if (tickCounter % 40 != 0) return;

        List<BlockPos> portals = scanForPortals(scanRadius.getValue());

        if (portals.isEmpty()) {
            if (messageThrottle == 0) {
                printChat(Lang.t(
                        "No portals found in loaded chunks. Move around or increase scan radius.",
                        "Aucun portail trouvé dans les chunks chargés. Déplacez-vous ou augmentez le rayon."));
                messageThrottle = 400;
            }
            return;
        }

        // Filter already-visited portals
        int totalFound = portals.size();
        if (!revisitPortals.getValue()) {
            portals.removeIf(this::isPortalVisited);
        }

        if (portals.isEmpty()) {
            if (messageThrottle == 0) {
                printChat(String.format(Lang.t(
                        "Found %d portal(s) but all already visited. Enable 'Revisit' or move to new area.",
                        "Trouvé %d portail(s) mais tous déjà visités. Activez 'Revisiter' ou bougez."),
                        totalFound));
                messageThrottle = 400;
            }
            return;
        }

        // Queue portals sorted by distance
        portalQueue.clear();
        portals.sort(Comparator.comparingDouble(p -> mc.player.blockPosition().distSqr(p)));
        portalQueue.addAll(portals);

        printChat(String.format(Lang.t(
                "Found %d new portal(s)! Starting with nearest at %d, %d, %d",
                "Trouvé %d nouveau(x) portail(s) ! Début avec le plus proche à %d, %d, %d"),
                portalQueue.size(),
                portalQueue.peek().getX(), portalQueue.peek().getY(), portalQueue.peek().getZ()));

        advanceToNextPortal();
    }

    // =================================================================
    // STATE: TRAVELING_TO_PORTAL
    // =================================================================

    private void handleTravelingToPortal() {
        if (currentPortalNether == null) {
            state = HunterState.SCANNING_NETHER;
            return;
        }

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentPortalNether.getX(), currentPortalNether.getZ());

        // === LANDING PHASE: ElytraBot finished, Baritone walks to portal ===
        if (landingPhase) {
            landingTimer++;

            if (baritoneController.isAvailable() && baritoneController.isLandingComplete()) {
                landingPhase = false;
                printChat(Lang.t("Reached portal area. Entering...", "Zone du portail atteinte. Entrée..."));
                baritoneController.cancelAll();
                beginEnteringPortal();
                return;
            }

            // Also check raw distance
            if (dist < 4) {
                landingPhase = false;
                baritoneController.cancelAll();
                beginEnteringPortal();
                return;
            }

            if (landingTimer > LANDING_TIMEOUT) {
                printChat(Lang.t("Landing timeout, trying to enter anyway.", "Timeout atterrissage, tentative d'entrée."));
                landingPhase = false;
                baritoneController.cancelAll();
                beginEnteringPortal();
            }
            return;
        }

        // === FLIGHT PHASE: ElytraBot flies toward portal ===
        if (useElytra.getValue() && elytraBot.isFlying()) {
            elytraBot.tick();

            // When close enough, initiate landing sequence
            if (dist < 100) {
                elytraBot.stop();
                // Let player land, then Baritone walks
                if (baritoneController.isAvailable()) {
                    baritoneController.configureForFastLanding();
                    baritoneGoalXZ(currentPortalNether.getX(), currentPortalNether.getZ());
                    landingPhase = true;
                    landingTimer = 0;
                    printChat(Lang.t("Near portal, landing...", "Proche du portail, atterrissage..."));
                } else {
                    beginEnteringPortal();
                }
                return;
            }
            return;
        }

        // === GROUND WALK via Baritone ===
        if (baritoneController.isAvailable()) {
            // Start Baritone if not already pathing
            if (!baritoneController.isPathing()) {
                baritoneGoalXZ(currentPortalNether.getX(), currentPortalNether.getZ());
            }

            if (dist < 4) {
                baritoneController.cancelAll();
                beginEnteringPortal();
                return;
            }

            // Stuck detection
            if (lastWalkPos != null && mc.player.position().distanceTo(lastWalkPos) < 0.05) {
                walkStuckTimer++;
                if (walkStuckTimer > SWEEP_STUCK_THRESHOLD) {
                    printChat(Lang.t("Stuck while walking to portal, skipping.", "Bloqué en marchant vers le portail, passage au suivant."));
                    walkStuckTimer = 0;
                    baritoneController.cancelAll();
                    skipCurrentPortal();
                    return;
                }
            } else {
                walkStuckTimer = 0;
            }
            lastWalkPos = mc.player.position();
        } else {
            // Fallback: manual walk
            walkTowards(currentPortalNether);
            if (dist < 4) {
                releaseMovementKeys();
                beginEnteringPortal();
            }
        }
    }

    // =================================================================
    // STATE: ENTERING_PORTAL
    // =================================================================

    private void beginEnteringPortal() {
        state = HunterState.ENTERING_PORTAL;
        portalWaitTimer = 0;
        releaseMovementKeys();
        baritoneController.cancelAll();
    }

    private void handleEnteringPortal() {
        portalWaitTimer++;

        // Walk into portal block
        if (currentPortalNether != null) {
            walkTowards(currentPortalNether);
        }

        // Dimension change handled by onDimensionChanged()

        if (portalWaitTimer > PORTAL_TIMEOUT_TICKS) {
            printChat(Lang.t(
                    "Portal entry timeout. Skipping this portal.",
                    "Timeout entrée portail. Passage au suivant."));
            releaseMovementKeys();
            skipCurrentPortal();
        }
    }

    // =================================================================
    // STATE: OVERWORLD_SWEEP
    // =================================================================

    private void handleOverworldSweep() {
        if (!isOverworld(getCurrentDimension())) return;

        int sweepDurationTicks = sweepDuration.getValue() * 20;
        int elapsed = tickCounter - sweepStartTick;

        // Timeout check
        if (elapsed > sweepDurationTicks) {
            printChat(String.format(Lang.t(
                    "Sweep complete (%ds). Bases found this sweep: %d. Returning to portal.",
                    "Sweep terminé (%ds). Bases trouvées : %d. Retour au portail."),
                    sweepDuration.getValue(), basesFound));
            beginReturnToPortal();
            return;
        }

        // Scan chunks every second
        if (tickCounter % 20 == 0) {
            List<ChunkAnalysis> finds = chunkScanner.scanLoadedChunks();
            for (ChunkAnalysis analysis : finds) {
                if (analysis.getBaseType() != BaseType.NONE && analysis.getBaseType() != BaseType.TRAIL) {
                    BaseRecord record = new BaseRecord(
                            analysis.getCenterBlockPos(),
                            analysis.getBaseType(),
                            analysis.getScore(),
                            analysis.getPlayerBlockCount(),
                            analysis.getStorageCount(),
                            analysis.getShulkerCount()
                    );
                    baseLogger.logBase(record);
                    basesFound++;
                    printChat(String.format(Lang.t(
                            "BASE DETECTED! %s score=%.0f at %d, %d",
                            "BASE DÉTECTÉE ! %s score=%.0f à %d, %d"),
                            analysis.getBaseType().getDisplayName(),
                            analysis.getScore(),
                            analysis.getCenterBlockPos().getX(),
                            analysis.getCenterBlockPos().getZ()));
                }
            }
        }

        // Navigate sweep waypoints via Baritone
        if (sweepWaypoints.isEmpty()) return;

        if (currentSweepWaypoint >= sweepWaypoints.size()) {
            // All waypoints visited, sweep done
            printChat(Lang.t(
                    "All sweep waypoints visited. Returning to portal.",
                    "Tous les waypoints de sweep visités. Retour au portail."));
            beginReturnToPortal();
            return;
        }

        BlockPos waypoint = sweepWaypoints.get(currentSweepWaypoint);

        // Start Baritone toward current waypoint if not active
        if (!sweepBaritoneActive || !baritoneController.isPathing()) {
            if (!sweepBaritoneActive) {
                baritoneGoalNear(waypoint, 5);
                sweepBaritoneActive = true;
                sweepStuckTimer = 0;
                lastSweepPos = mc.player.position();
            }
        }

        // Check if waypoint reached
        double distToWaypoint = horizontalDist(mc.player.getX(), mc.player.getZ(),
                waypoint.getX(), waypoint.getZ());

        boolean baritoneFinished = baritoneController.isAvailable()
                && !baritoneController.isPathing()
                && sweepBaritoneActive;

        if (distToWaypoint < 8 || baritoneFinished) {
            currentSweepWaypoint++;
            sweepBaritoneActive = false;
            baritoneController.cancelAll();

            if (currentSweepWaypoint < sweepWaypoints.size()) {
                int remaining = sweepWaypoints.size() - currentSweepWaypoint;
                int elapsed_s = elapsed / 20;
                if (tickCounter % 100 < 20) { // Don't spam
                    printChat(String.format(Lang.t(
                            "Sweep waypoint %d/%d reached. %d remaining. Time: %ds/%ds",
                            "Waypoint sweep %d/%d atteint. %d restants. Temps : %ds/%ds"),
                            currentSweepWaypoint, sweepWaypoints.size(),
                            remaining, elapsed_s, sweepDuration.getValue()));
                }
            }
            return;
        }

        // Stuck detection during sweep
        if (lastSweepPos != null && mc.player.position().distanceTo(lastSweepPos) < 0.05) {
            sweepStuckTimer++;
            if (sweepStuckTimer > SWEEP_STUCK_THRESHOLD) {
                printChat(Lang.t(
                        "Stuck during sweep, skipping to next waypoint.",
                        "Bloqué pendant le sweep, passage au waypoint suivant."));
                sweepStuckTimer = 0;
                sweepBaritoneActive = false;
                baritoneController.cancelAll();
                currentSweepWaypoint++;
            }
        } else {
            sweepStuckTimer = 0;
        }
        lastSweepPos = mc.player.position();
    }

    // =================================================================
    // STATE: RETURNING_TO_PORTAL
    // =================================================================

    private void beginReturnToPortal() {
        chunkScanner.reset();
        sweepBaritoneActive = false;
        baritoneController.cancelAll();
        state = HunterState.RETURNING_TO_PORTAL;
        landingPhase = false;
        walkStuckTimer = 0;
        lastWalkPos = null;

        if (currentPortalOverworld == null && overworldArrivalPos != null) {
            currentPortalOverworld = overworldArrivalPos;
        }

        if (currentPortalOverworld != null) {
            double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                    currentPortalOverworld.getX(), currentPortalOverworld.getZ());
            printChat(String.format(Lang.t(
                    "Returning to overworld portal at %d, %d (%.0f blocks)",
                    "Retour au portail overworld à %d, %d (%.0f blocs)"),
                    currentPortalOverworld.getX(), currentPortalOverworld.getZ(), dist));
        }
    }

    private void handleReturningToPortal() {
        if (currentPortalOverworld == null) {
            printChat(Lang.t(
                    "Lost portal position. Skipping to next.",
                    "Position du portail perdue. Passage au suivant."));
            skipCurrentPortal();
            return;
        }

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentPortalOverworld.getX(), currentPortalOverworld.getZ());

        // Use Baritone to walk back (we're already on the ground from sweep)
        if (baritoneController.isAvailable()) {
            if (!baritoneController.isPathing()) {
                baritoneGoalXZ(currentPortalOverworld.getX(), currentPortalOverworld.getZ());
            }

            if (dist < 5) {
                baritoneController.cancelAll();
                // Scan for the actual portal block nearby
                BlockPos portalBlock = scanForNearestPortal(32);
                if (portalBlock != null) {
                    currentPortalOverworld = portalBlock;
                }
                beginExitingPortal();
                return;
            }

            // Stuck detection
            if (lastWalkPos != null && mc.player.position().distanceTo(lastWalkPos) < 0.05) {
                walkStuckTimer++;
                if (walkStuckTimer > SWEEP_STUCK_THRESHOLD) {
                    printChat(Lang.t(
                            "Stuck returning to portal. Trying to force next portal.",
                            "Bloqué en retournant au portail. Tentative du portail suivant."));
                    walkStuckTimer = 0;
                    baritoneController.cancelAll();
                    skipCurrentPortal();
                    return;
                }
            } else {
                walkStuckTimer = 0;
            }
            lastWalkPos = mc.player.position();
        } else {
            // Fallback walk
            walkTowards(currentPortalOverworld);
            if (dist < 4) {
                releaseMovementKeys();
                beginExitingPortal();
            }
        }
    }

    // =================================================================
    // STATE: EXITING_PORTAL
    // =================================================================

    private void beginExitingPortal() {
        state = HunterState.EXITING_PORTAL;
        portalWaitTimer = 0;
        releaseMovementKeys();
        baritoneController.cancelAll();
        printChat(Lang.t("Entering portal to return to Nether...", "Entrée dans le portail pour retourner au Nether..."));
    }

    private void handleExitingPortal() {
        portalWaitTimer++;

        if (currentPortalOverworld != null) {
            walkTowards(currentPortalOverworld);
        }

        // Dimension change handled by onDimensionChanged()

        if (portalWaitTimer > PORTAL_TIMEOUT_TICKS) {
            printChat(Lang.t(
                    "Portal exit timeout. Trying next portal.",
                    "Timeout sortie portail. Tentative du portail suivant."));
            releaseMovementKeys();
            // If we're stuck in the overworld, try finding a portal nearby
            BlockPos nearbyPortal = scanForNearestPortal(64);
            if (nearbyPortal != null) {
                currentPortalOverworld = nearbyPortal;
                portalWaitTimer = 0;
                printChat(Lang.t(
                        "Found nearby portal, retrying...",
                        "Portail proche trouvé, nouvelle tentative..."));
            } else {
                skipCurrentPortal();
            }
        }
    }

    // =================================================================
    // DIMENSION CHANGE HANDLER
    // =================================================================

    private void onDimensionChanged(String from, String to) {
        printChat(String.format(Lang.t("Dimension: %s → %s", "Dimension : %s → %s"), from, to));

        releaseMovementKeys();
        elytraBot.stop();
        baritoneController.cancelAll();

        if (state == HunterState.ENTERING_PORTAL && isOverworld(to)) {
            // Successfully entered overworld
            overworldArrivalPos = mc.player.blockPosition().immutable();
            // Compute expected overworld coords from nether portal
            if (currentPortalNether != null) {
                currentPortalOverworld = new BlockPos(
                        currentPortalNether.getX() * 8,
                        mc.player.blockPosition().getY(),
                        currentPortalNether.getZ() * 8);
            } else {
                currentPortalOverworld = overworldArrivalPos;
            }

            // Mark portal visited
            markPortalVisited(currentPortalNether);
            portalsVisited++;

            printChat(String.format(Lang.t(
                    "Arrived in Overworld at %d, %d! Starting sweep (radius: %d, duration: %ds)",
                    "Arrivé dans l'Overworld à %d, %d ! Début du sweep (rayon : %d, durée : %ds)"),
                    overworldArrivalPos.getX(), overworldArrivalPos.getZ(),
                    sweepRadius.getValue(), sweepDuration.getValue()));

            beginOverworldSweep();

        } else if (state == HunterState.EXITING_PORTAL && isNether(to)) {
            // Successfully returned to nether
            printChat(Lang.t(
                    "Back in the Nether! Moving to next portal.",
                    "Retour dans le Nether ! Passage au portail suivant."));

            advanceToNextPortal();

        } else {
            // Unexpected transition
            printChat(Lang.t(
                    "Unexpected dimension change. Resetting to scan.",
                    "Changement de dimension inattendu. Reprise du scan."));
            state = HunterState.SCANNING_NETHER;
        }
    }

    // =================================================================
    // OVERWORLD SWEEP SETUP
    // =================================================================

    private void beginOverworldSweep() {
        state = HunterState.OVERWORLD_SWEEP;
        sweepStartTick = tickCounter;
        currentSweepWaypoint = 0;
        sweepBaritoneActive = false;
        sweepStuckTimer = 0;
        lastSweepPos = null;
        chunkScanner.reset();

        // Generate circular waypoints around arrival point
        sweepWaypoints.clear();
        int radius = sweepRadius.getValue();
        double cx = overworldArrivalPos.getX();
        double cz = overworldArrivalPos.getZ();

        for (int i = 0; i < SWEEP_NUM_WAYPOINTS; i++) {
            double angle = (2.0 * Math.PI * i) / SWEEP_NUM_WAYPOINTS;
            int wx = (int) (cx + Math.cos(angle) * radius);
            int wz = (int) (cz + Math.sin(angle) * radius);
            sweepWaypoints.add(new BlockPos(wx, 64, wz));
        }

        printChat(String.format(Lang.t(
                "Generated %d sweep waypoints in a %d-block radius circle.",
                "Généré %d waypoints de sweep en cercle de rayon %d blocs."),
                sweepWaypoints.size(), radius));
    }

    // =================================================================
    // PORTAL SCANNING
    // =================================================================

    /**
     * Scan loaded chunks for nether portal blocks. Returns a deduplicated
     * list of portal positions (one per portal structure, not per block).
     */
    private List<BlockPos> scanForPortals(int radius) {
        if (mc.player == null || mc.level == null) return Collections.emptyList();

        BlockPos playerPos = mc.player.blockPosition();
        List<BlockPos> rawPortals = new ArrayList<>();

        int minCX = (playerPos.getX() - radius) >> 4;
        int maxCX = (playerPos.getX() + radius) >> 4;
        int minCZ = (playerPos.getZ() - radius) >> 4;
        int maxCZ = (playerPos.getZ() + radius) >> 4;

        var chunkSource = mc.level.getChunkSource();
        boolean inNether = isNether(getCurrentDimension());
        int minY = inNether ? 20 : 40;
        int maxY = inNether ? 128 : 140;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                LevelChunk chunk = chunkSource.getChunk(cx, cz, false);
                if (chunk == null) continue;

                int baseX = cx << 4;
                int baseZ = cz << 4;

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                            if (chunk.getBlockState(pos).getBlock() == Blocks.NETHER_PORTAL) {
                                rawPortals.add(pos);
                                break; // Skip rest of Y in this column
                            }
                        }
                    }
                }
            }
        }

        // Deduplicate: merge portal blocks within 8 blocks of each other
        return deduplicatePortals(rawPortals, 8);
    }

    /**
     * Scan nearby chunks for the closest single portal block.
     */
    private BlockPos scanForNearestPortal(int radius) {
        List<BlockPos> portals = scanForPortals(radius);
        if (portals.isEmpty()) return null;
        portals.sort(Comparator.comparingDouble(p -> mc.player.blockPosition().distSqr(p)));
        return portals.get(0);
    }

    /**
     * Merge raw portal block positions into unique portal structures.
     * Two portal blocks within `mergeRadius` are considered the same portal.
     */
    private List<BlockPos> deduplicatePortals(List<BlockPos> raw, int mergeRadius) {
        List<BlockPos> unique = new ArrayList<>();
        int mergeSq = mergeRadius * mergeRadius;

        for (BlockPos pos : raw) {
            boolean merged = false;
            for (BlockPos existing : unique) {
                double dx = pos.getX() - existing.getX();
                double dz = pos.getZ() - existing.getZ();
                if (dx * dx + dz * dz < mergeSq) {
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                unique.add(pos);
            }
        }
        return unique;
    }

    // =================================================================
    // PORTAL QUEUE MANAGEMENT
    // =================================================================

    private void advanceToNextPortal() {
        releaseMovementKeys();
        landingPhase = false;
        walkStuckTimer = 0;
        lastWalkPos = null;

        if (portalQueue.isEmpty()) {
            printChat(Lang.t(
                    "No more portals in queue. Rescanning...",
                    "Plus de portails en file. Rescan..."));
            state = HunterState.SCANNING_NETHER;
            return;
        }

        currentPortalNether = portalQueue.poll();
        currentPortalOverworld = null;
        overworldArrivalPos = null;

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentPortalNether.getX(), currentPortalNether.getZ());

        printChat(String.format(Lang.t(
                "Next portal: %d, %d, %d (%.0f blocks away). %d remaining in queue.",
                "Portail suivant : %d, %d, %d (%.0f blocs). %d restants en file."),
                currentPortalNether.getX(), currentPortalNether.getY(), currentPortalNether.getZ(),
                dist, portalQueue.size()));

        state = HunterState.TRAVELING_TO_PORTAL;

        // Start navigation
        if (useElytra.getValue() && hasElytra() && dist > 50) {
            configureElytraBot();
            // Nether: low cruise altitude to stay under bedrock ceiling
            elytraBot.setCruiseAltitude(Math.min(cruiseAltitude.getValue(), 90.0));
            elytraBot.startFlight(currentPortalNether);
            printChat(Lang.t("Flying to portal via Elytra.", "Vol vers le portail en Elytra."));
        } else if (baritoneController.isAvailable()) {
            baritoneGoalXZ(currentPortalNether.getX(), currentPortalNether.getZ());
            printChat(Lang.t("Walking to portal via Baritone.", "Marche vers le portail via Baritone."));
        }
    }

    private void skipCurrentPortal() {
        releaseMovementKeys();
        elytraBot.stop();
        baritoneController.cancelAll();

        if (currentPortalNether != null) {
            markPortalVisited(currentPortalNether);
        }

        // If we're in the overworld, we need to get back to nether first
        if (isOverworld(getCurrentDimension())) {
            BlockPos nearbyPortal = scanForNearestPortal(64);
            if (nearbyPortal != null) {
                currentPortalOverworld = nearbyPortal;
                beginExitingPortal();
                return;
            }
            // Can't find a portal to go back — stuck in overworld
            printChat(Lang.t(
                    "Stuck in Overworld, no portal found nearby. Disabling.",
                    "Bloqué dans l'Overworld, aucun portail proche. Désactivation."));
            this.toggle();
            return;
        }

        advanceToNextPortal();
    }

    // =================================================================
    // PERSISTENCE — visited_portals.json
    // =================================================================

    private static class VisitedPortal {
        int x;
        int z;
        long timestamp;

        VisitedPortal() {}

        VisitedPortal(int x, int z, long timestamp) {
            this.x = x;
            this.z = z;
            this.timestamp = timestamp;
        }
    }

    private void loadVisitedPortals() {
        visitedPortals.clear();
        if (visitedPortalsFile == null || !Files.exists(visitedPortalsFile)) return;

        try {
            String json = Files.readString(visitedPortalsFile);
            List<VisitedPortal> loaded = GSON.fromJson(json,
                    new TypeToken<List<VisitedPortal>>() {}.getType());
            if (loaded != null) {
                visitedPortals.addAll(loaded);
                printChat(String.format(Lang.t(
                        "Loaded %d visited portals from file.",
                        "Chargé %d portails visités depuis le fichier."),
                        visitedPortals.size()));
            }
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Failed to load visited portals: {}", e.getMessage());
        }
    }

    private void saveVisitedPortals() {
        if (visitedPortalsFile == null) return;
        try {
            String json = GSON.toJson(visitedPortals);
            Files.writeString(visitedPortalsFile, json);
        } catch (IOException e) {
            LOGGER.error("[PortalHunter] Failed to save visited portals: {}", e.getMessage());
        }
    }

    private boolean isPortalVisited(BlockPos pos) {
        for (VisitedPortal vp : visitedPortals) {
            double dx = pos.getX() - vp.x;
            double dz = pos.getZ() - vp.z;
            if (dx * dx + dz * dz < 64) { // Within 8 blocks
                return true;
            }
        }
        return false;
    }

    private void markPortalVisited(BlockPos pos) {
        if (pos == null) return;
        if (isPortalVisited(pos)) return;
        visitedPortals.add(new VisitedPortal(pos.getX(), pos.getZ(), System.currentTimeMillis()));
        saveVisitedPortals();
    }

    // =================================================================
    // BARITONE HELPERS (reflection)
    // =================================================================

    /**
     * Navigate to X/Z coordinates via Baritone GoalXZ (Y-agnostic).
     */
    private void baritoneGoalXZ(int x, int z) {
        if (!baritoneController.isAvailable()) return;

        try {
            // Get Baritone instance
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = apiClass.getMethod("getProvider");
            Object provider = getProvider.invoke(null);
            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            Object baritone = getPrimary.invoke(null);

            // Get CustomGoalProcess
            Method getCustomGoal = baritone.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritone);

            // Create GoalXZ(x, z)
            Class<?> goalXZClass = Class.forName("baritone.api.pathing.goals.GoalXZ");
            Object goal = goalXZClass.getConstructor(int.class, int.class).newInstance(x, z);

            // setGoalAndPath
            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            Method setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoalAndPath.invoke(goalProcess, goal);

            LOGGER.info("[PortalHunter] Baritone GoalXZ set: {}, {}", x, z);
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Baritone GoalXZ failed: {}", e.getMessage());
            // Fallback: use BaritoneController.landAt which uses GoalNear
            baritoneController.landAt(new BlockPos(x, 64, z));
        }
    }

    /**
     * Navigate near a position via Baritone GoalNear with given tolerance.
     */
    private void baritoneGoalNear(BlockPos pos, int range) {
        if (!baritoneController.isAvailable()) return;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = apiClass.getMethod("getProvider");
            Object provider = getProvider.invoke(null);
            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            Object baritone = getPrimary.invoke(null);

            Method getCustomGoal = baritone.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoal.invoke(baritone);

            Class<?> goalNearClass = Class.forName("baritone.api.pathing.goals.GoalNear");
            Object goal = goalNearClass.getConstructor(BlockPos.class, int.class).newInstance(pos, range);

            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            Method setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoalAndPath.invoke(goalProcess, goal);

            LOGGER.info("[PortalHunter] Baritone GoalNear set: {} range {}", pos.toShortString(), range);
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Baritone GoalNear failed: {}", e.getMessage());
            baritoneController.landAt(pos);
        }
    }

    // =================================================================
    // ELYTRA CONFIGURATION
    // =================================================================

    private void configureElytraBot() {
        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setUseFlightNoise(true);
        elytraBot.setUseObstacleAvoidance(true);
        elytraBot.setEnableCircling(true);
        elytraBot.setBaritoneController(baritoneController);
        elytraBot.setUseBaritoneLanding(baritoneController.isAvailable());
        elytraBot.setMinElytraDurability(10);
    }

    private boolean hasElytra() {
        if (mc.player == null) return false;
        return mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    // =================================================================
    // ELYTRABOT CONFLICT CHECK
    // =================================================================

    private boolean isElytraBotInUse() {
        for (String name : new String[]{"ElytraBot", "BaseFinder", "AutoTravel"}) {
            try {
                IModule other = RusherHackAPI.getModuleManager().getFeature(name).orElse(null);
                if (other instanceof ToggleableModule tm && tm != this && tm.isToggled()) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    // =================================================================
    // MOVEMENT HELPERS
    // =================================================================

    private void walkTowards(BlockPos target) {
        if (mc.player == null) return;

        double dx = target.getX() + 0.5 - mc.player.getX();
        double dz = target.getZ() + 0.5 - mc.player.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // Smooth rotation
        float currentYaw = mc.player.getYRot();
        float yawDiff = wrapDegrees(targetYaw - currentYaw);
        float yawStep = Math.min(Math.abs(yawDiff), 8.0f) * Math.signum(yawDiff);
        mc.player.setYRot(currentYaw + yawStep);

        mc.options.keyUp.setDown(true);

        if (mc.player.getFoodData().getFoodLevel() > 6) {
            mc.options.keySprint.setDown(true);
        }

        // Jump when blocked
        if (mc.player.horizontalCollision && mc.player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
        }

        // Swim
        if (mc.player.isInWater() || mc.player.isInLava()) {
            mc.options.keyJump.setDown(true);
        }
    }

    private void releaseMovementKeys() {
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
        }
    }

    // =================================================================
    // DIMENSION HELPERS
    // =================================================================

    private String getCurrentDimension() {
        if (mc.level == null) return "unknown";
        var dimKey = mc.level.dimension();
        if (dimKey == Level.OVERWORLD) return "overworld";
        if (dimKey == Level.NETHER) return "nether";
        return dimKey.location().toString();
    }

    private boolean isOverworld(String dim) { return "overworld".equals(dim); }
    private boolean isNether(String dim) { return "nether".equals(dim); }

    // =================================================================
    // UTILITY
    // =================================================================

    private double horizontalDist(double x1, double z1, double x2, double z2) {
        return Math.sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1));
    }

    private float wrapDegrees(float degrees) {
        degrees = degrees % 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    private void printChat(String msg) {
        if (logToChat.getValue()) {
            ChatUtils.print("[PortalHunter] " + msg);
        }
        LOGGER.info("[PortalHunter] {}", msg);
    }

    private void logProgress() {
        String dim = getCurrentDimension();
        int queueSize = portalQueue.size();
        printChat(String.format(Lang.t(
                "State: %s | Dim: %s | Portals visited: %d | Queue: %d | Bases: %d",
                "État : %s | Dim : %s | Portails visités : %d | File : %d | Bases : %d"),
                state.name(), dim, portalsVisited, queueSize, basesFound));
    }

    // === PUBLIC GETTERS (for HUD integration) ===
    public HunterState getHunterState() { return state; }
    public int getPortalsVisited() { return portalsVisited; }
    public int getBasesFound() { return basesFound; }
    public int getQueueSize() { return portalQueue.size(); }
    public ElytraBot getElytraBot() { return elytraBot; }
    public ChunkScanner getChunkScanner() { return chunkScanner; }
    public BaseLogger getBaseLogger() { return baseLogger; }
}
