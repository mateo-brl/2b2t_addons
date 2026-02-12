package com.basefinder.modules;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NullSetting;
import org.rusherhack.core.setting.NumberSetting;

/**
 * AutoTravel - Smart travel to coordinates.
 * Supports nether shortcuts (8x faster), elytra flight, and walk/sprint.
 * Automatically finds and uses nether portals for long overworld trips.
 */
public class AutoTravelModule extends ToggleableModule {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("AutoTravel");

    // === SETTINGS ===
    private final NumberSetting<Integer> targetX = new NumberSetting<>("Cible X", 0, -30000000, 30000000).incremental(100);
    private final NumberSetting<Integer> targetZ = new NumberSetting<>("Cible Z", 0, -30000000, 30000000).incremental(100);
    private final EnumSetting<TargetDimension> targetDimension = new EnumSetting<>("Dimension", TargetDimension.OVERWORLD);
    private final EnumSetting<TravelMode> travelMode = new EnumSetting<>("Mode", TravelMode.AUTO);
    private final BooleanSetting useNetherShortcut = new BooleanSetting("Raccourci Nether",
            "Utilise le Nether pour les longs trajets Overworld (x8 plus rapide)", true);
    private final NumberSetting<Integer> netherThreshold = new NumberSetting<>("Seuil Nether", 1000, 100, 100000).incremental(100);

    private final NullSetting elytraGroup = new NullSetting("Elytra");
    private final NumberSetting<Double> cruiseAltitude = new NumberSetting<>("Altitude croisière", 200.0, 50.0, 350.0);
    private final NumberSetting<Integer> fireworkInterval = new NumberSetting<>("Intervalle fusées", 40, 10, 100);
    private final NumberSetting<Integer> minDurability = new NumberSetting<>("Durabilité min.", 10, 1, 100);

    private final NullSetting walkGroup = new NullSetting("Marche");
    private final BooleanSetting autoSprint = new BooleanSetting("Sprint auto", true);
    private final BooleanSetting autoJump = new BooleanSetting("Saut auto", "Sauter par-dessus les obstacles", true);

    private final BooleanSetting langFr = new BooleanSetting("Français", "Interface en français (off = English)", true);

    // === ENUMS ===
    public enum TargetDimension { OVERWORLD, NETHER, END }
    public enum TravelMode { AUTO, ELYTRA, WALK }

    private enum TravelState {
        IDLE,
        FINDING_PORTAL,
        GOING_TO_PORTAL,
        ENTERING_PORTAL,
        NETHER_TRAVEL,
        FINDING_EXIT_PORTAL,
        GOING_TO_EXIT,
        EXITING_PORTAL,
        DIRECT_TRAVEL,
        ARRIVED
    }

    // === INTERNAL STATE ===
    private final ElytraBot elytraBot = new ElytraBot();
    private TravelState travelState = TravelState.IDLE;
    private BlockPos finalTarget;
    private BlockPos currentTarget;
    private boolean usingElytra = false;
    private boolean usingNetherRoute = false;
    private BlockPos portalPos = null;

    private int tickCounter = 0;
    private int portalWaitTimer = 0;
    private int stuckTimer = 0;
    private Vec3 lastPos = null;
    private String lastDimension = "";
    private int messageThrottle = 0;
    private int jumpCooldown = 0;

    public AutoTravelModule() {
        super("AutoTravel", "Voyage intelligent via Nether/Elytra/Marche", ModuleCategory.EXTERNAL);

        elytraGroup.addSubSettings(cruiseAltitude, fireworkInterval, minDurability);
        walkGroup.addSubSettings(autoSprint, autoJump);

        this.registerSettings(
                targetX, targetZ, targetDimension, travelMode,
                useNetherShortcut, netherThreshold,
                elytraGroup, walkGroup, langFr
        );
    }

    @Override
    public void onEnable() {
        Lang.setFrench(langFr.getValue());

        if (mc.player == null || mc.level == null) {
            ChatUtils.print("[AutoTravel] " + Lang.t("Must be in a world!", "Vous devez être dans un monde !"));
            this.toggle();
            return;
        }

        if (targetX.getValue() == 0 && targetZ.getValue() == 0) {
            ChatUtils.print("[AutoTravel] " + Lang.t("Set target X/Z first!", "Définissez Cible X/Z d'abord !"));
            this.toggle();
            return;
        }

        finalTarget = new BlockPos(targetX.getValue(), 64, targetZ.getValue());
        travelState = TravelState.IDLE;
        usingNetherRoute = false;
        portalPos = null;
        tickCounter = 0;
        stuckTimer = 0;
        lastPos = null;
        messageThrottle = 0;
        jumpCooldown = 0;
        lastDimension = getCurrentDimension();

        planRoute();
    }

