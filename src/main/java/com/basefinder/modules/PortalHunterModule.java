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
import org.rusherhack.core.setting.NullSetting;
import org.rusherhack.core.setting.NumberSetting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * PortalHunter v2 — Zone-based nether portal base hunting.
 *
 * Flow:
 * 1. Traverse a user-defined Nether zone (zigzag pattern)
 * 2. Scan loaded chunks for nether portals as we move
 * 3. For each portal: enter → overworld circular sweep → return → mark visited
 * 4. Resume zone traversal until fully covered
 *
 * Navigation: Baritone only (elytra + walking). No custom ElytraBot.
 */
public class PortalHunterModule extends ToggleableModule {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PortalHunter");

    // === COMPONENTS ===
    private final BaritoneController baritone = new BaritoneController();
    private final ElytraBot elytraBot = new ElytraBot();
    private final ChunkScanner chunkScanner = new ChunkScanner();
    private final BaseLogger baseLogger = new BaseLogger();
    private final SurvivalManager survivalManager = new SurvivalManager();

    // === SETTINGS ===

    // Zone Nether
    private final NumberSetting<Integer> zoneMinX = new NumberSetting<>("Zone Min X", 0, -3750000, 3750000).incremental(100);
    private final NumberSetting<Integer> zoneMaxX = new NumberSetting<>("Zone Max X", 10000, -3750000, 3750000).incremental(100);
    private final NumberSetting<Integer> zoneMinZ = new NumberSetting<>("Zone Min Z", 0, -3750000, 3750000).incremental(100);
    private final NumberSetting<Integer> zoneMaxZ = new NumberSetting<>("Zone Max Z", 10000, -3750000, 3750000).incremental(100);
    private final NumberSetting<Integer> zoneSpacing = new NumberSetting<>("Espacement", 200, 50, 1000).incremental(50);
    private final NullSetting zoneGroup = new NullSetting("Zone Nether");

    // Sweep Overworld
    private final NumberSetting<Integer> sweepRadius = new NumberSetting<>("Rayon sweep", 500, 100, 3000).incremental(100);
    private final NumberSetting<Integer> sweepPoints = new NumberSetting<>("Points cercle", 8, 4, 24).incremental(1);
    private final NullSetting sweepGroup = new NullSetting("Sweep Overworld");

    // Detection
    private final NumberSetting<Double> minScore = new NumberSetting<>("Score minimum", 30.0, 5.0, 200.0);
    private final BooleanSetting detectConstruction = new BooleanSetting("Construction", "Détecter les constructions", true);
    private final BooleanSetting detectStorage = new BooleanSetting("Stockage", "Détecter le stockage", true);
    private final BooleanSetting detectStash = new BooleanSetting("Stash", "Détecter les stashs isolés", true);
    private final BooleanSetting detectFarm = new BooleanSetting("Farm", "Détecter les farms", true);
    private final NullSetting detectionGroup = new NullSetting("Détection");

