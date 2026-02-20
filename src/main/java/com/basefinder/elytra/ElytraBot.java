package com.basefinder.elytra;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import com.basefinder.terrain.TerrainPredictor;
import com.basefinder.util.BaritoneController;
import com.basefinder.util.LagDetector;
import com.basefinder.util.Lang;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.client.api.utils.InventoryUtils;

import java.util.Random;

/**
 * Automated elytra flight controller.
 * Handles takeoff, cruise altitude, firework boosting, elytra durability auto-swap, and landing.
 */
public class ElytraBot {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ElytraBot");
    private final Minecraft mc = Minecraft.getInstance();

    // Flight parameters
    private double cruiseAltitude = 200.0;
    private double minAltitude = 100.0;
    private double maxSpeed = 2.5;
    private int fireworkCooldown = 0;
    private int fireworkInterval = 40; // ticks between firework uses
    private float targetYaw = 0;
    private float targetPitch = -2.0f; // slight nose-up for cruise
    private boolean isFlying = false;
    private BlockPos destination = null;

    // Elytra durability
    private int minElytraDurability = 10; // swap when remaining durability <= this
    private int durabilityCheckInterval = 20; // check every second

    // Elytra swap state machine (3-tick process: pickup → equip → putdown)
    private int elytraSwapStep = 0; // 0=none, 1=pickup, 2=equip, 3=putdown
    private int elytraSwapSlot = -1;

    // Firework monitoring
    private int lastFireworkCount = -1;
    private boolean lowFireworkWarned = false;

    // Lag detection - 2b2t chunk loading safety
    private LagDetector lagDetector;
    private boolean unloadedChunksAhead = false;
    private double safeAltitudeBoost = 0; // Extra altitude when chunks not loaded

    // Obstacle avoidance
    private boolean useObstacleAvoidance = true;
    private boolean emergencyPullUp = false;
    private int pullUpTimer = 0;
    private static final int LOOK_AHEAD_DISTANCE = 40; // blocks ahead to check
    private static final int EMERGENCY_HEIGHT_CHECK = 15; // blocks below to check ground proximity
    private static final int PULL_UP_DURATION = 30; // ticks of emergency climb

    // Anti-kick / anti-detection noise
    private boolean useFlightNoise = true;
    private final Random noiseRandom = new Random();
    private float yawNoise = 0;
    private float pitchNoise = 0;
    private double altitudeNoise = 0;
    private int noiseChangeInterval = 40; // change noise direction every 2 seconds
    private int noiseTimer = 0;

    // State
    private FlightState state = FlightState.IDLE;
    private int takeoffTimer = 0;
    private int stuckTimer = 0;
    private Vec3 lastPosition = null;

    // Takeoff state machine
    // -1=PRE_ROTATE, 0=JUMP, 1=WAIT_APEX, 2=DEPLOY, 3=BOOST
    private int takeoffPhase = -1;     // start with PRE_ROTATE
    private int takeoffAttempts = 0;   // max 5 retries
    private int takeoffAirTicks = 0;   // ticks spent in air during takeoff
    private boolean isTakingOff = false; // flag for faster rotation rate during takeoff

    // Landing state
    private int landingTimer = 0;

    // Safe approach fields - controlled descent for base photography
    private BlockPos approachTarget = null;
    private double approachTargetAltitude = 80.0;

    // Circling state (orbit when chunks not loaded)
    private boolean enableCircling = true;
    private BlockPos circleCenter = null;
    private double circleAngle = 0;
    private int circleTicks = 0;
    private double circleRadius = 300;
    private int circleTimeout = 600; // 30 seconds max
    private FlightState stateBeforeCircling = FlightState.CRUISING;

    // Baritone landing delegation
    private boolean useBaritoneLanding = true;
    private int acceptedFallDamage = 3; // half-hearts
    private BaritoneController baritoneController = null;
    private int baritoneLandingTimer = 0;
    private static final int BARITONE_LANDING_TIMEOUT = 60; // 3 seconds

    // Terrain prediction
    private TerrainPredictor terrainPredictor = null;
    private int terrainSafetyMargin = 40;

    public enum FlightState {
        IDLE,
        TAKING_OFF,
        CLIMBING,
        CRUISING,
        DESCENDING,
        SAFE_DESCENDING, // Controlled descent for base approach - prevents fall damage
        FLARING, // Braking flare before landing - pitch up to bleed speed
        LANDING,
        REFUELING, // Looking for fireworks in inventory
        CIRCLING, // Orbiting when chunks aren't loaded (2b2t lag)
        BARITONE_LANDING // Delegating landing to Baritone for reliability
    }

    private int tickCounter = 0;

    /**
     * Called every tick to manage elytra flight.
     */
    public void tick() {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        if (fireworkCooldown > 0) fireworkCooldown--;

        // Update anti-kick flight noise
        if (useFlightNoise) {
            updateFlightNoise();
        }

        // Obstacle avoidance - check terrain ahead
        if (useObstacleAvoidance && isFlying && mc.player.isFallFlying()) {
            checkObstacles();
        }

        // Handle emergency pull-up (overrides normal flight)
        if (emergencyPullUp) {
            handleEmergencyPullUp();
        }

        // Process pending firework use (delayed slot sync)
        if (pendingFireworkSlot >= 0) {
            useFireworkIfNeeded();
        }

        // Process elytra swap steps (1 step per tick for server sync)
        if (elytraSwapStep > 0) {
            processElytraSwap();
            return; // Don't do anything else during swap
        }

        // Detect if stuck
        Vec3 currentPos = mc.player.position();
        if (lastPosition != null && currentPos.distanceTo(lastPosition) < 0.01 && state != FlightState.IDLE) {
            stuckTimer++;
        } else {
            stuckTimer = 0;
        }
        lastPosition = currentPos;

        // Debug logging every 2 seconds
        if (tickCounter % 40 == 0 && state != FlightState.IDLE) {
            LOGGER.info("[ElytraBot] State: {}, isFallFlying: {}, onGround: {}, Y: {}, Dest: {}",
                state, mc.player.isFallFlying(), mc.player.onGround(),
                (int) mc.player.getY(), destination != null ? destination.toShortString() : "none");
        }

        // Check elytra durability periodically during flight
        if (tickCounter % durabilityCheckInterval == 0 && isFlying && state != FlightState.LANDING && state != FlightState.IDLE) {
            checkElytraDurability();
        }

        switch (state) {
            case IDLE -> {}
            case TAKING_OFF -> handleTakeoff();
            case CLIMBING -> handleClimbing();
            case CRUISING -> handleCruising();
            case DESCENDING -> handleDescending();
            case SAFE_DESCENDING -> handleSafeDescent();
            case FLARING -> handleFlaring();
            case LANDING -> handleLanding();
            case REFUELING -> handleRefueling();
            case CIRCLING -> handleCircling();
            case BARITONE_LANDING -> handleBaritoneLanding();
        }
    }