    @Override
    public void onDisable() {
        elytraBot.stop();
        releaseMovementKeys();
        travelState = TravelState.IDLE;
        // Reset all internal state for clean re-enable
        currentTarget = null;
        finalTarget = null;
        portalPos = null;
        usingElytra = false;
        usingNetherRoute = false;
        tickCounter = 0;
        stuckTimer = 0;
        lastPos = null;
        portalWaitTimer = 0;
        messageThrottle = 0;
        jumpCooldown = 0;
        if (mc.level != null) {
            ChatUtils.print("[AutoTravel] " + Lang.t("Stopped.", "Arrêté."));
        }
    }

    @Subscribe
    private void onUpdate(EventUpdate event) {
        if (mc.player == null || mc.level == null) return;

        Lang.setFrench(langFr.getValue());
        tickCounter++;

        // Detect dimension change
        String currentDim = getCurrentDimension();
        if (!currentDim.equals(lastDimension)) {
            onDimensionChanged(lastDimension, currentDim);
            lastDimension = currentDim;
        }

        // Stuck detection
        if (lastPos != null && mc.player.position().distanceTo(lastPos) < 0.01
                && travelState != TravelState.IDLE && travelState != TravelState.ARRIVED
                && travelState != TravelState.ENTERING_PORTAL && travelState != TravelState.EXITING_PORTAL) {
            stuckTimer++;
            if (stuckTimer > 200 && !usingElytra) {
                // Stuck while walking for 10 seconds - try jumping
                if (mc.player.onGround()) {
                    mc.options.keyJump.setDown(true);
                    jumpCooldown = 10;
                }
                if (stuckTimer > 400) {
                    ChatUtils.print("[AutoTravel] " + Lang.t("Stuck! Try moving manually.", "Bloqué ! Essayez de bouger manuellement."));
                    stuckTimer = 0;
                }
            }
        } else {
            stuckTimer = 0;
        }
        lastPos = mc.player.position();

        // Message throttle
        if (messageThrottle > 0) messageThrottle--;

        // Progress logging every 10 seconds
        if (tickCounter % 200 == 0 && travelState != TravelState.IDLE && travelState != TravelState.ARRIVED) {
            logProgress();
        }

        try {
            switch (travelState) {
                case IDLE -> {}
                case FINDING_PORTAL -> handleFindingPortal();
                case GOING_TO_PORTAL -> handleGoingToPortal();
                case ENTERING_PORTAL -> handleEnteringPortal();
                case NETHER_TRAVEL -> handleNetherTravel();
                case FINDING_EXIT_PORTAL -> handleFindingExitPortal();
                case GOING_TO_EXIT -> handleGoingToExit();
                case EXITING_PORTAL -> handleExitingPortal();
                case DIRECT_TRAVEL -> handleDirectTravel();
                case ARRIVED -> {
                    releaseMovementKeys();
                    elytraBot.stop();
                    ChatUtils.print("[AutoTravel] " + Lang.t("Arrived at destination!", "Arrivé à destination !"));
                    this.toggle();
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AutoTravel] Error in state {}: {}", travelState, e.getMessage());
            releaseMovementKeys();
        }
    }

    // =====================================================
    // ROUTE PLANNING
    // =====================================================

    private void planRoute() {
        String currentDim = getCurrentDimension();
        TargetDimension target = targetDimension.getValue();
        double distance = horizontalDist(mc.player.getX(), mc.player.getZ(),
                targetX.getValue(), targetZ.getValue());

        usingElytra = shouldUseElytra();
        String methodStr = usingElytra
                ? Lang.t("Elytra", "Elytra")
                : Lang.t("Walking", "Marche");

        ChatUtils.print(String.format("[AutoTravel] " +
                        Lang.t("Target: %d, %d (%s) | Distance: %.0f | Method: %s",
                                "Cible : %d, %d (%s) | Distance : %.0f | Méthode : %s"),
                targetX.getValue(), targetZ.getValue(), target.name(), distance, methodStr));

        // Same dimension → direct or nether shortcut
        if (isSameDimension(currentDim, target)) {
            // Nether shortcut: overworld→overworld via nether for long distances
            if (isOverworld(currentDim) && target == TargetDimension.OVERWORLD
                    && useNetherShortcut.getValue() && distance > netherThreshold.getValue()) {

                int netherX = targetX.getValue() / 8;
                int netherZ = targetZ.getValue() / 8;
                double netherDist = horizontalDist(
                        mc.player.getX() / 8, mc.player.getZ() / 8, netherX, netherZ);

                ChatUtils.print(String.format("[AutoTravel] " +
                                Lang.t("Nether shortcut: %.0f blocks → %.0f in Nether (Nether coords: %d, %d)",
                                        "Raccourci Nether : %.0f blocs → %.0f dans le Nether (coords Nether : %d, %d)"),
                        distance, netherDist, netherX, netherZ));

                usingNetherRoute = true;
                travelState = TravelState.FINDING_PORTAL;
                return;
            }

            startDirectTravel(finalTarget);
            return;
        }

        // Cross-dimension travel
        if (isOverworld(currentDim) && target == TargetDimension.NETHER) {
            ChatUtils.print("[AutoTravel] " + Lang.t(
                    "Need to enter the Nether. Looking for portal...",
                    "Entrée dans le Nether nécessaire. Recherche de portail..."));
            usingNetherRoute = false;
            travelState = TravelState.FINDING_PORTAL;
            return;
        }

        if (isNether(currentDim) && target == TargetDimension.OVERWORLD) {
            // Already in nether, target overworld → travel to target/8, then exit
            int netherX = targetX.getValue() / 8;
            int netherZ = targetZ.getValue() / 8;
            ChatUtils.print(String.format("[AutoTravel] " +
                            Lang.t("In Nether → traveling to %d, %d then exiting to Overworld",
                                    "Dans le Nether → voyage vers %d, %d puis sortie Overworld"),
                    netherX, netherZ));
            usingNetherRoute = true;
            currentTarget = new BlockPos(netherX, 64, netherZ);
            travelState = TravelState.NETHER_TRAVEL;
            startNavigationTo(currentTarget);
            return;
        }

        // Fallback: direct travel
        startDirectTravel(finalTarget);
    }

    // =====================================================
    // STATE HANDLERS
    // =====================================================

    private void handleFindingPortal() {
        // Scan for portal blocks in loaded chunks
        if (tickCounter % 40 == 0 || portalPos == null) {
            portalPos = scanForPortal(256);
        }

        if (portalPos != null) {
            double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                    portalPos.getX(), portalPos.getZ());
            ChatUtils.print(String.format("[AutoTravel] " +
                            Lang.t("Portal found at %d, %d, %d (%.0f blocks)",
                                    "Portail trouvé à %d, %d, %d (%.0f blocs)"),
                    portalPos.getX(), portalPos.getY(), portalPos.getZ(), dist));

            currentTarget = portalPos;
            travelState = TravelState.GOING_TO_PORTAL;

            if (usingElytra && dist > 100) {
                startElytraTo(portalPos);
            }
        } else {
            if (messageThrottle == 0) {
                ChatUtils.print("[AutoTravel] " + Lang.t(
                        "No portal found nearby. Walk towards one or build one.",
                        "Aucun portail trouvé. Marchez vers un portail ou construisez-en un."));
                messageThrottle = 400; // 20 second cooldown
            }
        }
    }