    // Navigation
    private final BooleanSetting useElytra = new BooleanSetting("Elytra Nether", "Utiliser Baritone elytra dans le Nether", true);
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude croisière", 200.0, 50.0, 350.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Intervalle fusées", 40, 10, 100).incremental(5);
    private final NullSetting navGroup = new NullSetting("Navigation");

    // Survival
    private final BooleanSetting autoTotem = new BooleanSetting("Auto Totem", "Totem automatique en offhand", true);
    private final BooleanSetting autoEat = new BooleanSetting("Auto Eat", "Manger automatiquement", true);
    private final BooleanSetting playerDetect = new BooleanSetting("Détection joueurs", "Déconnecter si joueur détecté", true);
    private final NullSetting survivalGroup = new NullSetting("Survie");

    // Interface
    private final BooleanSetting logToChat = new BooleanSetting("Alertes chat", "Afficher les événements dans le chat", true);
    private final BooleanSetting debugMode = new BooleanSetting("Debug", "Logs détaillés dans le chat", false);
    private final BooleanSetting langFr = new BooleanSetting("Français", "Interface en français (off = English)", true);

    // === STATE MACHINE ===
    public enum HunterState {
        IDLE,
        ZONE_TRAVERSAL,
        TRAVELING_TO_PORTAL,
        ENTERING_PORTAL,
        OVERWORLD_SWEEP,
        RETURNING_TO_PORTAL,
        ENTERING_NETHER
    }

    private HunterState state = HunterState.IDLE;
    private int tickCounter = 0;

    // Zone traversal
    private final List<BlockPos> zoneWaypoints = new ArrayList<>();
    private int currentZoneWaypoint = 0;
    private int savedZoneWaypoint = 0; // saved index when interrupted by portal processing

    // Portal queue (discovered portals awaiting processing)
    private final LinkedList<BlockPos> portalQueue = new LinkedList<>();
    private final Set<Long> knownPortalKeys = new HashSet<>(); // avoid re-queueing same portal

    // Current portal being processed
    private BlockPos currentPortalNether = null;
    private BlockPos currentPortalOverworld = null;
    private BlockPos overworldArrivalPos = null;

    // Dimension tracking
    private String lastDimension = "";

    // Portal entry timer
    private int portalWaitTimer = 0;
    private static final int PORTAL_TIMEOUT = 300; // 15 seconds

    // Sweep state
    private final List<BlockPos> sweepWaypoints = new ArrayList<>();
    private int currentSweepWaypoint = 0;

    // Stuck detection
    private int stuckTimer = 0;
    private Vec3 lastPos = null;
    private static final int STUCK_THRESHOLD = 200; // 10 seconds

    // Nether elytra: ElytraBot takeoff → Baritone elytra handoff
    private boolean netherTakeoffInProgress = false;
    private int netherTargetX, netherTargetZ;

    // Persistence
    private final List<VisitedPortal> visitedPortals = new ArrayList<>();
    private Path visitedPortalsFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Stats
    private int portalsVisited = 0;
    private int basesFound = 0;
    private int messageThrottle = 0;

    public PortalHunterModule() {
        super("PortalHunter", "Scan automatique de bases via portails du Nether", ModuleCategory.EXTERNAL);

        zoneGroup.addSubSettings(zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ, zoneSpacing);
        sweepGroup.addSubSettings(sweepRadius, sweepPoints);
        detectionGroup.addSubSettings(minScore, detectConstruction, detectStorage, detectStash, detectFarm);
        navGroup.addSubSettings(useElytra, cruiseAltitude, fireworkInterval);
        survivalGroup.addSubSettings(autoTotem, autoEat, playerDetect);

        this.registerSettings(
                zoneGroup, sweepGroup, detectionGroup, navGroup, survivalGroup,
                logToChat, debugMode, langFr
        );
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    public void onEnable() {
        Lang.setFrench(langFr.getValue());

        if (mc.player == null || mc.level == null) {
            printChat(Lang.t("Must be in a world!", "Vous devez être dans un monde !"));
            this.toggle();
            return;
        }

        if (!isNether(getCurrentDimension())) {
            printChat(Lang.t("ERROR: You must be in the Nether!", "ERREUR : Vous devez être dans le Nether !"));
            this.toggle();
            return;
        }

        if (isConflictingModuleActive()) {
            printChat(Lang.t("ERROR: Another navigation module is active!", "ERREUR : Un autre module de navigation est actif !"));
            this.toggle();
            return;
        }

        if (!baritone.isAvailable()) {
            printChat(Lang.t("ERROR: Baritone not available!", "ERREUR : Baritone non disponible !"));
            this.toggle();
            return;
        }

        // Init persistence
        try {
            Path pluginDir = mc.gameDirectory.toPath().resolve("rusherhack").resolve("basefinder");
            Files.createDirectories(pluginDir);
            visitedPortalsFile = pluginDir.resolve("visited_portals.json");
            loadVisitedPortals();
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Failed to init persistence: {}", e.getMessage());
        }

        // Configure chunk scanner
        chunkScanner.setMinScore(minScore.getValue());
        chunkScanner.setDetectConstruction(detectConstruction.getValue());
        chunkScanner.setDetectStorage(detectStorage.getValue());
        chunkScanner.setDetectStash(detectStash.getValue());
        chunkScanner.setDetectFarm(detectFarm.getValue());
        chunkScanner.setDetectPortal(true);
        chunkScanner.setDetectMapArt(true);
        chunkScanner.setDetectTrails(true);
        chunkScanner.setUseEntityScanning(true);
        chunkScanner.setUseClusterScoring(true);

        baseLogger.setLogToChat(logToChat.getValue());
        baseLogger.setLogToFile(true);

        // Configure ElytraBot for overworld sweep
        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setUseFlightNoise(true);
        elytraBot.setUseObstacleAvoidance(true);
        elytraBot.setEnableCircling(false);
        elytraBot.setBaritoneController(baritone);
        elytraBot.setUseBaritoneLanding(baritone.isAvailable());
        elytraBot.setMinElytraDurability(10);

        // Configure survival
        survivalManager.setEnableAutoTotem(autoTotem.getValue());
        survivalManager.setEnableAutoEat(autoEat.getValue());
        survivalManager.setEnablePlayerDetection(playerDetect.getValue());
        survivalManager.onEnable();

        // Generate zone waypoints
        generateZoneWaypoints();

        // Reset state
        portalQueue.clear();
        knownPortalKeys.clear();
        currentPortalNether = null;
        currentPortalOverworld = null;
        overworldArrivalPos = null;
        portalsVisited = 0;
        basesFound = 0;
        tickCounter = 0;
        messageThrottle = 0;
        stuckTimer = 0;
        lastPos = null;
        lastDimension = getCurrentDimension();

        state = HunterState.ZONE_TRAVERSAL;

        String elytraStatus = baritone.isElytraAvailable()
                ? Lang.t("Elytra: YES", "Elytra: OUI")
                : Lang.t("Elytra: NO (walking only)", "Elytra: NON (marche seule)");

        printChat(String.format(Lang.t(
                "Started! Zone: [%d,%d] to [%d,%d] | %d waypoints | Sweep: %d | %s",
                "Démarré ! Zone : [%d,%d] à [%d,%d] | %d waypoints | Sweep : %d | %s"),
                zoneMinX.getValue(), zoneMinZ.getValue(),
                zoneMaxX.getValue(), zoneMaxZ.getValue(),
                zoneWaypoints.size(), sweepRadius.getValue(), elytraStatus));

        // Warn if player is far from zone
        double distToZone = distanceToZone(mc.player.getX(), mc.player.getZ());
        if (distToZone > 1000) {
            printChat(String.format(Lang.t(
                    "WARNING: You are %.0f blocks from the zone! Consider adjusting zone bounds.",
                    "ATTENTION : Vous êtes à %.0f blocs de la zone ! Ajustez les limites."),
                    distToZone));
        }

        navigateToCurrentZoneWaypoint();
    }

    @Override
    public void onDisable() {
        netherTakeoffInProgress = false;
        elytraBot.stop();
        baritone.cancelElytra();
        baritone.cancelAll();
        survivalManager.stop();
        releaseMovementKeys();
        state = HunterState.IDLE;

        if (mc.level != null) {
            printChat(String.format(Lang.t(
                    "Stopped. Portals: %d | Bases: %d | Progress: %d/%d waypoints",
                    "Arrêté. Portails : %d | Bases : %d | Progression : %d/%d waypoints"),
                    portalsVisited, basesFound, currentZoneWaypoint, zoneWaypoints.size()));
        }
    }

    // =========================================================================
    // MAIN TICK
    // =========================================================================

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        if (messageThrottle > 0) messageThrottle--;

        // Handle Nether elytra takeoff (ElytraBot → Baritone handoff)
        if (netherTakeoffInProgress) {
            handleNetherTakeoff();
            return; // Don't process state machine during takeoff
        }

        // Survival (highest priority)
        if (survivalManager.tick()) {
            baritone.cancelAll();
            state = HunterState.IDLE;
            return;
        }

        // Dimension change detection
        String currentDim = getCurrentDimension();
        if (!currentDim.equals(lastDimension)) {
            onDimensionChanged(lastDimension, currentDim);
            lastDimension = currentDim;
        }

        // Progress log every 30 seconds
        if (tickCounter % 600 == 0 && state != HunterState.IDLE) {
            logProgress();
        }

        try {
            switch (state) {
                case IDLE -> {}
                case ZONE_TRAVERSAL -> handleZoneTraversal();
                case TRAVELING_TO_PORTAL -> handleTravelingToPortal();
                case ENTERING_PORTAL -> handleEnteringPortal();
                case OVERWORLD_SWEEP -> handleOverworldSweep();
                case RETURNING_TO_PORTAL -> handleReturningToPortal();
                case ENTERING_NETHER -> handleEnteringNether();
            }
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Error in state {}: {}", state, e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================================
    // STATE: ZONE_TRAVERSAL
    // =========================================================================

    private void handleZoneTraversal() {
        if (zoneWaypoints.isEmpty() || currentZoneWaypoint >= zoneWaypoints.size()) {
            printChat(Lang.t(
                    "Zone fully covered! Portals visited: " + portalsVisited + " | Bases found: " + basesFound,
                    "Zone entièrement couverte ! Portails visités : " + portalsVisited + " | Bases trouvées : " + basesFound));
            state = HunterState.IDLE;
            this.toggle();
            return;
        }

        // Scan for portals every 2 seconds
        if (tickCounter % 40 == 0) {
            List<BlockPos> newPortals = scanForNetherPortals();
            int added = 0;
            for (BlockPos portal : newPortals) {
                long key = portalKey(portal);
                if (!isPortalVisited(portal) && !knownPortalKeys.contains(key)) {
                    portalQueue.add(portal);
                    knownPortalKeys.add(key);
                    added++;
                }
            }

            if (added > 0) {
                // Sort queue by distance to player
                portalQueue.sort(Comparator.comparingDouble(p ->
                        horizontalDistSq(mc.player.getX(), mc.player.getZ(), p.getX(), p.getZ())));

                printChat(String.format(Lang.t(
                        "Found %d new portal(s)! Going to nearest. (%d in queue)",
                        "Trouvé %d nouveau(x) portail(s) ! Direction le plus proche. (%d en file)"),
                        added, portalQueue.size()));

                // Save zone progress and switch to portal processing
                savedZoneWaypoint = currentZoneWaypoint;
                baritone.cancelAll();
                stuckTimer = 0;
                lastPos = null;
                startNextPortal();
                return;
            }
        }

        // Check if current zone waypoint reached
        BlockPos waypoint = zoneWaypoints.get(currentZoneWaypoint);
        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(), waypoint.getX(), waypoint.getZ());

        if (dist < 20) {
            currentZoneWaypoint++;
            stuckTimer = 0;
            lastPos = null;

            if (currentZoneWaypoint >= zoneWaypoints.size()) {
                handleZoneTraversal(); // will trigger the "fully covered" message
                return;
            }

            if (messageThrottle == 0 && currentZoneWaypoint % 5 == 0) {
                printChat(String.format(Lang.t(
                        "Zone progress: %d/%d waypoints (%.1f%%)",
                        "Progression zone : %d/%d waypoints (%.1f%%)"),
                        currentZoneWaypoint, zoneWaypoints.size(),
                        (100.0 * currentZoneWaypoint) / zoneWaypoints.size()));
                messageThrottle = 200;
            }

            navigateToCurrentZoneWaypoint();
            return;
        }

        // Stuck detection
        if (checkStuck()) {
            printChat(Lang.t("Stuck during zone traversal, skipping waypoint.",
                    "Bloqué pendant le parcours, passage au waypoint suivant."));
            baritone.cancelAll();
            currentZoneWaypoint++;
            resetStuck();
            if (currentZoneWaypoint < zoneWaypoints.size()) {
                navigateToCurrentZoneWaypoint();
            }
            return;
        }

        // Re-issue navigation if not actively moving
        if (!baritone.isPathing() && !baritone.isElytraFlying() && tickCounter % 60 == 0) {
            // After elytra landing (< 150 blocks), always walk to avoid re-launch loop
            if (dist <= 150) {
                baritone.goToXZ(waypoint.getX(), waypoint.getZ());
                debug("Walk (post-landing) -> zone wp " + currentZoneWaypoint + " (" + (int)dist + " blocs)");
            } else {
                // Far away: re-launch elytra
                navigateToCurrentZoneWaypoint();
            }
        }
    }

    private void navigateToCurrentZoneWaypoint() {
        if (currentZoneWaypoint >= zoneWaypoints.size()) return;

        BlockPos wp = zoneWaypoints.get(currentZoneWaypoint);
        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(), wp.getX(), wp.getZ());

        // Elytra for any meaningful distance in the Nether (> 50)
        if (useElytra.getValue() && hasElytra() && dist > 50 && baritone.isElytraAvailable()) {
            startNetherElytraFlight(wp.getX(), wp.getZ());
            return;
        }
        // Walking only for short distances (post-elytra landing or no elytra)
        baritone.goToXZ(wp.getX(), wp.getZ());
        debug("Walk -> zone wp " + currentZoneWaypoint + " (" + (int)dist + " blocs)");
    }

    // =========================================================================
    // STATE: TRAVELING_TO_PORTAL
    // =========================================================================

    private void handleTravelingToPortal() {
        if (currentPortalNether == null) {
            startNextPortal();
            return;
        }

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentPortalNether.getX(), currentPortalNether.getZ());

        // Close enough — enter portal
        if (dist < 5) {
            baritone.cancelElytra();
            baritone.cancelAll();
            debug("Arrivé au portail (dist=" + String.format("%.1f", dist) + "), entrée...");
            beginEnteringPortal();
            return;
        }

        // Elytra flying: check if close enough to land and walk
        if (baritone.isElytraFlying() && dist < 30) {
            baritone.cancelElytra();
            baritone.goToXZ(currentPortalNether.getX(), currentPortalNether.getZ());
            debug("Elytra -> walk transition (dist=" + (int)dist + ")");
            return;
        }

        // Stuck detection
        if (checkStuck()) {
            printChat(Lang.t("Stuck going to portal, skipping.",
                    "Bloqué en allant au portail, passage au suivant."));
            skipCurrentPortal();
            return;
        }

        // Re-issue navigation if Baritone stopped and elytra not flying
        if (!baritone.isPathing() && !baritone.isElytraFlying() && tickCounter % 60 == 0) {
            if (dist > 150 && useElytra.getValue() && hasElytra() && baritone.isElytraAvailable()) {
                startNetherElytraFlight(currentPortalNether.getX(), currentPortalNether.getZ());
                debug("Elytra -> portail (" + (int)dist + " blocs)");
            } else {
                baritone.goToXZ(currentPortalNether.getX(), currentPortalNether.getZ());
                debug("Walk (post-landing) -> portail (" + (int)dist + " blocs)");
            }
        }
    }

    // =========================================================================
    // STATE: ENTERING_PORTAL
    // =========================================================================

    private void beginEnteringPortal() {
        state = HunterState.ENTERING_PORTAL;
        portalWaitTimer = 0;
        releaseMovementKeys();
        baritone.cancelAll();
    }

    private void handleEnteringPortal() {
        portalWaitTimer++;

        if (currentPortalNether != null && mc.player != null && mc.level != null) {
            // Check if player is actually INSIDE a portal block
            BlockPos feet = mc.player.blockPosition();
            boolean inPortal = mc.level.getBlockState(feet).getBlock() == Blocks.NETHER_PORTAL
                    || mc.level.getBlockState(feet.above()).getBlock() == Blocks.NETHER_PORTAL;

            if (inPortal) {
                // Confirmed inside portal — stop and wait for teleportation
                releaseMovementKeys();
                if (portalWaitTimer % 60 == 0) {
                    debug("DANS portail block, attente TP...");
                }
            } else {
                double distToPortal = Math.sqrt(mc.player.blockPosition().distSqr(currentPortalNether));

                if (distToPortal > 4) {
                    // Far from portal — use Baritone for 3D navigation to correct Y
                    if (!baritone.isPathing() && portalWaitTimer % 20 == 1) {
                        BlockPos floorPos = currentPortalNether.below();
                        baritone.goToNear(floorPos, 2);
                        debug("Baritone -> portail 3D (dist=" + String.format("%.0f", distToPortal) + ")");
                    }
                } else {
                    // Close to portal — cancel Baritone and walk directly into it
                    baritone.cancelAll();
                    walkTowards(currentPortalNether);
                    if (portalWaitTimer % 40 == 0) {
                        debug("walkTowards portail (dist=" + String.format("%.1f", distToPortal) + ")");
                    }
                }
            }
        }

        // Dimension change handled by onDimensionChanged()

        if (portalWaitTimer > PORTAL_TIMEOUT) {
            printChat(Lang.t("Portal entry timeout, skipping.",
                    "Timeout entrée portail, passage au suivant."));
            releaseMovementKeys();
            baritone.cancelAll();
            debug("Portal timeout at " + (currentPortalNether != null ? currentPortalNether.toShortString() : "null"));
            skipCurrentPortal();
        }
    }

    // =========================================================================
    // STATE: OVERWORLD_SWEEP
    // =========================================================================

    private void handleOverworldSweep() {
        if (!isOverworld(getCurrentDimension())) return;

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
                            "BASE FOUND! %s score=%.0f at %d, %d",
                            "BASE TROUVÉE ! %s score=%.0f à %d, %d"),
                            analysis.getBaseType().getDisplayName(),
                            analysis.getScore(),
                            analysis.getCenterBlockPos().getX(),
                            analysis.getCenterBlockPos().getZ()));
                }
            }
        }

        // All sweep waypoints visited
        if (sweepWaypoints.isEmpty() || currentSweepWaypoint >= sweepWaypoints.size()) {
            printChat(Lang.t("Sweep complete. Returning to portal.",
                    "Sweep terminé. Retour au portail."));
            beginReturnToPortal();
            return;
        }

        // Tick ElytraBot if flying
        if (elytraBot.isFlying()) {
            elytraBot.tick();
        }

        BlockPos waypoint = sweepWaypoints.get(currentSweepWaypoint);
        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                waypoint.getX(), waypoint.getZ());

        // ElytraBot close to waypoint: stop flight, advance
        if (elytraBot.isFlying() && dist < 100) {
            elytraBot.stop();
            debug("ElytraBot landing near sweep wp (dist=" + (int)dist + ")");
        }

        // Waypoint reached (on ground, close enough)
        boolean navIdle = !baritone.isPathing() && !elytraBot.isFlying();
        if (dist < 30 || (navIdle && stuckTimer == 0 && dist < 100)) {
            currentSweepWaypoint++;
            resetStuck();

            if (currentSweepWaypoint < sweepWaypoints.size()) {
                BlockPos next = sweepWaypoints.get(currentSweepWaypoint);
                navigateToSweepWaypoint(next);
                int remaining = sweepWaypoints.size() - currentSweepWaypoint;
                printChat(String.format(Lang.t(
                        "Sweep: %d/%d waypoints. %d remaining.",
                        "Sweep : %d/%d waypoints. %d restants."),
                        currentSweepWaypoint, sweepWaypoints.size(), remaining));
            }
            return;
        }

        // Stuck detection (only when not flying)
        if (!elytraBot.isFlying() && checkStuck()) {
            printChat(Lang.t("Stuck during sweep, skipping waypoint.",
                    "Bloqué pendant le sweep, passage au waypoint suivant."));
            elytraBot.stop();
            baritone.cancelAll();
            currentSweepWaypoint++;
            resetStuck();
            if (currentSweepWaypoint < sweepWaypoints.size()) {
                navigateToSweepWaypoint(sweepWaypoints.get(currentSweepWaypoint));
            }
            return;
        }

        // Re-issue if nothing is happening
        if (!baritone.isPathing() && !elytraBot.isFlying() && tickCounter % 60 == 0) {
            navigateToSweepWaypoint(waypoint);
        }
    }

    // =========================================================================
    // STATE: RETURNING_TO_PORTAL
    // =========================================================================

    private void beginReturnToPortal() {
        chunkScanner.reset();
        elytraBot.stop();
        baritone.cancelAll();
        state = HunterState.RETURNING_TO_PORTAL;
        resetStuck();

        if (currentPortalOverworld == null && overworldArrivalPos != null) {
            currentPortalOverworld = overworldArrivalPos;
        }

        if (currentPortalOverworld != null) {
            baritone.goToXZ(currentPortalOverworld.getX(), currentPortalOverworld.getZ());
            double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                    currentPortalOverworld.getX(), currentPortalOverworld.getZ());
            printChat(String.format(Lang.t(
                    "Returning to portal at %d, %d (%.0f blocks)",
                    "Retour au portail à %d, %d (%.0f blocs)"),
                    currentPortalOverworld.getX(), currentPortalOverworld.getZ(), dist));
        }
    }

    private void handleReturningToPortal() {
        if (currentPortalOverworld == null) {
            printChat(Lang.t("Lost portal position, skipping.",
                    "Position du portail perdue, passage au suivant."));
            skipCurrentPortal();
            return;
        }

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentPortalOverworld.getX(), currentPortalOverworld.getZ());

        if (dist < 5) {
            baritone.cancelAll();
            // Find actual portal block
            BlockPos actualPortal = scanForNearestPortalBlock(32);
            if (actualPortal != null) {
                currentPortalOverworld = actualPortal;
            }
            beginEnteringNether();
            return;
        }

        // Stuck detection
        if (checkStuck()) {
            printChat(Lang.t("Stuck returning, looking for nearby portal.",
                    "Bloqué en retournant, recherche de portail proche."));
            baritone.cancelAll();
            BlockPos nearbyPortal = scanForNearestPortalBlock(64);
            if (nearbyPortal != null) {
                currentPortalOverworld = nearbyPortal;
                baritone.goToXZ(nearbyPortal.getX(), nearbyPortal.getZ());
                resetStuck();
            } else {
                skipCurrentPortal();
            }
            return;
        }

        // Re-issue nav
        if (!baritone.isPathing() && tickCounter % 60 == 0) {
            baritone.goToXZ(currentPortalOverworld.getX(), currentPortalOverworld.getZ());
        }
    }

    // =========================================================================
    // STATE: ENTERING_NETHER
    // =========================================================================

    private void beginEnteringNether() {
        state = HunterState.ENTERING_NETHER;
        portalWaitTimer = 0;
        releaseMovementKeys();
        baritone.cancelAll();
        printChat(Lang.t("Entering portal to return to Nether...",
                "Entrée dans le portail pour retourner au Nether..."));
    }

    private void handleEnteringNether() {
        portalWaitTimer++;

        if (currentPortalOverworld != null && mc.player != null && mc.level != null) {
            BlockPos feet = mc.player.blockPosition();
            boolean inPortal = mc.level.getBlockState(feet).getBlock() == Blocks.NETHER_PORTAL
                    || mc.level.getBlockState(feet.above()).getBlock() == Blocks.NETHER_PORTAL;

            if (inPortal) {
                releaseMovementKeys();
            } else {
                if (!baritone.isPathing() && portalWaitTimer % 20 == 1) {
                    BlockPos floorPos = currentPortalOverworld.below();
                    baritone.goToNear(floorPos, 2);
                }
            }
        }

        // Dimension change handled by onDimensionChanged()

        if (portalWaitTimer > PORTAL_TIMEOUT) {
            printChat(Lang.t("Portal exit timeout.", "Timeout sortie portail."));
            releaseMovementKeys();
            BlockPos nearbyPortal = scanForNearestPortalBlock(64);
            if (nearbyPortal != null) {
                currentPortalOverworld = nearbyPortal;
                portalWaitTimer = 0;
            } else {
                skipCurrentPortal();
            }
        }
    }

    // =========================================================================
    // DIMENSION CHANGE HANDLER
    // =========================================================================

    private void onDimensionChanged(String from, String to) {
        printChat(String.format(Lang.t("Dimension: %s -> %s", "Dimension : %s -> %s"), from, to));

        releaseMovementKeys();
        netherTakeoffInProgress = false;
        elytraBot.stop();
        baritone.cancelElytra();
        baritone.cancelAll();
        debug("Dimension change: " + from + " -> " + to + " (state=" + state + ")");

        if (state == HunterState.ENTERING_PORTAL && isOverworld(to)) {
            // Successfully entered Overworld
            overworldArrivalPos = mc.player.blockPosition().immutable();
            if (currentPortalNether != null) {
                currentPortalOverworld = new BlockPos(
                        currentPortalNether.getX() * 8,
                        mc.player.blockPosition().getY(),
                        currentPortalNether.getZ() * 8);
            } else {
                currentPortalOverworld = overworldArrivalPos;
            }

            portalsVisited++;

            printChat(String.format(Lang.t(
                    "Overworld at %d, %d! Starting sweep (radius: %d, %d points)",
                    "Overworld à %d, %d ! Début du sweep (rayon : %d, %d points)"),
                    overworldArrivalPos.getX(), overworldArrivalPos.getZ(),
                    sweepRadius.getValue(), sweepPoints.getValue()));

            beginOverworldSweep();

        } else if (state == HunterState.ENTERING_NETHER && isNether(to)) {
            // Successfully returned to Nether
            markPortalVisited(currentPortalNether);
            printChat(Lang.t(
                    "Back in Nether! Portal marked as visited.",
                    "Retour au Nether ! Portail marqué comme visité."));

            resetStuck();

            // Check if more portals in queue
            if (!portalQueue.isEmpty()) {
                startNextPortal();
            } else {
                // Resume zone traversal
                currentZoneWaypoint = savedZoneWaypoint;
                state = HunterState.ZONE_TRAVERSAL;
                navigateToCurrentZoneWaypoint();
                printChat(Lang.t("Resuming zone traversal.",
                        "Reprise du parcours de zone."));
            }

        } else {
            printChat(Lang.t("Unexpected dimension change, resetting.",
                    "Changement de dimension inattendu, reset."));
            if (isNether(to)) {
                state = HunterState.ZONE_TRAVERSAL;
                resetStuck();
                navigateToCurrentZoneWaypoint();
            } else {
                // Stuck in wrong dimension
                state = HunterState.IDLE;
                this.toggle();
            }
        }
    }

    // =========================================================================
    // OVERWORLD SWEEP SETUP
    // =========================================================================

    private void beginOverworldSweep() {
        state = HunterState.OVERWORLD_SWEEP;
        currentSweepWaypoint = 0;
        resetStuck();
        chunkScanner.reset();

        // Generate circular waypoints around arrival point
        sweepWaypoints.clear();
        int radius = sweepRadius.getValue();
        int points = sweepPoints.getValue();
        double cx = overworldArrivalPos.getX();
        double cz = overworldArrivalPos.getZ();

        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI * i) / points;
            int wx = (int) (cx + Math.cos(angle) * radius);
            int wz = (int) (cz + Math.sin(angle) * radius);
            sweepWaypoints.add(new BlockPos(wx, 64, wz));
        }

        printChat(String.format(Lang.t(
                "Generated %d sweep waypoints in %d-block radius.",
                "Généré %d waypoints de sweep en rayon de %d blocs."),
                sweepWaypoints.size(), radius));

        if (!sweepWaypoints.isEmpty()) {
            navigateToSweepWaypoint(sweepWaypoints.get(0));
        }
    }

    /**
     * Navigate to a sweep waypoint using ElytraBot (works in all dimensions).
     */
    private void navigateToSweepWaypoint(BlockPos wp) {
        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(), wp.getX(), wp.getZ());
        if (hasElytra() && dist > 50) {
            elytraBot.startFlight(wp);
            debug("ElytraBot -> sweep wp (" + (int)dist + " blocs)");
        } else {
            baritone.goToXZ(wp.getX(), wp.getZ());
            debug("Walk -> sweep wp (" + (int)dist + " blocs)");
        }
    }

    // =========================================================================
    // ZONE WAYPOINT GENERATION
    // =========================================================================

    private void generateZoneWaypoints() {
        zoneWaypoints.clear();
        currentZoneWaypoint = 0;

        int minX = Math.min(zoneMinX.getValue(), zoneMaxX.getValue());
        int maxX = Math.max(zoneMinX.getValue(), zoneMaxX.getValue());
        int minZ = Math.min(zoneMinZ.getValue(), zoneMaxZ.getValue());
        int maxZ = Math.max(zoneMinZ.getValue(), zoneMaxZ.getValue());
        int spacing = zoneSpacing.getValue();

        // Boustrophedon (zigzag) pattern for efficient coverage
        boolean leftToRight = true;
        for (int z = minZ; z <= maxZ; z += spacing) {
            if (leftToRight) {
                for (int x = minX; x <= maxX; x += spacing) {
                    zoneWaypoints.add(new BlockPos(x, 64, z));
                }
            } else {
                for (int x = maxX; x >= minX; x -= spacing) {
                    zoneWaypoints.add(new BlockPos(x, 64, z));
                }
            }
            leftToRight = !leftToRight;
        }

        // Skip to nearest waypoint from player position
        if (!zoneWaypoints.isEmpty() && mc.player != null) {
            int nearestIdx = 0;
            double nearestDist = Double.MAX_VALUE;
            for (int i = 0; i < zoneWaypoints.size(); i++) {
                BlockPos wp = zoneWaypoints.get(i);
                double d = horizontalDistSq(mc.player.getX(), mc.player.getZ(), wp.getX(), wp.getZ());
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestIdx = i;
                }
            }
            currentZoneWaypoint = nearestIdx;
        }
    }

    // =========================================================================
    // PORTAL SCANNING
    // =========================================================================

    /**
     * Scan all loaded Nether chunks for portal blocks.
     * Returns deduplicated portal positions.
     */
    private List<BlockPos> scanForNetherPortals() {
        if (mc.player == null || mc.level == null) return Collections.emptyList();

        List<BlockPos> rawPortals = new ArrayList<>();
        var chunkSource = mc.level.getChunkSource();
        int renderDist = mc.options.renderDistance().get();
        int playerCX = mc.player.chunkPosition().x;
        int playerCZ = mc.player.chunkPosition().z;

        for (int cx = playerCX - renderDist; cx <= playerCX + renderDist; cx++) {
            for (int cz = playerCZ - renderDist; cz <= playerCZ + renderDist; cz++) {
                LevelChunk chunk = chunkSource.getChunk(cx, cz, false);
                if (chunk == null) continue;

                int baseX = cx << 4;
                int baseZ = cz << 4;

                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 20; y < 128; y++) {
                            BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                            if (chunk.getBlockState(pos).getBlock() == Blocks.NETHER_PORTAL) {
                                rawPortals.add(pos);
                                break; // skip rest of Y in this column
                            }
                        }
                    }
                }
            }
        }

        return deduplicatePortals(rawPortals, 8);
    }

    /**
     * Find nearest portal block within radius (used in overworld to find return portal).
     */
    private BlockPos scanForNearestPortalBlock(int radius) {
        if (mc.player == null || mc.level == null) return null;

        List<BlockPos> found = new ArrayList<>();
        var chunkSource = mc.level.getChunkSource();
        BlockPos playerPos = mc.player.blockPosition();
        int minCX = (playerPos.getX() - radius) >> 4;
        int maxCX = (playerPos.getX() + radius) >> 4;
        int minCZ = (playerPos.getZ() - radius) >> 4;
        int maxCZ = (playerPos.getZ() + radius) >> 4;

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
                                found.add(pos);
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (found.isEmpty()) return null;
        found.sort(Comparator.comparingDouble(p -> playerPos.distSqr(p)));
        return found.get(0);
    }

    /**
     * Merge portal blocks within mergeRadius into unique portal positions.
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

    // =========================================================================
    // PORTAL QUEUE MANAGEMENT
    // =========================================================================

    private void startNextPortal() {
        resetStuck();

        if (portalQueue.isEmpty()) {
            // Resume zone traversal
            currentZoneWaypoint = savedZoneWaypoint;
            state = HunterState.ZONE_TRAVERSAL;
            navigateToCurrentZoneWaypoint();
            printChat(Lang.t("No more portals in queue, resuming zone scan.",
                    "Plus de portails en file, reprise du scan de zone."));
            return;
        }

        currentPortalNether = portalQueue.poll();
        currentPortalOverworld = null;
        overworldArrivalPos = null;

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentPortalNether.getX(), currentPortalNether.getZ());

        printChat(String.format(Lang.t(
                "Next portal: %d, %d, %d (%.0f blocks). %d in queue.",
                "Portail suivant : %d, %d, %d (%.0f blocs). %d en file."),
                currentPortalNether.getX(), currentPortalNether.getY(), currentPortalNether.getZ(),
                dist, portalQueue.size()));

        state = HunterState.TRAVELING_TO_PORTAL;

        // Elytra for any meaningful distance (> 50), walk for short
        if (useElytra.getValue() && hasElytra() && dist > 50 && baritone.isElytraAvailable()) {
            startNetherElytraFlight(currentPortalNether.getX(), currentPortalNether.getZ());
            debug("Elytra -> portail (" + (int)dist + " blocs)");
        } else {
            baritone.goToXZ(currentPortalNether.getX(), currentPortalNether.getZ());
            debug("Walk -> portail (" + (int)dist + " blocs)");
        }
    }

    private void skipCurrentPortal() {
        releaseMovementKeys();
        baritone.cancelAll();

        if (currentPortalNether != null) {
            markPortalVisited(currentPortalNether);
        }

        // If in overworld, try to find portal back to nether
        if (isOverworld(getCurrentDimension())) {
            BlockPos nearbyPortal = scanForNearestPortalBlock(64);
            if (nearbyPortal != null) {
                currentPortalOverworld = nearbyPortal;
                beginEnteringNether();
                return;
            }
            printChat(Lang.t("Stuck in Overworld, no portal nearby. Disabling.",
                    "Bloqué dans l'Overworld, aucun portail proche. Désactivation."));
            this.toggle();
            return;
        }

        startNextPortal();
    }

    // =========================================================================
    // PERSISTENCE — visited_portals.json
    // =========================================================================

    private static class VisitedPortal {
        int x;
        int z;
        long timestamp;
        boolean baseFound;

        VisitedPortal() {}

        VisitedPortal(int x, int z, long timestamp, boolean baseFound) {
            this.x = x;
            this.z = z;
            this.timestamp = timestamp;
            this.baseFound = baseFound;
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
                        "Loaded %d visited portals.",
                        "Chargé %d portails visités."),
                        visitedPortals.size()));
            }
        } catch (Exception e) {
            LOGGER.error("[PortalHunter] Failed to load portals: {}", e.getMessage());
        }
    }

    private void saveVisitedPortals() {
        if (visitedPortalsFile == null) return;
        try {
            Files.writeString(visitedPortalsFile, GSON.toJson(visitedPortals));
        } catch (IOException e) {
            LOGGER.error("[PortalHunter] Failed to save portals: {}", e.getMessage());
        }
    }

    private boolean isPortalVisited(BlockPos pos) {
        for (VisitedPortal vp : visitedPortals) {
            double dx = pos.getX() - vp.x;
            double dz = pos.getZ() - vp.z;
            if (dx * dx + dz * dz < 64) return true; // within 8 blocks
        }
        return false;
    }

    private void markPortalVisited(BlockPos pos) {
        if (pos == null || isPortalVisited(pos)) return;
        visitedPortals.add(new VisitedPortal(pos.getX(), pos.getZ(),
                System.currentTimeMillis(), basesFound > 0));
        saveVisitedPortals();
        LOGGER.info("[PortalHunter] Portal at {}, {} marked as visited (total: {})",
                pos.getX(), pos.getZ(), visitedPortals.size());
    }

    /**
     * Generate a unique key for a portal position, rounded to 8-block grid.
     */
    private long portalKey(BlockPos pos) {
        int gx = pos.getX() >> 3; // divide by 8
        int gz = pos.getZ() >> 3;
        return ((long) gx) << 32 | (gz & 0xFFFFFFFFL);
    }

    // =========================================================================
    // NETHER ELYTRA: ElytraBot takeoff → Baritone elytra handoff
    // =========================================================================

    /**
     * Start Nether elytra flight: ElytraBot handles takeoff, then Baritone elytra takes over.
     */
    private void startNetherElytraFlight(int x, int z) {
        // Already airborne — go straight to Baritone elytra
        if (mc.player != null && mc.player.isFallFlying() && !mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistance() > 0.3) {
            baritone.elytraTo(x, z);
            debug("Déjà en vol, Baritone elytra direct");
            return;
        }

        // Use ElytraBot for reliable takeoff
        netherTakeoffInProgress = true;
        netherTargetX = x;
        netherTargetZ = z;
        elytraBot.startFlight(new BlockPos(x, 64, z));
        debug("ElytraBot takeoff -> " + x + ", " + z);
    }

    /**
     * Tick ElytraBot during takeoff, hand off to Baritone elytra once airborne.
     */
    private void handleNetherTakeoff() {
        if (mc.player == null) {
            netherTakeoffInProgress = false;
            elytraBot.stop();
            return;
        }

        // Tick ElytraBot (handles jump, deploy, firework)
        elytraBot.tick();

        // Once airborne with speed → hand off to Baritone elytra
        if (mc.player.isFallFlying() && !mc.player.onGround()
                && mc.player.getDeltaMovement().horizontalDistance() > 0.5) {
            elytraBot.stop();
            baritone.elytraTo(netherTargetX, netherTargetZ);
            netherTakeoffInProgress = false;
            debug("Handoff Baritone elytra OK -> " + netherTargetX + ", " + netherTargetZ);
        }
    }

    // =========================================================================
    // STUCK DETECTION
    // =========================================================================

    private boolean checkStuck() {
        if (mc.player == null) return false;

        Vec3 currentPos = mc.player.position();
        if (lastPos != null && currentPos.distanceTo(lastPos) < 0.1) {
            stuckTimer++;
            return stuckTimer > STUCK_THRESHOLD;
        }
        stuckTimer = 0;
        lastPos = currentPos;
        return false;
    }

    private void resetStuck() {
        stuckTimer = 0;
        lastPos = null;
    }

    // =========================================================================
    // MOVEMENT HELPERS
    // =========================================================================

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

        if (mc.player.horizontalCollision && mc.player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
        }

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

    // =========================================================================
    // DIMENSION HELPERS
    // =========================================================================

    private String getCurrentDimension() {
        if (mc.level == null) return "unknown";
        var dimKey = mc.level.dimension();
        if (dimKey == Level.OVERWORLD) return "overworld";
        if (dimKey == Level.NETHER) return "nether";
        return dimKey.location().toString();
    }

    private boolean isOverworld(String dim) { return "overworld".equals(dim); }
    private boolean isNether(String dim) { return "nether".equals(dim); }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private boolean hasElytra() {
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean isConflictingModuleActive() {
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

    private double horizontalDist(double x1, double z1, double x2, double z2) {
        return Math.sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1));
    }

    private double distanceToZone(double px, double pz) {
        int minX = Math.min(zoneMinX.getValue(), zoneMaxX.getValue());
        int maxX = Math.max(zoneMinX.getValue(), zoneMaxX.getValue());
        int minZ = Math.min(zoneMinZ.getValue(), zoneMaxZ.getValue());
        int maxZ = Math.max(zoneMinZ.getValue(), zoneMaxZ.getValue());
        double dx = Math.max(0, Math.max(minX - px, px - maxX));
        double dz = Math.max(0, Math.max(minZ - pz, pz - maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double horizontalDistSq(double x1, double z1, double x2, double z2) {
        return (x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1);
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
        } else {
            LOGGER.info("[PortalHunter] {}", msg);
        }
    }

    private void debug(String msg) {
        LOGGER.info("[PortalHunter-DBG] {}", msg);
        if (debugMode.getValue()) {
            ChatUtils.print("\u00A77[PH-Debug] " + msg);
        }
    }

    private void logProgress() {
        printChat(String.format(Lang.t(
                "State: %s | Portals: %d | Bases: %d | Zone: %d/%d | Queue: %d",
                "État : %s | Portails : %d | Bases : %d | Zone : %d/%d | File : %d"),
                state.name(), portalsVisited, basesFound,
                currentZoneWaypoint, zoneWaypoints.size(), portalQueue.size()));
    }

    // === PUBLIC API ===
    public HunterState getHunterState() { return state; }
    public int getPortalsVisited() { return portalsVisited; }
    public int getBasesFound() { return basesFound; }
    public int getQueueSize() { return portalQueue.size(); }
    public ChunkScanner getChunkScanner() { return chunkScanner; }
    public BaseLogger getBaseLogger() { return baseLogger; }
    public int getZoneProgress() { return currentZoneWaypoint; }
    public int getZoneTotal() { return zoneWaypoints.size(); }
    public int getVisitedPortalsCount() { return visitedPortals.size(); }

    public int[] getZoneBounds() {
        return new int[] {
                zoneMinX.getValue(), zoneMaxX.getValue(),
                zoneMinZ.getValue(), zoneMaxZ.getValue()
        };
    }

    public void setZoneBounds(int minX, int maxX, int minZ, int maxZ) {
        zoneMinX.setValue(minX);
        zoneMaxX.setValue(maxX);
        zoneMinZ.setValue(minZ);
        zoneMaxZ.setValue(maxZ);
    }

    public int getSweepRadius() { return sweepRadius.getValue(); }
    public void setSweepRadius(int radius) { sweepRadius.setValue(radius); }

    public void clearVisitedPortals() {
        visitedPortals.clear();
        saveVisitedPortals();
    }
}