    /**
     * Start flying towards a destination.
     */
    public void startFlight(BlockPos target) {
        if (mc.player == null) return;

        this.destination = target;
        this.isFlying = true;
        this.targetYaw = calculateYawToTarget(target);

        // Check if already flying with elytra
        if (mc.player.isFallFlying()) {
            state = FlightState.CRUISING;
        } else {
            // Check if wearing elytra
            ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA)) {
                state = FlightState.TAKING_OFF;
                takeoffTimer = 0;
                takeoffPhase = -1; // start with PRE_ROTATE
                isTakingOff = true;
            }
        }
    }

    public void stop() {
        isFlying = false;
        state = FlightState.IDLE;
        destination = null;
        elytraSwapStep = 0;
        elytraSwapSlot = -1;
        lastFireworkCount = -1;
        lowFireworkWarned = false;
        isTakingOff = false;
        takeoffPhase = -1;
        // Reset circling state
        circleCenter = null;
        circleTicks = 0;
        circleAngle = 0;
        // Cancel Baritone landing if active
        if (baritoneController != null) {
            baritoneController.cancelLanding();
        }
        baritoneLandingTimer = 0;
    }

    // ===== ELYTRA DURABILITY MANAGEMENT =====

    /**
     * Checks the equipped elytra's durability and initiates a swap if needed.
     */
    private void checkElytraDurability() {
        if (mc.player == null) return;

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);

        // Not wearing elytra at all - try to equip one
        if (!chest.is(Items.ELYTRA)) {
            int spareSlot = findElytraInInventory();
            if (spareSlot >= 0) {
                ChatUtils.print("[ElytraBot] " + Lang.t("No elytra equipped! Equipping from inventory...", "Pas d'elytra équipé ! Équipement depuis l'inventaire..."));
                startElytraSwap(spareSlot);
            } else {
                ChatUtils.print("[ElytraBot] " + Lang.t("No elytra available! Emergency landing...", "Aucun elytra disponible ! Atterrissage d'urgence..."));
                initiateEmergencyLanding();
            }
            return;
        }

        // Check remaining durability
        int remaining = chest.getMaxDamage() - chest.getDamageValue();
        LOGGER.info("[ElytraBot] Elytra durability: {}/{}", remaining, chest.getMaxDamage());

        if (remaining <= minElytraDurability) {
            // Low durability - try to swap
            int spareSlot = findElytraInInventory();
            if (spareSlot >= 0) {
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra low (" + remaining + " durability)! Swapping...", "Elytra usé (" + remaining + " durabilité) ! Échange..."));
                startElytraSwap(spareSlot);
            } else {
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra low (" + remaining + ") and no spare! Landing...", "Elytra usé (" + remaining + ") et aucun de rechange ! Atterrissage..."));
                initiateEmergencyLanding();
            }
        }
    }

    /**
     * Find a usable elytra in the player's inventory (not equipped).
     * Returns the container slot index, or -1 if none found.
     */
    private int findElytraInInventory() {
        if (mc.player == null) return -1;

        // Search main inventory (slots 9-35) and hotbar (slots 36-44)
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.ELYTRA)) {
                int durability = stack.getMaxDamage() - stack.getDamageValue();
                if (durability > minElytraDurability) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Count how many usable elytra are in the inventory (including equipped).
     */
    public int getElytraCount() {
        if (mc.player == null) return 0;

        int count = 0;
        // Check equipped
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA) && (chest.getMaxDamage() - chest.getDamageValue()) > minElytraDurability) {
            count++;
        }
        // Check inventory
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamageValue()) > minElytraDurability) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the durability of the currently equipped elytra.
     * Returns -1 if not wearing elytra.
     */
    public int getEquippedElytraDurability() {
        if (mc.player == null) return -1;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return -1;
        return chest.getMaxDamage() - chest.getDamageValue();
    }

    /**
     * Start the 3-tick elytra swap process.
     */
    private void startElytraSwap(int inventorySlot) {
        elytraSwapSlot = inventorySlot;
        elytraSwapStep = 1;
        LOGGER.info("[ElytraBot] Starting elytra swap from slot {}", inventorySlot);
    }

    /**
     * Process one step of the elytra swap per tick.
     * Step 1: Pick up new elytra from inventory slot
     * Step 2: Click chest armor slot to swap
     * Step 3: Put old elytra in original slot
     */
    private void processElytraSwap() {
        if (mc.player == null || mc.gameMode == null || elytraSwapSlot < 0) {
            elytraSwapStep = 0;
            elytraSwapSlot = -1;
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;

        switch (elytraSwapStep) {
            case 1 -> {
                // Pick up new elytra from inventory
                mc.gameMode.handleInventoryMouseClick(containerId, elytraSwapSlot, 0, ClickType.PICKUP, mc.player);
                elytraSwapStep = 2;
                LOGGER.info("[ElytraBot] Swap step 1: picked up elytra from slot {}", elytraSwapSlot);
            }
            case 2 -> {
                // Click chest slot to swap (slot 6 = chestplate in inventoryMenu)
                mc.gameMode.handleInventoryMouseClick(containerId, 6, 0, ClickType.PICKUP, mc.player);
                elytraSwapStep = 3;
                LOGGER.info("[ElytraBot] Swap step 2: clicked chest slot");
            }
            case 3 -> {
                // Put old elytra in original inventory slot
                mc.gameMode.handleInventoryMouseClick(containerId, elytraSwapSlot, 0, ClickType.PICKUP, mc.player);
                elytraSwapStep = 0;
                elytraSwapSlot = -1;
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra swapped! Durability: ", "Elytra échangé ! Durabilité : ") + getEquippedElytraDurability());
                LOGGER.info("[ElytraBot] Swap step 3: completed swap");
            }
            default -> {
                elytraSwapStep = 0;
                elytraSwapSlot = -1;
            }
        }
    }

    /**
     * Initiate safe emergency landing when no elytra is available.
     */
    private void initiateEmergencyLanding() {
        state = FlightState.DESCENDING;
        // The descending handler will transition to landing
    }

    // ===== OBSTACLE AVOIDANCE =====

    /**
     * Check for terrain ahead and below. If obstacles are detected, trigger emergency pull-up.
     * Checks: forward raycast (velocity direction) and ground proximity.
     */
    private void checkObstacles() {
        if (mc.player == null || mc.level == null) return;

        Vec3 pos = mc.player.position();
        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (hSpeed < 0.1) return; // Not moving fast enough to worry

        // Normalize horizontal velocity for look-ahead direction
        double nx = velocity.x / hSpeed;
        double nz = velocity.z / hSpeed;

        // Forward raycast - check blocks ahead at current altitude and slightly below
        for (int dist = 5; dist <= LOOK_AHEAD_DISTANCE; dist += 5) {
            double checkX = pos.x + nx * dist;
            double checkZ = pos.z + nz * dist;

            // Check at current Y and Y-3 (below eye level)
            for (int dy = -3; dy <= 2; dy++) {
                BlockPos checkPos = BlockPos.containing(checkX, pos.y + dy, checkZ);
                BlockState blockState = mc.level.getBlockState(checkPos);
                if (!blockState.isAir() && !blockState.liquid()) {
                    // Obstacle ahead!
                    if (!emergencyPullUp) {
                        LOGGER.warn("[ElytraBot] Obstacle detected {} blocks ahead at Y={}", dist, checkPos.getY());
                        emergencyPullUp = true;
                        pullUpTimer = 0;
                    }
                    return;
                }
            }
        }

        // Terrain prediction: check further ahead (200 blocks) with seed data
        if (terrainPredictor != null && !emergencyPullUp) {
            int predictedMax = terrainPredictor.getMaxHeightAhead(pos, velocity, 200);
            if (predictedMax > 0 && pos.y < predictedMax + 20) {
                LOGGER.warn("[ElytraBot] Terrain prediction: high terrain ({}) ahead, current Y={}", predictedMax, (int) pos.y);
                emergencyPullUp = true;
                pullUpTimer = 0;
                return;
            }
        }

        // Ground proximity check - check blocks directly below
        boolean groundClose = false;
        for (int dy = 1; dy <= EMERGENCY_HEIGHT_CHECK; dy++) {
            BlockPos below = BlockPos.containing(pos.x, pos.y - dy, pos.z);
            if (!mc.level.getBlockState(below).isAir()) {
                if (dy <= 8 && state == FlightState.CRUISING) {
                    // Too close to ground while cruising
                    groundClose = true;
                }
                break;
            }
        }

        if (groundClose && !emergencyPullUp) {
            LOGGER.warn("[ElytraBot] Ground proximity warning - pulling up");
            emergencyPullUp = true;
            pullUpTimer = 0;
        }
    }

    /**
     * Handle emergency pull-up: pitch up sharply and use firework to gain altitude fast.
     */
    private void handleEmergencyPullUp() {
        if (mc.player == null) return;

        pullUpTimer++;
        targetPitch = -60.0f; // Steep climb
        applyRotation();
        useFireworkIfNeeded();

        if (pullUpTimer >= PULL_UP_DURATION) {
            emergencyPullUp = false;
            pullUpTimer = 0;
            LOGGER.info("[ElytraBot] Emergency pull-up complete, Y={}", (int) mc.player.getY());
        }
    }

    // ===== UTILITY =====

    /**
     * Raycast straight down to find distance to ground.
     * Returns distance in blocks (max 320). Ignores air and liquids.
     */
    private double getGroundDistance() {
        if (mc.player == null || mc.level == null) return 320;
        BlockPos pos = mc.player.blockPosition();
        for (int dy = 0; dy <= 320; dy++) {
            BlockPos check = pos.below(dy);
            BlockState blockState = mc.level.getBlockState(check);
            if (!blockState.isAir() && !blockState.liquid()) {
                return dy;
            }
        }
        return 320;
    }

    // ===== FLIGHT STATE HANDLERS =====

    private void handleTakeoff() {
        if (mc.player == null) return;

        takeoffTimer++;
        isTakingOff = true;

        // Already flying? Transition to boost phase
        if (mc.player.isFallFlying() && takeoffPhase < 3) {
            takeoffPhase = 3; // skip to BOOST
        }

        switch (takeoffPhase) {
            case -1 -> { // PRE_ROTATE - orient player nose-up BEFORE jumping
                targetPitch = -45.0f;
                applyRotation();
                // Wait until pitch is sufficiently upward before jumping
                if (mc.player.getXRot() < -30.0f) {
                    LOGGER.info("[ElytraBot] Pre-rotation complete (pitch={}), jumping", String.format("%.1f", mc.player.getXRot()));
                    takeoffPhase = 0;
                }
                // Safety: if pre-rotation takes too long (>40 ticks / 2s), jump anyway
                if (takeoffTimer > 40 && takeoffPhase == -1) {
                    LOGGER.warn("[ElytraBot] Pre-rotation timeout, forcing jump (pitch={})", String.format("%.1f", mc.player.getXRot()));
                    takeoffPhase = 0;
                }
            }
            case 0 -> { // JUMP - jump while maintaining upward pitch
                targetPitch = -45.0f;
                applyRotation();
                if (mc.player.onGround()) {
                    mc.player.jumpFromGround();
                    takeoffAirTicks = 0;
                    takeoffPhase = 1;
                }
            }
            case 1 -> { // WAIT_APEX - wait for near-apex of jump, keep nose up
                targetPitch = -45.0f;
                applyRotation();
                if (!mc.player.onGround()) {
                    takeoffAirTicks++;
                    // Wait until velocity is near zero (apex) or enough time has passed
                    if (mc.player.getDeltaMovement().y <= 0.1 || takeoffAirTicks > 7) {
                        takeoffPhase = 2;
                        takeoffAirTicks = 0; // reuse as deploy tick counter
                    }
                } else {
                    // Fell back to ground before reaching apex - retry from PRE_ROTATE
                    takeoffPhase = -1;
                }
            }
            case 2 -> { // DEPLOY - spam elytra packet until it works, maintain pitch
                targetPitch = -45.0f;
                applyRotation();
                takeoffAirTicks++;
                if (mc.player.isFallFlying()) {
                    takeoffPhase = 3;
                    break;
                }
                // Send deploy packet every tick
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(
                            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                    ));
                }
                // Give up after 5 ticks or if back on ground
                if (takeoffAirTicks > 5 || mc.player.onGround()) {
                    takeoffAttempts++;
                    if (takeoffAttempts >= 5) {
                        LOGGER.error("[ElytraBot] Failed to deploy elytra after {} attempts", takeoffAttempts);
                        ChatUtils.print("[ElytraBot] " + Lang.t("Takeoff failed after 5 attempts!", "Décollage échoué après 5 tentatives !"));
                        // Stay in TAKING_OFF - the existing timeout will handle it
                    } else {
                        LOGGER.info("[ElytraBot] Deploy failed, retrying (attempt {})", takeoffAttempts + 1);
                        takeoffPhase = -1; // back to PRE_ROTATE for clean retry
                    }
                }
            }
            case 3 -> { // BOOST - fire a rocket and climb
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra deployed! Climbing...", "Elytra déployé ! Montée..."));
                fireworkCooldown = 0; // force immediate boost
                targetPitch = -45.0f;
                applyRotation();
                useFireworkIfNeeded();
                // Reset takeoff state and transition to CLIMBING
                isTakingOff = false;
                takeoffPhase = -1;
                takeoffAttempts = 0;
                takeoffAirTicks = 0;
                takeoffTimer = 0;
                state = FlightState.CLIMBING;
            }
        }

        // Global timeout - reset everything after 200 ticks (10 seconds)
        if (takeoffTimer > 200) {
            LOGGER.warn("[ElytraBot] Takeoff global timeout, resetting");
            isTakingOff = false;
            takeoffPhase = -1;
            takeoffAttempts = 0;
            takeoffAirTicks = 0;
            takeoffTimer = 0;
        }
    }

    private void handleClimbing() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            LOGGER.info("[ElytraBot] Lost flight during climbing, going back to takeoff");
            state = FlightState.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1; // restart with PRE_ROTATE
            isTakingOff = true;
            return;
        }

        // Pitch up to gain altitude - reduce angle as we approach cruise to avoid overshooting
        double altDiff = cruiseAltitude - mc.player.getY();
        if (altDiff > 50) {
            targetPitch = -45.0f; // steep climb when far below
            useFireworkIfNeeded();
        } else if (altDiff > 20) {
            targetPitch = -25.0f; // moderate climb
            useFireworkIfNeeded();
        } else if (altDiff > 5) {
            targetPitch = -10.0f; // gentle climb, no fireworks to limit momentum
        } else {
            targetPitch = -2.0f; // almost there, just glide up
        }
        applyRotation();

        if (mc.player.getY() >= cruiseAltitude - 3) {
            LOGGER.info("[ElytraBot] Reached cruise altitude {}, switching to cruise mode", cruiseAltitude);
            ChatUtils.print("[ElytraBot] " + Lang.t("Cruising at altitude ", "Croisière à altitude ") + (int)cruiseAltitude);
            state = FlightState.CRUISING;
        }
    }

    private void handleCruising() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            state = FlightState.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            return;
        }

        // Update yaw towards destination
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }

        // 2b2t lag safety: check if chunks ahead are loaded
        updateChunkLoadingSafety();

        // Check if we should enter circling mode (chunks not loaded)
        if (enableCircling && shouldEnterCircling()) {
            enterCircling();
            return;
        }

        // Calculate effective cruise altitude (terrain-aware)
        double effectiveAltitude = cruiseAltitude;
        if (terrainPredictor != null) {
            Vec3 pos = mc.player.position();
            Vec3 velocity = mc.player.getDeltaMovement();
            int maxTerrainAhead = terrainPredictor.getMaxHeightAhead(pos, velocity, 500);
            effectiveAltitude = Math.max(cruiseAltitude, maxTerrainAhead + terrainSafetyMargin);
        }

        // Maintain altitude - HARD CAP at effectiveAltitude
        double y = mc.player.getY();
        if (y > effectiveAltitude) {
            // Above cruise altitude - nose down to correct
            targetPitch = 15.0f;
        } else if (y < effectiveAltitude - 15) {
            // Far below - aggressive climb
            targetPitch = -25.0f;
            useFireworkIfNeeded();
        } else if (y < effectiveAltitude - 5) {
            // Slightly below - gentle climb
            targetPitch = -8.0f;
            useFireworkIfNeeded();
        } else {
            // Within 5 blocks of target - cruise level
            targetPitch = -2.0f;
            // Periodic firework to maintain speed
            Vec3 velocity = mc.player.getDeltaMovement();
            double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
            if (horizontalSpeed < 1.0) {
                useFireworkIfNeeded();
            }
        }

        applyRotation();

        // Check if near destination
        if (destination != null) {
            double distXZ = Math.sqrt(
                    Math.pow(mc.player.getX() - destination.getX(), 2) +
                    Math.pow(mc.player.getZ() - destination.getZ(), 2)
            );
            if (distXZ < 300) {
                state = FlightState.DESCENDING;
            }
        }

        // Monitor firework supply
        checkFireworkSupply();
    }

    private void handleDescending() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            state = FlightState.LANDING;
            landingTimer = 0;
            return;
        }

        // Aim towards destination
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }

        // Adaptive descent based on ground distance and speed
        double groundDist = getGroundDistance();
        Vec3 vel = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);

        // Speed control: if too fast, pitch up slightly to brake
        if (hSpeed > 1.2) {
            targetPitch = -5.0f; // brake
        } else if (groundDist > 100) {
            targetPitch = 8.0f;  // gentle descent, we're high
        } else if (groundDist > 70) {
            targetPitch = 5.0f;  // moderate
        } else if (groundDist >= 50) {
            targetPitch = 3.0f;  // gentle
        } else {
            // Start flaring earlier at 50 blocks
            LOGGER.info("[ElytraBot] Ground at {} blocks, starting flare", (int) groundDist);
            state = FlightState.FLARING;
            return;
        }

        applyRotation();

        if (mc.player.onGround()) {
            state = FlightState.LANDING;
            landingTimer = 0;
        }
    }

    // ===== SAFE DESCENT (BASE APPROACH) =====

    /**
     * Start a controlled descent to a target altitude while maintaining elytra flight.
     * Used when approaching a detected base for screenshot - prevents the fall damage
     * that occurred when elytra was stopped abruptly at cruise altitude.
     */
    public void startSafeDescent(BlockPos target, double targetAltitude) {
        this.approachTarget = target;
        this.approachTargetAltitude = targetAltitude;
        this.destination = target;
        this.state = FlightState.SAFE_DESCENDING;
        this.isFlying = true;
        LOGGER.info("[ElytraBot] Starting safe descent to altitude {} for base at {}", (int) targetAltitude, target.toShortString());
    }

    /**
     * Handle controlled descent: gently lower altitude while maintaining elytra flight.
     * Never stops elytra mid-air. Maintains enough speed to keep flying.
     */
    private void handleSafeDescent() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            // Lost elytra flight - try to restart to avoid freefall
            state = FlightState.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            LOGGER.warn("[ElytraBot] Lost flight during safe descent! Restarting...");
            return;
        }

        // Aim towards the approach target
        if (approachTarget != null) {
            targetYaw = calculateYawToTarget(approachTarget);
        }

        double currentY = mc.player.getY();

        // Controlled descent in stages
        if (currentY > approachTargetAltitude + 40) {
            targetPitch = 12.0f; // moderate descent when far above
        } else if (currentY > approachTargetAltitude + 15) {
            targetPitch = 6.0f; // gentle descent getting closer
        } else if (currentY > approachTargetAltitude + 5) {
            targetPitch = 2.0f; // very gentle, almost level
        } else if (currentY >= approachTargetAltitude - 5) {
            targetPitch = -2.0f; // level flight at target altitude
            // Use firework to maintain altitude if sinking too fast
            Vec3 velocity = mc.player.getDeltaMovement();
            if (velocity.y < -0.15) {
                useFireworkIfNeeded();
            }
        } else {
            // Below target - climb back up gently
            targetPitch = -12.0f;
            useFireworkIfNeeded();
        }

        applyRotation();

        // Maintain minimum forward speed to keep elytra active
        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (hSpeed < 0.5) {
            useFireworkIfNeeded();
        }
    }

    /**
     * Check if the player has reached the safe approach altitude (within tolerance).
     */
    public boolean isAtApproachAltitude() {
        if (mc.player == null) return false;
        return Math.abs(mc.player.getY() - approachTargetAltitude) < 10;
    }

    public boolean isSafeDescending() {
        return state == FlightState.SAFE_DESCENDING;
    }

    /**
     * Flare maneuver: pitch up sharply to convert horizontal speed into altitude,
     * bleeding speed before landing. Transitions to LANDING when slow enough.
     */
    private void handleFlaring() {
        if (mc.player == null) return;

        double groundDist = getGroundDistance();

        // Lost elytra mid-flare → go straight to landing
        if (!mc.player.isFallFlying()) {
            LOGGER.info("[ElytraBot] Lost flight during flare, switching to landing");
            state = FlightState.LANDING;
            landingTimer = 0;
            return;
        }

        // Progressive braking based on speed and distance
        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        if (hSpeed > 1.0) {
            targetPitch = -25.0f; // initial braking
        } else if (hSpeed > 0.5) {
            targetPitch = -35.0f; // stronger braking
        } else {
            targetPitch = -45.0f; // stall maximal
        }

        // Proactive firework braking: falling too fast close to ground
        if (velocity.y < -0.3 && groundDist < 25) {
            targetPitch = -60.0f;
            fireworkCooldown = 0;
            useFireworkIfNeeded();
        }

        // Cushion firework: very close to ground, any downward velocity
        if (groundDist < 8 && velocity.y < -0.15) {
            targetPitch = -80.0f;
            fireworkCooldown = 0;
            useFireworkIfNeeded();
        }

        // Keep yaw towards destination
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }
        applyRotation();

        // Stricter landing transition: slow, close, and not falling fast
        if (hSpeed < 0.3 && groundDist < 6 && velocity.y > -0.2) {
            LOGGER.info("[ElytraBot] Flare complete, hSpeed={}, groundDist={}, vy={}, landing",
                    String.format("%.2f", hSpeed), (int) groundDist, String.format("%.2f", velocity.y));
            state = FlightState.LANDING;
            landingTimer = 0;
            return;
        }

        // Over-climbed: gentle descent, don't re-enter DESCENDING
        if (groundDist > 50) {
            targetPitch = -5.0f;
        }

        if (mc.player.onGround()) {
            state = FlightState.IDLE;
            isFlying = false;
            ChatUtils.print("[ElytraBot] " + Lang.t("Landed.", "Atterri."));
        }
    }

    private void handleLanding() {
        if (mc.player == null) return;

        landingTimer++;

        // Still flying with elytra → aggressive nose up to slow down
        if (mc.player.isFallFlying()) {
            targetPitch = -40.0f;
            applyRotation();

            double groundDist = getGroundDistance();
            Vec3 vel = mc.player.getDeltaMovement();

            // Cushion firework: any downward velocity close to ground
            if (vel.y < -0.15 && groundDist < 10) {
                LOGGER.warn("[ElytraBot] Cushion firework! vy={}, ground={}", String.format("%.2f", vel.y), (int) groundDist);
                targetPitch = -80.0f;
                applyRotation();
                fireworkCooldown = 0;
                useFireworkIfNeeded();
            }

            // Emergency: very close to ground, fire no matter what
            if (groundDist < 5 && vel.y < -0.1) {
                LOGGER.warn("[ElytraBot] Emergency landing firework! vy={}, ground={}", String.format("%.2f", vel.y), (int) groundDist);
                fireworkCooldown = 0;
                useFireworkIfNeeded();
            }
        }

        // Only set IDLE when actually on the ground
        if (mc.player.onGround()) {
            state = FlightState.IDLE;
            isFlying = false;
            landingTimer = 0;
            ChatUtils.print("[ElytraBot] " + Lang.t("Landed.", "Atterri."));
            return;
        }

        // Safety timeout — if still airborne after 100 ticks, go back to FLARING (not IDLE)
        if (landingTimer > 100) {
            if (mc.player.isFallFlying()) {
                LOGGER.warn("[ElytraBot] Landing timeout but still airborne, returning to FLARING");
                state = FlightState.FLARING;
                landingTimer = 0;
            } else {
                // Not elytra-flying and not on ground (e.g. falling) — just wait for ground contact
                LOGGER.warn("[ElytraBot] Landing timeout, waiting for ground contact");
                landingTimer = 50; // reset partially to avoid spamming, keep checking
            }
        }
    }

    private void handleRefueling() {
        // Try to find fireworks anywhere in inventory (hotbar + main)
        boolean hasInHotbar = findFireworkInHotbar() >= 0;
        boolean hasInInventory = findFireworkInInventory() >= 0;
        if (hasInHotbar || hasInInventory) {
            state = FlightState.CRUISING;
            lowFireworkWarned = false;
        } else {
            // No fireworks - gentle glide descent
            targetPitch = -3.0f; // very gentle descent to maximize glide distance
            if (destination != null) {
                targetYaw = calculateYawToTarget(destination);
            }
            applyRotation();
            if (mc.player != null && mc.player.getY() < minAltitude) {
                ChatUtils.print("[ElytraBot] " + Lang.t("No fireworks! Landing...", "Plus de fusées ! Atterrissage..."));
                state = FlightState.LANDING;
            }
        }
    }

    // ===== CIRCLING (CHUNK WAIT) =====

    /**
     * Check if we should enter circling mode.
     * Triggers when: many unloaded chunks ahead OR severe lag.
     */
    private boolean shouldEnterCircling() {
        if (lagDetector == null) return false;
        int unloaded = lagDetector.getUnloadedChunksAhead();
        return unloaded >= 3 || lagDetector.isSeverelyLagging();
    }

    /**
     * Enter circling mode: save state and begin orbiting.
     */
    private void enterCircling() {
        if (mc.player == null) return;
        stateBeforeCircling = state;
        circleCenter = mc.player.blockPosition();
        circleAngle = 0;
        circleTicks = 0;
        state = FlightState.CIRCLING;
        LOGGER.info("[ElytraBot] Entering CIRCLING mode at {}", circleCenter.toShortString());
        ChatUtils.print("[ElytraBot] " + Lang.t("Chunks not loaded - circling...", "Chunks non chargés - orbite d'attente..."));
    }

    /**
     * Handle circling: orbit around a point while waiting for chunks to load.
     * Scanning continues during circling (productive waiting).
     */
    private void handleCircling() {
        if (mc.player == null || circleCenter == null) {
            exitCircling();
            return;
        }

        if (!mc.player.isFallFlying()) {
            state = FlightState.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            return;
        }

        circleTicks++;

        // Calculate orbital speed (maintain current horizontal speed)
        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (hSpeed < 0.1) hSpeed = 1.0;

        // Angular velocity: complete circle based on radius and speed
        double circumference = 2.0 * Math.PI * circleRadius;
        double ticksPerCircle = circumference / (hSpeed * 20.0); // Convert blocks/tick to blocks/sec
        if (ticksPerCircle < 100) ticksPerCircle = 100; // Minimum 5 seconds per circle
        circleAngle += 2.0 * Math.PI / ticksPerCircle;

        // Calculate target point on the circle
        double targetX = circleCenter.getX() + Math.cos(circleAngle) * circleRadius;
        double targetZ = circleCenter.getZ() + Math.sin(circleAngle) * circleRadius;

        // Aim towards the orbit point
        targetYaw = calculateYawToTarget(BlockPos.containing(targetX, mc.player.getY(), targetZ));

        // Maintain cruise altitude
        double y = mc.player.getY();
        if (y < cruiseAltitude - 10) {
            targetPitch = -15.0f;
            useFireworkIfNeeded();
        } else if (y < cruiseAltitude - 3) {
            targetPitch = -5.0f;
            useFireworkIfNeeded();
        } else {
            targetPitch = -2.0f;
            if (hSpeed < 0.8) {
                useFireworkIfNeeded();
            }
        }

        applyRotation();

        // Check exit conditions
        if (lagDetector != null && lagDetector.isFlightPathLoaded() && lagDetector.areChunksStabilized() && !lagDetector.isSeverelyLagging()) {
            LOGGER.info("[ElytraBot] Chunks loaded and stable - resuming flight");
            ChatUtils.print("[ElytraBot] " + Lang.t("Chunks loaded - resuming!", "Chunks chargés - reprise !"));
            exitCircling();
            return;
        }

        // Timeout
        if (circleTicks >= circleTimeout) {
            LOGGER.warn("[ElytraBot] Circling timeout ({} ticks) - forcing resume with altitude boost", circleTimeout);
            ChatUtils.print("[ElytraBot] " + Lang.t("Circling timeout - resuming with safety altitude", "Timeout orbite - reprise avec altitude de sécurité"));
            exitCirclingWithBoost();
        }
    }

    /**
     * Exit circling and resume normal flight.
     */
    private void exitCircling() {
        circleCenter = null;
        circleTicks = 0;
        circleAngle = 0;
        state = FlightState.CRUISING;
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }
    }

    /**
     * Exit circling with an altitude boost for safety (chunks may still be partially unloaded).
     */
    private void exitCirclingWithBoost() {
        circleCenter = null;
        circleTicks = 0;
        circleAngle = 0;
        safeAltitudeBoost = 20; // Extra 20 blocks altitude
        state = FlightState.CRUISING;
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }
    }

    // ===== BARITONE LANDING =====

    /**
     * Start landing via Baritone - finds the ground position and delegates.
     */
    private void startBaritoneLanding() {
        if (mc.player == null || baritoneController == null) return;

        BlockPos groundPos;
        if (destination != null) {
            // Find ground below destination
            groundPos = findGroundBelow(destination);
        } else {
            groundPos = findGroundBelow(mc.player.blockPosition());
        }

        baritoneController.setAcceptDamage(acceptedFallDamage);
        baritoneController.configureForFastLanding();
        baritoneController.landAt(groundPos);
        baritoneLandingTimer = 0;
        state = FlightState.BARITONE_LANDING;
        LOGGER.info("[ElytraBot] Delegating landing to Baritone at {}", groundPos.toShortString());
        ChatUtils.print("[ElytraBot] " + Lang.t("Baritone landing at ", "Atterrissage Baritone à ") + groundPos.toShortString());
    }

    /**
     * Handle Baritone landing: monitor progress, fallback on timeout/error.
     */
    private void handleBaritoneLanding() {
        if (mc.player == null) return;

        baritoneLandingTimer++;

        // Check if Baritone completed the landing
        if (baritoneController != null && baritoneController.isLandingComplete()) {
            LOGGER.info("[ElytraBot] Baritone landing complete!");
            ChatUtils.print("[ElytraBot] " + Lang.t("Landed via Baritone.", "Atterri via Baritone."));
            state = FlightState.IDLE;
            isFlying = false;
            baritoneLandingTimer = 0;
            return;
        }

        // Check if player is on ground (Baritone may have finished without signal)
        if (mc.player.onGround() && !mc.player.isFallFlying()) {
            LOGGER.info("[ElytraBot] Player on ground during Baritone landing - assuming success");
            if (baritoneController != null) baritoneController.cancelLanding();
            state = FlightState.IDLE;
            isFlying = false;
            baritoneLandingTimer = 0;
            return;
        }

        // Timeout → fallback to custom landing
        if (baritoneLandingTimer >= BARITONE_LANDING_TIMEOUT) {
            LOGGER.warn("[ElytraBot] Baritone landing timeout! Falling back to custom landing");
            ChatUtils.print("[ElytraBot] " + Lang.t("Baritone landing timeout - fallback to manual", "Baritone timeout - atterrissage manuel"));
            if (baritoneController != null) baritoneController.cancelLanding();
            baritoneLandingTimer = 0;
            state = FlightState.DESCENDING;
        }
    }

    /**
     * Find the ground position below a given block position.
     */
    private BlockPos findGroundBelow(BlockPos pos) {
        if (mc.level == null) return pos;
        for (int y = pos.getY(); y > mc.level.getMinY(); y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            BlockState blockState = mc.level.getBlockState(check);
            if (!blockState.isAir() && !blockState.liquid()) {
                return check.above();
            }
        }
        return new BlockPos(pos.getX(), 64, pos.getZ());
    }

    /**
     * Monitor firework supply and warn when getting low.
     * Plans ahead: if only a few fireworks left, starts descending early
     * instead of waiting until completely empty at high altitude.
     */
    private void checkFireworkSupply() {
        if (mc.player == null) return;

        int currentCount = getFireworkCount();

        // Track firework usage
        if (lastFireworkCount >= 0 && currentCount < lastFireworkCount) {
            LOGGER.info("[ElytraBot] Firework used: {} remaining", currentCount);
        }
        lastFireworkCount = currentCount;

        // No fireworks at all - enter refueling/landing
        if (currentCount == 0) {
            state = FlightState.REFUELING;
            return;
        }

        // Low firework warning (<=5 left)
        if (currentCount <= 5 && !lowFireworkWarned) {
            lowFireworkWarned = true;
            ChatUtils.print("[ElytraBot] " + Lang.t("Low fireworks! Only " + currentCount + " remaining.", "Fusées basses ! Seulement " + currentCount + " restantes."));
        }

        // Critical: 2 or fewer fireworks - start descending to save them for landing
        if (currentCount <= 2 && mc.player.getY() > minAltitude + 30) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Almost out of fireworks (" + currentCount + ")! Descending...", "Presque plus de fusées (" + currentCount + ") ! Descente..."));
            state = FlightState.DESCENDING;
        }
    }

    // ===== 2B2T LAG SAFETY =====

    /**
     * Check if chunks ahead of our flight path are loaded.
     * On 2b2t, the server lags and chunks may not load fast enough
     * when flying at high speed. If we detect unloaded chunks ahead,
     * we gain extra altitude as a safety margin (terrain we can't see
     * could be mountains or builds).
     */
    private void updateChunkLoadingSafety() {
        if (mc.player == null || mc.level == null) return;

        // Use LagDetector if available
        if (lagDetector != null && !lagDetector.isFlightPathLoaded()) {
            unloadedChunksAhead = true;
            // Gain 3 extra blocks per unloaded chunk (max 10)
            double boost = Math.min(10.0, lagDetector.getUnloadedChunksAhead() * 3.0);
            safeAltitudeBoost = Math.max(safeAltitudeBoost, boost);
            return;
        }

        // Fallback: manual check of chunks in flight direction
        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (hSpeed < 0.5) {
            unloadedChunksAhead = false;
            safeAltitudeBoost = Math.max(0, safeAltitudeBoost - 1); // Decay gradually
            return;
        }

        double nx = velocity.x / hSpeed;
        double nz = velocity.z / hSpeed;
        double px = mc.player.getX();
        double pz = mc.player.getZ();

        int unloaded = 0;
        var chunkSource = mc.level.getChunkSource();

        // Check 3 chunks ahead
        for (int i = 1; i <= 3; i++) {
            double checkX = px + nx * i * 48;
            double checkZ = pz + nz * i * 48;
            int chunkX = (int) Math.floor(checkX) >> 4;
            int chunkZ = (int) Math.floor(checkZ) >> 4;

            LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                unloaded++;
            }
        }

        if (unloaded >= 2) {
            if (!unloadedChunksAhead) {
                LOGGER.warn("[ElytraBot] Unloaded chunks ahead ({})! Gaining altitude for safety", unloaded);
            }
            unloadedChunksAhead = true;
            safeAltitudeBoost = Math.min(10.0, unloaded * 3.0);
        } else {
            unloadedChunksAhead = false;
            safeAltitudeBoost = Math.max(0, safeAltitudeBoost - 1); // Decay gradually
        }
    }

    // ===== FIREWORK MANAGEMENT =====

    /**
     * Use a firework rocket if cooldown allows.
     * Uses a 2-tick delay between slot switch and use to allow server sync.
     *
     * Search order:
     * 1. Hotbar (direct use, no swap needed)
     * 2. Main inventory slots 9-35 (swap to hotbar slot 8 via SWAP click)
     */
    private int pendingFireworkSlot = -1;
    private int pendingFireworkDelay = 0;
    private int previousSlotBeforeFirework = -1;
    private boolean pendingInventorySwap = false; // True if we just did an inventory swap and need to wait

    private void useFireworkIfNeeded() {
        if (fireworkCooldown > 0 || mc.player == null || mc.gameMode == null) return;

        // Handle pending firework use (slot was switched, wait a tick before using)
        if (pendingFireworkSlot >= 0) {
            pendingFireworkDelay--;
            if (pendingFireworkDelay <= 0) {
                mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                mc.player.getInventory().selected = previousSlotBeforeFirework;
                fireworkCooldown = fireworkInterval;
                pendingFireworkSlot = -1;
                pendingInventorySwap = false;
            }
            return;
        }

        // Step 1: Check hotbar for firework rockets
        int hotbarSlot = findFireworkInHotbar();
        if (hotbarSlot >= 0) {
            // Found in hotbar - switch and use
            previousSlotBeforeFirework = mc.player.getInventory().selected;
            mc.player.getInventory().selected = hotbarSlot;
            pendingFireworkSlot = hotbarSlot;
            pendingFireworkDelay = 1; // Wait 1 tick for server to sync
            return;
        }

        // Step 2: Check main inventory (slots 9-35 in inventoryMenu)
        int invSlot = findFireworkInInventory();
        if (invSlot >= 0) {
            // Found in inventory - swap to hotbar slot 8 using reliable SWAP click
            int containerId = mc.player.inventoryMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(containerId, invSlot, 8, ClickType.SWAP, mc.player);

            LOGGER.info("[ElytraBot] Swapped firework from inventory slot {} to hotbar slot 8", invSlot);

            // Now select hotbar slot 8 and schedule use after 2 ticks (swap needs extra sync time)
            previousSlotBeforeFirework = mc.player.getInventory().selected;
            mc.player.getInventory().selected = 8;
            pendingFireworkSlot = 8;
            pendingFireworkDelay = 2; // Extra tick for inventory swap to sync
            pendingInventorySwap = true;
            return;
        }

        // No fireworks found anywhere
    }

    /**
     * Search hotbar (slots 0-8) for firework rockets.
     * Returns hotbar index (0-8) or -1 if not found.
     */
    private int findFireworkInHotbar() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }

    /**
     * Search full inventory (slots 9-35 in inventoryMenu = main inventory) for firework rockets.
     * Returns inventoryMenu slot index or -1 if not found.
     * Prefers the largest stack to minimize swap operations.
     */
    private int findFireworkInInventory() {
        if (mc.player == null) return -1;

        int bestSlot = -1;
        int bestCount = 0;

        // Main inventory: inventoryMenu slots 9-35
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.FIREWORK_ROCKET) && stack.getCount() > bestCount) {
                bestSlot = i;
                bestCount = stack.getCount();
            }
        }
        return bestSlot;
    }

    private boolean hasFireworks() {
        return findFireworkInHotbar() >= 0 || findFireworkInInventory() >= 0;
    }

    // ===== ANTI-KICK FLIGHT NOISE =====

    /**
     * Updates flight noise values to make movement look more human-like.
     * Adds subtle random variations to yaw, pitch and altitude to avoid
     * anti-cheat detection on 2b2t.
     *
     * Changes:
     * - Small yaw jitter (±2 degrees)
     * - Small pitch jitter (±1 degree)
     * - Altitude micro-variations (±3 blocks)
     */
    private void updateFlightNoise() {
        noiseTimer++;
        if (noiseTimer >= noiseChangeInterval) {
            noiseTimer = 0;
            // Smooth random walk: new target noise values
            yawNoise = (noiseRandom.nextFloat() - 0.5f) * 4.0f;   // ±2 degrees
            pitchNoise = (noiseRandom.nextFloat() - 0.5f) * 2.0f; // ±1 degree
            altitudeNoise = (noiseRandom.nextDouble() - 0.5) * 6.0; // ±3 blocks
            // Vary the interval slightly to be less predictable
            noiseChangeInterval = 30 + noiseRandom.nextInt(30); // 1.5-3 seconds
        }
    }

    // ===== ROTATION =====

    private void applyRotation() {
        if (mc.player == null) return;
        // Smoothly interpolate rotation with optional noise
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        // Disable noise during takeoff for precise orientation
        float noiseY = (useFlightNoise && !isTakingOff) ? yawNoise : 0;
        float noiseP = (useFlightNoise && !isTakingOff) ? pitchNoise : 0;

        float yawDiff = wrapDegrees((targetYaw + noiseY) - currentYaw);
        float pitchDiff = (targetPitch + noiseP) - currentPitch;

        // Use faster rotation rate during takeoff (10°/tick) vs normal (3-5°/tick)
        float maxYawRate = isTakingOff ? 15.0f : 5.0f;
        float maxPitchRate = isTakingOff ? 10.0f : 3.0f;

        float yawStep = Math.min(Math.abs(yawDiff), maxYawRate) * Math.signum(yawDiff);
        float pitchStep = Math.min(Math.abs(pitchDiff), maxPitchRate) * Math.signum(pitchDiff);

        mc.player.setYRot(currentYaw + yawStep);
        mc.player.setXRot(currentPitch + pitchStep);
    }

    private float calculateYawToTarget(BlockPos target) {
        if (mc.player == null) return 0;
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private float wrapDegrees(float degrees) {
        degrees = degrees % 360;
        if (degrees >= 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }

    // ===== GETTERS / SETTERS =====

    public boolean isFlying() { return isFlying; }
    public FlightState getState() { return state; }
    public BlockPos getDestination() { return destination; }

    public void setCruiseAltitude(double alt) { this.cruiseAltitude = alt; }
    public void setMinAltitude(double alt) { this.minAltitude = alt; }
    public void setFireworkInterval(int ticks) { this.fireworkInterval = ticks; }
    public void setMinElytraDurability(int durability) { this.minElytraDurability = durability; }
    public void setUseFlightNoise(boolean v) { this.useFlightNoise = v; }
    public void setUseObstacleAvoidance(boolean v) { this.useObstacleAvoidance = v; }
    public void setLagDetector(LagDetector detector) { this.lagDetector = detector; }
    public boolean hasUnloadedChunksAhead() { return unloadedChunksAhead; }

    // Circling settings
    public void setEnableCircling(boolean v) { this.enableCircling = v; }
    public void setCircleRadius(double r) { this.circleRadius = r; }
    public void setCircleTimeout(int ticks) { this.circleTimeout = ticks; }
    public boolean isCircling() { return state == FlightState.CIRCLING; }
    public int getCircleTicks() { return circleTicks; }

    // Baritone landing settings
    public void setUseBaritoneLanding(boolean v) { this.useBaritoneLanding = v; }
    public void setAcceptedFallDamage(int halfHearts) { this.acceptedFallDamage = halfHearts; }
    public void setBaritoneController(BaritoneController ctrl) { this.baritoneController = ctrl; }

    // Terrain prediction
    public void setTerrainPredictor(TerrainPredictor predictor) { this.terrainPredictor = predictor; }
    public void setTerrainSafetyMargin(int margin) { this.terrainSafetyMargin = margin; }
    public TerrainPredictor getTerrainPredictor() { return terrainPredictor; }

    /**
     * Count ALL firework rockets in the player's inventory (hotbar + main inventory).
     * Manual scan for reliability instead of relying on InventoryUtils.
     */
    public int getFireworkCount() {
        if (mc.player == null) return 0;

        int count = 0;
        // Hotbar (slots 0-8 in player inventory)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) {
                count += stack.getCount();
            }
        }
        // Main inventory (slots 9-35 in inventoryMenu)
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.FIREWORK_ROCKET)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public double getDistanceToDestination() {
        if (mc.player == null || destination == null) return -1;
        return Math.sqrt(
                Math.pow(mc.player.getX() - destination.getX(), 2) +
                Math.pow(mc.player.getZ() - destination.getZ(), 2)
        );
    }
}