    private void handleGoingToPortal() {
        if (currentTarget == null) {
            travelState = TravelState.FINDING_PORTAL;
            return;
        }

        navigateToTarget();

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentTarget.getX(), currentTarget.getZ());

        if (dist < 4) {
            ChatUtils.print("[AutoTravel] " + Lang.t(
                    "Portal reached. Entering...",
                    "Portail atteint. Entrée..."));
            releaseMovementKeys();
            elytraBot.stop();
            travelState = TravelState.ENTERING_PORTAL;
            portalWaitTimer = 0;
        }
    }

    private void handleEnteringPortal() {
        portalWaitTimer++;

        // Keep walking towards portal center
        if (portalPos != null) {
            walkTowards(portalPos);
        }

        // Dimension change detected by onDimensionChanged()
        if (portalWaitTimer > 300) { // 15 seconds timeout
            ChatUtils.print("[AutoTravel] " + Lang.t(
                    "Portal timeout. Try standing in the portal manually.",
                    "Timeout portail. Essayez de vous placer manuellement dans le portail."));
            portalWaitTimer = 0;
            releaseMovementKeys();
            travelState = TravelState.FINDING_PORTAL;
        }
    }

    private void handleNetherTravel() {
        if (currentTarget == null) {
            int netherX = targetX.getValue() / 8;
            int netherZ = targetZ.getValue() / 8;
            currentTarget = new BlockPos(netherX, 64, netherZ);
        }

        navigateToTarget();

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentTarget.getX(), currentTarget.getZ());

        if (dist < 30) {
            ChatUtils.print(String.format("[AutoTravel] " +
                            Lang.t("Reached nether position. Looking for exit portal...",
                                    "Position nether atteinte. Recherche de portail de sortie..."),
                    currentTarget.getX(), currentTarget.getZ()));
            releaseMovementKeys();
            elytraBot.stop();
            travelState = TravelState.FINDING_EXIT_PORTAL;
        }
    }

    private void handleFindingExitPortal() {
        if (tickCounter % 40 == 0 || portalPos == null) {
            portalPos = scanForPortal(256);
        }

        if (portalPos != null) {
            ChatUtils.print(String.format("[AutoTravel] " +
                            Lang.t("Exit portal found at %d, %d, %d",
                                    "Portail de sortie trouvé à %d, %d, %d"),
                    portalPos.getX(), portalPos.getY(), portalPos.getZ()));
            currentTarget = portalPos;
            travelState = TravelState.GOING_TO_EXIT;
        } else {
            if (messageThrottle == 0) {
                ChatUtils.print("[AutoTravel] " + Lang.t(
                        "No exit portal found. Build one or look around.",
                        "Aucun portail de sortie. Construisez-en un ou cherchez autour."));
                messageThrottle = 400;
            }
        }
    }

    private void handleGoingToExit() {
        if (currentTarget == null) {
            travelState = TravelState.FINDING_EXIT_PORTAL;
            return;
        }

        navigateToTarget();

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentTarget.getX(), currentTarget.getZ());

        if (dist < 4) {
            ChatUtils.print("[AutoTravel] " + Lang.t(
                    "Exiting portal...", "Sortie du portail..."));
            releaseMovementKeys();
            elytraBot.stop();
            travelState = TravelState.EXITING_PORTAL;
            portalWaitTimer = 0;
        }
    }

    private void handleExitingPortal() {
        portalWaitTimer++;

        if (portalPos != null) {
            walkTowards(portalPos);
        }

        // Dimension change detected by onDimensionChanged()
        if (portalWaitTimer > 300) {
            ChatUtils.print("[AutoTravel] " + Lang.t(
                    "Portal timeout. Try entering manually.",
                    "Timeout portail. Essayez d'entrer manuellement."));
            portalWaitTimer = 0;
            releaseMovementKeys();
            travelState = TravelState.FINDING_EXIT_PORTAL;
        }
    }

    private void handleDirectTravel() {
        if (currentTarget == null) return;

        navigateToTarget();

        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentTarget.getX(), currentTarget.getZ());

        if (usingElytra) {
            elytraBot.tick();

            if (dist < 50 && !elytraBot.isFlying() && mc.player.onGround()) {
                travelState = TravelState.ARRIVED;
                return;
            }

            // Elytra failed (no resources) → switch to walk
            if (!elytraBot.isFlying() && mc.player.onGround() && tickCounter > 100) {
                if (elytraBot.getFireworkCount() == 0 || elytraBot.getElytraCount() == 0) {
                    ChatUtils.print("[AutoTravel] " + Lang.t(
                            "Elytra travel failed. Switching to walking.",
                            "Vol elytra impossible. Passage en marche."));
                    usingElytra = false;
                }
            }
        } else {
            // Walk arrival
            if (dist < 10) {
                // Nether route: after walking to nether coords, find exit portal
                if (usingNetherRoute && isNether(getCurrentDimension())
                        && targetDimension.getValue() == TargetDimension.OVERWORLD) {
                    releaseMovementKeys();
                    travelState = TravelState.FINDING_EXIT_PORTAL;
                    return;
                }
                travelState = TravelState.ARRIVED;
            }
        }
    }

    // =====================================================
    // DIMENSION CHANGE
    // =====================================================

    private void onDimensionChanged(String from, String to) {
        ChatUtils.print(String.format("[AutoTravel] " +
                Lang.t("Dimension: %s → %s", "Dimension : %s → %s"), from, to));

        releaseMovementKeys();
        elytraBot.stop();
        portalPos = null;

        if (travelState == TravelState.ENTERING_PORTAL) {
            if (isNether(to) && usingNetherRoute) {
                // Entered nether for overworld shortcut
                int netherX = targetX.getValue() / 8;
                int netherZ = targetZ.getValue() / 8;
                currentTarget = new BlockPos(netherX, 64, netherZ);
                double dist = horizontalDist(mc.player.getX(), mc.player.getZ(), netherX, netherZ);

                ChatUtils.print(String.format("[AutoTravel] " +
                                Lang.t("In Nether! Traveling to %d, %d (%.0f blocks)",
                                        "Dans le Nether ! Voyage vers %d, %d (%.0f blocs)"),
                        netherX, netherZ, dist));

                usingElytra = shouldUseElytra();
                travelState = TravelState.NETHER_TRAVEL;
                if (usingElytra) {
                    startElytraTo(currentTarget);
                }
            } else if (isNether(to)) {
                // Entered nether as final destination
                startDirectTravel(new BlockPos(targetX.getValue(), 64, targetZ.getValue()));
            } else {
                // Unknown transition - go direct
                startDirectTravel(finalTarget);
            }
        } else if (travelState == TravelState.EXITING_PORTAL) {
            if (isOverworld(to)) {
                // Back in overworld after nether shortcut
                double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                        finalTarget.getX(), finalTarget.getZ());

                ChatUtils.print(String.format("[AutoTravel] " +
                                Lang.t("Back in Overworld! Target: %d, %d (%.0f blocks)",
                                        "Retour Overworld ! Cible : %d, %d (%.0f blocs)"),
                        finalTarget.getX(), finalTarget.getZ(), dist));

                usingElytra = shouldUseElytra();
                usingNetherRoute = false;
                startDirectTravel(finalTarget);
            } else {
                // Ended up somewhere unexpected
                ChatUtils.print("[AutoTravel] " + Lang.t(
                        "Unexpected dimension. Recalculating...",
                        "Dimension inattendue. Recalcul..."));
                planRoute();
            }
        }
    }

    // =====================================================
    // NAVIGATION
    // =====================================================

    private void startDirectTravel(BlockPos target) {
        currentTarget = target;
        travelState = TravelState.DIRECT_TRAVEL;
        startNavigationTo(target);
    }

    private void startNavigationTo(BlockPos target) {
        currentTarget = target;
        usingElytra = shouldUseElytra();

        if (usingElytra) {
            startElytraTo(target);
        }
    }

    private void startElytraTo(BlockPos target) {
        elytraBot.setCruiseAltitude(cruiseAltitude.getValue());
        elytraBot.setFireworkInterval(fireworkInterval.getValue());
        elytraBot.setMinElytraDurability(minDurability.getValue());
        elytraBot.setUseFlightNoise(true);
        elytraBot.setUseObstacleAvoidance(true);
        elytraBot.startFlight(target);

        ChatUtils.print(String.format("[AutoTravel] " +
                        Lang.t("Elytra flight to %d, %d | Fireworks: %d | Elytra: %d",
                                "Vol elytra vers %d, %d | Fusées : %d | Elytra : %d"),
                target.getX(), target.getZ(),
                elytraBot.getFireworkCount(), elytraBot.getElytraCount()));
        ChatUtils.print("[AutoTravel] " + Lang.t("Jump to take off!", "Sautez pour décoller !"));
    }

    private void navigateToTarget() {
        if (currentTarget == null) return;

        if (usingElytra) {
            elytraBot.tick();
        } else {
            walkTowards(currentTarget);
        }
    }

    // =====================================================
    // WALK MODE
    // =====================================================

    private void walkTowards(BlockPos target) {
        if (mc.player == null) return;

        // Face target
        double dx = target.getX() + 0.5 - mc.player.getX();
        double dz = target.getZ() + 0.5 - mc.player.getZ();
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // Smooth rotation
        float currentYaw = mc.player.getYRot();
        float yawDiff = wrapDegrees(targetYaw - currentYaw);
        float yawStep = Math.min(Math.abs(yawDiff), 8.0f) * Math.signum(yawDiff);
        mc.player.setYRot(currentYaw + yawStep);

        // Move forward
        mc.options.keyUp.setDown(true);

        // Sprint (need food level > 6)
        if (autoSprint.getValue() && mc.player.getFoodData().getFoodLevel() > 6) {
            mc.options.keySprint.setDown(true);
        }

        // Auto jump when blocked
        if (autoJump.getValue() && jumpCooldown <= 0) {
            if (isBlockedAhead() && mc.player.onGround()) {
                mc.options.keyJump.setDown(true);
                jumpCooldown = 8;
            } else if (jumpCooldown <= 0) {
                mc.options.keyJump.setDown(false);
            }
        }
        if (jumpCooldown > 0) {
            jumpCooldown--;
            if (jumpCooldown == 0) {
                mc.options.keyJump.setDown(false);
            }
        }

        // Swim - hold jump in water/lava
        if (mc.player.isInWater() || mc.player.isInLava()) {
            mc.options.keyJump.setDown(true);
        }
    }

    private boolean isBlockedAhead() {
        if (mc.player == null || mc.level == null) return false;

        Vec3 look = mc.player.getLookAngle();
        double checkX = mc.player.getX() + look.x * 0.8;
        double checkZ = mc.player.getZ() + look.z * 0.8;

        // Check block at feet level and knee level
        BlockPos feetPos = BlockPos.containing(checkX, mc.player.getY(), checkZ);
        BlockState feetBlock = mc.level.getBlockState(feetPos);

        if (!feetBlock.isAir() && !feetBlock.liquid()) {
            // Check if the block above is clear (can jump over 1-block obstacle)
            BlockPos aboveHead = BlockPos.containing(checkX, mc.player.getY() + 2, checkZ);
            BlockState aboveBlock = mc.level.getBlockState(aboveHead);
            if (aboveBlock.isAir() || aboveBlock.liquid()) {
                return true; // Can jump over
            }
        }
        return false;
    }

    private void releaseMovementKeys() {
        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyJump.setDown(false);
        }
    }

    // =====================================================
    // PORTAL SCANNING
    // =====================================================

    /**
     * Scan loaded chunks for nether portal blocks.
     * Returns the closest portal block position, or null.
     */
    private BlockPos scanForPortal(int radius) {
        if (mc.player == null || mc.level == null) return null;

        BlockPos playerPos = mc.player.blockPosition();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

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
                                double dist = playerPos.distSqr(pos);
                                if (dist < closestDist) {
                                    closestDist = dist;
                                    closest = pos;
                                }
                                break; // Skip rest of Y column
                            }
                        }
                    }
                }
            }
        }

        return closest;
    }

    // =====================================================
    // UTILITY
    // =====================================================

    private boolean shouldUseElytra() {
        if (mc.player == null) return false;
        if (travelMode.getValue() == TravelMode.WALK) return false;
        if (travelMode.getValue() == TravelMode.ELYTRA) return true;

        // AUTO: use elytra if equipped and has fireworks
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean hasElytra = chest.is(Items.ELYTRA);
        boolean hasFireworks = elytraBot.getFireworkCount() > 0;

        // Nether ceiling is 128 → elytra risky in AUTO, prefer walk
        if (isNether(getCurrentDimension())) {
            return false;
        }

        return hasElytra && hasFireworks;
    }

    private String getCurrentDimension() {
        if (mc.level == null) return "unknown";
        var dimKey = mc.level.dimension();
        if (dimKey == Level.OVERWORLD) return "overworld";
        if (dimKey == Level.NETHER) return "nether";
        if (dimKey == Level.END) return "end";
        return dimKey.location().toString();
    }

    private boolean isOverworld(String dim) { return "overworld".equals(dim); }
    private boolean isNether(String dim) { return "nether".equals(dim); }

    private boolean isSameDimension(String current, TargetDimension target) {
        return switch (target) {
            case OVERWORLD -> isOverworld(current);
            case NETHER -> isNether(current);
            case END -> "end".equals(current);
        };
    }

    private double horizontalDist(double x1, double z1, double x2, double z2) {
        return Math.sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1));
    }

    private float wrapDegrees(float degrees) {
        degrees = degrees % 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    private void logProgress() {
        if (currentTarget == null || mc.player == null) return;
        double dist = horizontalDist(mc.player.getX(), mc.player.getZ(),
                currentTarget.getX(), currentTarget.getZ());
        String mode = usingElytra
                ? "Elytra"
                : Lang.t("Walking", "Marche");
        String dim = getCurrentDimension();

        ChatUtils.print(String.format("[AutoTravel] %s | %s | " +
                        Lang.t("Distance: %.0f | Dim: %s", "Distance : %.0f | Dim : %s"),
                travelState, mode, dist, dim));
    }

    // Public getters for HUD integration
    public TravelState getTravelState() { return travelState; }
    public boolean isUsingElytra() { return usingElytra; }
    public boolean isUsingNetherRoute() { return usingNetherRoute; }
    public ElytraBot getElytraBot() { return elytraBot; }
}
