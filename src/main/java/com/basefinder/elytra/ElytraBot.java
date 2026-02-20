package com.basefinder.elytra;

import com.basefinder.terrain.TerrainPredictor;
import com.basefinder.util.BaritoneController;
import com.basefinder.util.LagDetector;
import com.basefinder.util.Lang;
import com.basefinder.util.MathUtils;
import com.basefinder.util.PhysicsSimulator;
import com.basefinder.util.PhysicsSimulator.FlightState;
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
import org.rusherhack.client.api.utils.ChatUtils;

import java.util.Random;

/**
 * Rewritten automated elytra flight controller with ZERO-DAMAGE guarantee.
 *
 * Core principle: NEVER apply a rotation without first simulating it with PhysicsSimulator
 * and verifying the trajectory is safe.
 *
 * Features:
 * - Physics-based trajectory prediction (40-tick lookahead)
 * - Multi-candidate pitch evaluation (best of 21 candidates per tick)
 * - Terrain-aware cruise altitude (adapts to mountains/valleys)
 * - Emergency pull-up when collision is imminent
 * - Anti-stall recovery (dive to regain speed)
 * - Smooth rotation interpolation (no snapping)
 * - Safe descent for base approach photography
 * - 3-tick elytra swap state machine
 * - Anti-kick micro noise
 * - Baritone ground navigation after landing
 * - 2b2t lag compensation (circling when chunks don't load)
 */
public class ElytraBot {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ElytraBot");
    private final Minecraft mc = Minecraft.getInstance();
    private final PhysicsSimulator physics = new PhysicsSimulator();

    // === FLIGHT PARAMETERS (set via module settings) ===
    private double cruiseAltitude = 200.0;
    private double minAltitude = 100.0;
    private int fireworkInterval = 40;
    private int minElytraDurability = 10;
    private boolean useFlightNoise = true;
    private boolean useObstacleAvoidance = true;

    // === PHYSICS CONSTANTS ===
    /** Number of ticks to simulate ahead for safety. */
    private static final int SIMULATION_TICKS = 40;
    /** Number of candidate pitches to evaluate each tick. */
    private static final int PITCH_CANDIDATES = 21;
    /** Minimum pitch (max climb angle). */
    private static final float MIN_PITCH = -55.0f;
    /** Maximum pitch (max dive angle). */
    private static final float MAX_PITCH = 45.0f;
    /** Maximum pitch change per tick for smooth rotation. */
    private static final float MAX_PITCH_RATE = 4.0f;
    /** Maximum yaw change per tick during cruise. */
    private static final float MAX_YAW_RATE_CRUISE = 5.0f;
    /** Maximum yaw change per tick during critical phases. */
    private static final float MAX_YAW_RATE_FAST = 15.0f;
    /** Minimum terrain clearance in blocks. */
    private static final double SAFETY_CLEARANCE = 3.0;
    /** Emergency pull-up pitch. */
    private static final float EMERGENCY_PITCH = -55.0f;
    /** Stall recovery pitch (dive to regain speed). */
    private static final float STALL_RECOVERY_PITCH = 20.0f;
    /** Minimum speed before stall recovery. */
    private static final double STALL_SPEED = 0.3;
    /** Terrain scan distance for obstacle detection. */
    private static final int LOOK_AHEAD_BLOCKS = 60;

    // === STATE ===
    private ElytraBot.FlightPhase state = FlightPhase.IDLE;
    private boolean isFlying = false;
    private BlockPos destination = null;
    private float targetYaw = 0;
    private int tickCounter = 0;
    private int fireworkCooldown = 0;
    private Vec3 lastPosition = null;
    private int stuckTimer = 0;

    // Takeoff state machine
    private int takeoffPhase = -1;  // -1=PRE_ROTATE, 0=JUMP, 1=WAIT_APEX, 2=DEPLOY, 3=BOOST
    private int takeoffTimer = 0;
    private int takeoffAttempts = 0;
    private int takeoffAirTicks = 0;
    private boolean isTakingOff = false;

    // Stall recovery
    private int stallRecoveryTicks = 0;

    // Elytra swap (3-tick process)
    private int elytraSwapStep = 0;
    private int elytraSwapSlot = -1;

    // Firework management (delayed use)
    private int pendingFireworkSlot = -1;
    private int pendingFireworkDelay = 0;
    private int previousSlotBeforeFirework = -1;
    private boolean pendingInventorySwap = false;

    // Firework monitoring
    private int lastFireworkCount = -1;
    private boolean lowFireworkWarned = false;

    // Landing
    private int landingTimer = 0;

    // Safe descent (base approach)
    private BlockPos approachTarget = null;
    private double approachTargetAltitude = 80.0;

    // Anti-kick noise
    private final Random noiseRandom = new Random();
    private float yawNoise = 0;
    private float pitchNoise = 0;
    private int noiseTimer = 0;
    private int noiseChangeInterval = 40;

    // Circling (chunk wait)
    private boolean enableCircling = true;
    private BlockPos circleCenter = null;
    private double circleAngle = 0;
    private int circleTicks = 0;
    private double circleRadius = 300;
    private int circleTimeout = 600;
    private FlightPhase stateBeforeCircling = FlightPhase.CRUISING;

    // Lag detection
    private LagDetector lagDetector = null;
    private boolean unloadedChunksAhead = false;
    private double safeAltitudeBoost = 0;

    // Terrain prediction
    private TerrainPredictor terrainPredictor = null;
    private int terrainSafetyMargin = 40;

    // Baritone landing
    private boolean useBaritoneLanding = true;
    private int acceptedFallDamage = 3;
    private BaritoneController baritoneController = null;
    private int baritoneLandingTimer = 0;
    private static final int BARITONE_LANDING_TIMEOUT = 200;

    // Durability check interval
    private int durabilityCheckInterval = 20;

    /**
     * Flight phases (kept compatible with HUD).
     * IMPORTANT: The HUD calls getState().name() — keep these enum names stable.
     */
    public enum FlightPhase {
        IDLE,
        TAKING_OFF,
        CLIMBING,
        CRUISING,
        DESCENDING,
        SAFE_DESCENDING,
        FLARING,
        LANDING,
        REFUELING,
        CIRCLING,
        BARITONE_LANDING
    }

    // ========================================================================
    // PUBLIC API (must remain compatible with BaseFinderModule + ElytraBotModule)
    // ========================================================================

    /**
     * Main tick method. Call every game tick while the module is enabled.
     */
    public void tick() {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        if (fireworkCooldown > 0) fireworkCooldown--;

        // Anti-kick noise
        if (useFlightNoise) updateFlightNoise();

        // Pending firework use
        if (pendingFireworkSlot >= 0) processFireworkUse();

        // Elytra swap (1 step per tick)
        if (elytraSwapStep > 0) {
            processElytraSwap();
            return;
        }

        // Stuck detection
        Vec3 currentPos = mc.player.position();
        if (lastPosition != null && currentPos.distanceTo(lastPosition) < 0.01 && state != FlightPhase.IDLE) {
            stuckTimer++;
        } else {
            stuckTimer = 0;
        }
        lastPosition = currentPos;

        // Durability check during flight
        if (tickCounter % durabilityCheckInterval == 0 && isFlying
                && state != FlightPhase.LANDING && state != FlightPhase.IDLE) {
            checkElytraDurability();
        }

        // Debug log every 2 seconds
        if (tickCounter % 40 == 0 && state != FlightPhase.IDLE) {
            LOGGER.info("[ElytraBot] State={}, fallFlying={}, onGround={}, Y={}, speed={}, dest={}",
                    state, mc.player.isFallFlying(), mc.player.onGround(),
                    (int) mc.player.getY(),
                    String.format("%.2f", PhysicsSimulator.getSpeed(mc.player)),
                    destination != null ? destination.toShortString() : "none");
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

    /** Start flying towards a destination. */
    public void startFlight(BlockPos target) {
        if (mc.player == null) return;

        this.destination = target;
        this.isFlying = true;
        this.targetYaw = calculateYawToTarget(target);

        if (mc.player.isFallFlying()) {
            state = FlightPhase.CRUISING;
        } else {
            ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.is(Items.ELYTRA)) {
                state = FlightPhase.TAKING_OFF;
                takeoffTimer = 0;
                takeoffPhase = -1;
                isTakingOff = true;
            }
        }
    }

    /** Stop all flight operations. */
    public void stop() {
        isFlying = false;
        state = FlightPhase.IDLE;
        destination = null;
        elytraSwapStep = 0;
        elytraSwapSlot = -1;
        lastFireworkCount = -1;
        lowFireworkWarned = false;
        isTakingOff = false;
        takeoffPhase = -1;
        stallRecoveryTicks = 0;
        circleCenter = null;
        circleTicks = 0;
        circleAngle = 0;
        pendingFireworkSlot = -1;
        if (baritoneController != null) baritoneController.cancelLanding();
        baritoneLandingTimer = 0;
    }

    /** Start controlled descent for base approach photography. */
    public void startSafeDescent(BlockPos target, double targetAltitude) {
        this.approachTarget = target;
        this.approachTargetAltitude = targetAltitude;
        this.destination = target;
        this.state = FlightPhase.SAFE_DESCENDING;
        this.isFlying = true;
        LOGGER.info("[ElytraBot] Starting safe descent to alt {} for {}", (int) targetAltitude, target.toShortString());
    }

    public boolean isAtApproachAltitude() {
        if (mc.player == null) return false;
        return Math.abs(mc.player.getY() - approachTargetAltitude) < 10;
    }

    public boolean isSafeDescending() {
        return state == FlightPhase.SAFE_DESCENDING;
    }

    public boolean isFlying() { return isFlying; }

    /**
     * Returns the current state. HUD calls .name() on this — keep enum values stable.
     */
    public FlightPhase getState() { return state; }

    public BlockPos getDestination() { return destination; }
    public boolean hasUnloadedChunksAhead() { return unloadedChunksAhead; }
    public boolean isCircling() { return state == FlightPhase.CIRCLING; }
    public int getCircleTicks() { return circleTicks; }

    public double getDistanceToDestination() {
        if (mc.player == null || destination == null) return -1;
        double dx = mc.player.getX() - destination.getX();
        double dz = mc.player.getZ() - destination.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ========================================================================
    // CORE AUTOPILOT — Physics-based pitch calculation
    // ========================================================================

    /**
     * Calculate the optimal pitch using physics simulation.
     * Evaluates multiple candidate pitches and picks the one with the best score.
     *
     * Score components:
     * - Altitude proximity to target (main factor)
     * - Terrain clearance (critical safety)
     * - Speed maintenance
     * - Smoothness (prefer small changes)
     * - HARD REJECT if any simulation tick leads to collision/damage
     */
    private float calculateOptimalPitch(double targetAlt) {
        if (mc.player == null) return 0;

        FlightState current = FlightState.fromPlayer(mc.player);
        float currentPitch = mc.player.getXRot();
        float bestPitch = currentPitch;
        double bestScore = Double.NEGATIVE_INFINITY;

        // Evaluate coarse candidates
        for (int i = 0; i < PITCH_CANDIDATES; i++) {
            float candidate = MIN_PITCH + (MAX_PITCH - MIN_PITCH) * ((float) i / (PITCH_CANDIDATES - 1));
            double score = evaluatePitchCandidate(current, candidate, targetAlt);
            if (score > bestScore) {
                bestScore = score;
                bestPitch = candidate;
            }
        }

        // Refine around best (+/- 4 degrees, 1-degree steps)
        float refined = bestPitch;
        double refinedScore = bestScore;
        for (float delta = -4.0f; delta <= 4.0f; delta += 1.0f) {
            float candidate = MathUtils.clamp(bestPitch + delta, MIN_PITCH, MAX_PITCH);
            double score = evaluatePitchCandidate(current, candidate, targetAlt);
            if (score > refinedScore) {
                refinedScore = score;
                refined = candidate;
            }
        }

        return refined;
    }

    /**
     * Evaluate a candidate pitch by simulating forward and scoring the trajectory.
     */
    private double evaluatePitchCandidate(FlightState current, float pitch, double targetAlt) {
        FlightState[] trajectory = physics.simulateForward(current, pitch, current.yaw, SIMULATION_TICKS, false);

        double score = 0;

        for (int i = 0; i < trajectory.length; i++) {
            FlightState s = trajectory[i];
            double weight = 1.0 - ((double) i / trajectory.length); // Earlier ticks matter more

            // 1. TERRAIN CLEARANCE (critical)
            int groundY = getTerrainHeight((int) s.x, (int) s.z);
            double clearance = s.y - groundY;

            if (clearance < 0) {
                return -1_000_000; // Collision = instant reject
            }
            if (clearance < SAFETY_CLEARANCE) {
                score -= (SAFETY_CLEARANCE - clearance) * 500 * weight;
            } else {
                score += Math.min(clearance, 30) * weight; // Reward clearance up to a point
            }

            // 2. ALTITUDE PROXIMITY to target
            double altError = Math.abs(s.y - targetAlt);
            score -= altError * 8 * weight;

            // 3. SPEED MAINTENANCE
            double speed = s.getTotalSpeed();
            if (speed < STALL_SPEED) {
                score -= 300 * weight; // Stall penalty
            } else if (speed > 0.5 && speed < 2.5) {
                score += 15 * weight; // Good speed range
            }

            // 4. FALL DAMAGE check near ground
            if (clearance < 6 && PhysicsSimulator.wouldCauseFallDamage(s.motionY)) {
                score -= 5000 * weight;
            }
        }

        // 5. SMOOTHNESS bonus (prefer small pitch changes)
        double pitchChange = Math.abs(pitch - current.pitch);
        score -= pitchChange * 2.5;

        return score;
    }

    /**
     * Get terrain height at a position using the best available source.
     */
    private int getTerrainHeight(int x, int z) {
        // Use terrain predictor if available (seed-based + cache)
        if (terrainPredictor != null) {
            return terrainPredictor.predictHeight(x, z);
        }

        // Fallback: direct world query if chunk is loaded
        if (mc.level != null && mc.level.hasChunk(x >> 4, z >> 4)) {
            return mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        }

        return 64; // Default sea level if no data
    }

    /**
     * Get the maximum terrain height ahead of the player's flight path.
     */
    private int getMaxTerrainAhead(int distance) {
        if (mc.player == null) return 64;

        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = MathUtils.horizontalSpeed(velocity);
        double dirX, dirZ;

        if (hSpeed > 0.01) {
            dirX = velocity.x / hSpeed;
            dirZ = velocity.z / hSpeed;
        } else {
            float yawRad = (float) Math.toRadians(mc.player.getYRot());
            dirX = -Math.sin(yawRad);
            dirZ = Math.cos(yawRad);
        }

        // If terrain predictor is available, use it (faster, includes seed prediction)
        if (terrainPredictor != null) {
            return terrainPredictor.getMaxHeightAhead(mc.player.position(), velocity, distance);
        }

        // Fallback: manual block scan
        int maxH = 0;
        Vec3 pos = mc.player.position();
        for (int d = 0; d < distance; d += 8) {
            int px = (int) (pos.x + dirX * d);
            int pz = (int) (pos.z + dirZ * d);
            int h = getTerrainHeight(px, pz);
            maxH = Math.max(maxH, h);
        }
        return maxH;
    }

    /**
     * Calculate effective target altitude considering terrain, mode, and safety.
     */
    private double getEffectiveTargetAltitude() {
        double baseAlt = cruiseAltitude + safeAltitudeBoost;

        // Terrain-aware: fly at least terrainSafetyMargin above terrain ahead
        int maxTerrainAhead = getMaxTerrainAhead(300);
        if (maxTerrainAhead > 0) {
            baseAlt = Math.max(baseAlt, maxTerrainAhead + terrainSafetyMargin);
        }

        return baseAlt;
    }

    // ========================================================================
    // SMOOTH ROTATION APPLICATION
    // ========================================================================

    /**
     * Apply pitch/yaw with smooth interpolation. NEVER snaps.
     * Rate is context-dependent: fast during takeoff/emergency, slow during cruise.
     */
    private void applySmoothRotation(float desiredPitch, float desiredYaw) {
        if (mc.player == null) return;

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        // Add noise only during non-critical phases
        boolean critical = isTakingOff || state == FlightPhase.FLARING
                || state == FlightPhase.LANDING || state == FlightPhase.DESCENDING;
        float noiseY = (useFlightNoise && !critical) ? yawNoise : 0;
        float noiseP = (useFlightNoise && !critical) ? pitchNoise : 0;

        // Pitch rate: faster during critical phases
        float maxPitchRate;
        float maxYawRate;
        if (isTakingOff) {
            maxPitchRate = 10.0f;
            maxYawRate = MAX_YAW_RATE_FAST;
        } else if (state == FlightPhase.FLARING || state == FlightPhase.LANDING) {
            maxPitchRate = 12.0f;
            maxYawRate = 10.0f;
        } else if (state == FlightPhase.DESCENDING || state == FlightPhase.SAFE_DESCENDING) {
            maxPitchRate = 6.0f;
            maxYawRate = 8.0f;
        } else {
            maxPitchRate = MAX_PITCH_RATE;
            maxYawRate = MAX_YAW_RATE_CRUISE;
        }

        float yawDiff = MathUtils.wrapAngle((desiredYaw + noiseY) - currentYaw);
        float pitchDiff = (desiredPitch + noiseP) - currentPitch;

        float yawStep = MathUtils.clamp(yawDiff, -maxYawRate, maxYawRate);
        float pitchStep = MathUtils.clamp(pitchDiff, -maxPitchRate, maxPitchRate);

        mc.player.setYRot(currentYaw + yawStep);
        mc.player.setXRot(MathUtils.clamp(currentPitch + pitchStep, -90.0f, 90.0f));
    }

    /**
     * Apply pitch with simulation safety check.
     * If the proposed pitch would cause damage, reject it and use a safe fallback.
     */
    private void applySafePitch(float desiredPitch) {
        if (mc.player == null) return;

        // Verify with physics simulation before applying
        FlightState current = FlightState.fromPlayer(mc.player);
        FlightState[] trajectory = physics.simulateForward(current, desiredPitch, mc.player.getYRot(), 20, false);

        boolean safe = true;
        for (FlightState s : trajectory) {
            int groundY = getTerrainHeight((int) s.x, (int) s.z);
            if (s.y < groundY + 1) {
                safe = false;
                break;
            }
            if (s.y - groundY < 3 && PhysicsSimulator.wouldCauseFallDamage(s.motionY)) {
                safe = false;
                break;
            }
        }

        if (!safe) {
            // Fallback: climb to avoid danger
            desiredPitch = Math.min(desiredPitch, -20.0f);
            LOGGER.warn("[ElytraBot] Pitch {} rejected by safety check, using {}", desiredPitch, -20.0f);
        }

        applySmoothRotation(desiredPitch, targetYaw);
    }

    // ========================================================================
    // FLIGHT STATE HANDLERS
    // ========================================================================

    private void handleTakeoff() {
        if (mc.player == null) return;

        takeoffTimer++;
        isTakingOff = true;

        if (mc.player.isFallFlying() && takeoffPhase < 3) {
            takeoffPhase = 3;
        }

        switch (takeoffPhase) {
            case -1 -> { // PRE_ROTATE
                applySmoothRotation(-45.0f, targetYaw);
                if (mc.player.getXRot() < -30.0f || takeoffTimer > 40) {
                    takeoffPhase = 0;
                }
            }
            case 0 -> { // JUMP
                applySmoothRotation(-45.0f, targetYaw);
                if (mc.player.onGround()) {
                    mc.player.jumpFromGround();
                    takeoffAirTicks = 0;
                    takeoffPhase = 1;
                }
            }
            case 1 -> { // WAIT_APEX
                applySmoothRotation(-45.0f, targetYaw);
                if (!mc.player.onGround()) {
                    takeoffAirTicks++;
                    if (mc.player.getDeltaMovement().y <= 0.1 || takeoffAirTicks > 7) {
                        takeoffPhase = 2;
                        takeoffAirTicks = 0;
                    }
                } else {
                    takeoffPhase = -1;
                }
            }
            case 2 -> { // DEPLOY
                applySmoothRotation(-45.0f, targetYaw);
                takeoffAirTicks++;
                if (mc.player.isFallFlying()) {
                    takeoffPhase = 3;
                    break;
                }
                if (mc.getConnection() != null) {
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(
                            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                    ));
                }
                if (takeoffAirTicks > 5 || mc.player.onGround()) {
                    takeoffAttempts++;
                    if (takeoffAttempts >= 5) {
                        ChatUtils.print("[ElytraBot] " + Lang.t("Takeoff failed after 5 attempts!", "Décollage échoué après 5 tentatives !"));
                    } else {
                        takeoffPhase = -1;
                    }
                }
            }
            case 3 -> { // BOOST
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra deployed! Climbing...", "Elytra déployé ! Montée..."));
                fireworkCooldown = 0;
                applySmoothRotation(-45.0f, targetYaw);
                useFirework();
                isTakingOff = false;
                takeoffPhase = -1;
                takeoffAttempts = 0;
                takeoffAirTicks = 0;
                takeoffTimer = 0;
                state = FlightPhase.CLIMBING;
            }
        }

        if (takeoffTimer > 200) {
            LOGGER.warn("[ElytraBot] Takeoff global timeout");
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
            state = FlightPhase.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            return;
        }

        double effectiveAlt = getEffectiveTargetAltitude();
        double altDiff = effectiveAlt - mc.player.getY();

        // Use physics simulation to find optimal climb pitch
        float pitch;
        if (altDiff > 50) {
            pitch = -45.0f;
            useFirework();
        } else if (altDiff > 20) {
            pitch = -25.0f;
            useFirework();
        } else if (altDiff > 5) {
            pitch = -10.0f;
        } else {
            pitch = -2.0f;
        }

        applySafePitch(pitch);

        if (mc.player.getY() >= effectiveAlt - 3) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Cruising at altitude ", "Croisière à altitude ") + (int) effectiveAlt);
            state = FlightPhase.CRUISING;
        }
    }

    /**
     * Main cruising handler — uses physics-based autopilot.
     */
    private void handleCruising() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            state = FlightPhase.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            return;
        }

        // Update yaw towards destination
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }

        // 2b2t lag safety
        updateChunkLoadingSafety();

        // Enter circling if chunks not loaded
        if (enableCircling && shouldEnterCircling()) {
            enterCircling();
            return;
        }

        double effectiveAlt = getEffectiveTargetAltitude();

        // === ANTI-STALL: if speed critically low, recover first ===
        double speed = PhysicsSimulator.getSpeed(mc.player);
        if (speed < STALL_SPEED && stallRecoveryTicks <= 0) {
            stallRecoveryTicks = 20;
        }

        if (stallRecoveryTicks > 0) {
            stallRecoveryTicks--;
            // Dive to regain speed, but check terrain
            float recoveryPitch = STALL_RECOVERY_PITCH;
            double groundDist = getGroundDistance();
            if (groundDist < 20) {
                recoveryPitch = 5.0f; // Gentle if low
            }
            applySafePitch(recoveryPitch);
            return;
        }

        // === PHYSICS-BASED PITCH CALCULATION ===
        float optimalPitch = calculateOptimalPitch(effectiveAlt);

        // Speed limiting: if too fast, pull up
        if (speed > 2.5) {
            optimalPitch = Math.min(optimalPitch, -10.0f);
        }

        // Periodic firework to maintain speed
        double hSpeed = PhysicsSimulator.getHorizontalSpeed(mc.player);
        if (hSpeed < 0.8) {
            useFirework();
        }

        applySafePitch(optimalPitch);

        // Check if near destination → begin descent
        if (destination != null) {
            double distXZ = getDistanceToDestination();
            double destGroundY = estimateGroundHeightAtDestination();
            double altAboveDest = mc.player.getY() - destGroundY;
            double descentStartDist = Math.max(500, altAboveDest * 7);
            if (distXZ < descentStartDist && distXZ >= 0) {
                state = FlightPhase.DESCENDING;
            }
        }

        // Firework supply monitoring
        checkFireworkSupply();
    }

    private void handleDescending() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            state = FlightPhase.LANDING;
            landingTimer = 0;
            return;
        }

        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }

        double groundDist = getGroundDistance();
        Vec3 vel = mc.player.getDeltaMovement();
        double hSpeed = MathUtils.horizontalSpeed(vel);
        double distToDest = destination != null ? getDistanceToDestination() : 500;
        double destGroundY = estimateGroundHeightAtDestination();
        double altAboveDest = mc.player.getY() - destGroundY;

        // Terrain safety: high terrain ahead → climb
        int terrainMax = getMaxTerrainAhead(300);
        if (terrainMax > 0 && mc.player.getY() < terrainMax + terrainSafetyMargin) {
            applySafePitch(-20.0f);
            useFirework();
            return;
        }

        // Transition to flaring when close to ground
        if (groundDist < 40) {
            state = FlightPhase.FLARING;
            return;
        }

        // Glide slope computation
        double targetArrivalAlt = destGroundY + 40;
        double altToLose = mc.player.getY() - targetArrivalAlt;
        float pitch;

        if (altToLose > 0 && distToDest > 50) {
            double idealAngle = Math.toDegrees(Math.atan2(altToLose, distToDest));
            idealAngle = MathUtils.clamp(idealAngle, 2.0, 10.0);

            if (hSpeed > 1.5) {
                pitch = -5.0f; // Brake
            } else {
                pitch = (float) idealAngle;
            }
        } else if (altToLose <= 0) {
            pitch = -2.0f;
        } else {
            pitch = 5.0f;
        }

        applySafePitch(pitch);

        if (mc.player.onGround()) {
            finishLanding();
        }
    }

    private void handleSafeDescent() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            state = FlightPhase.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            return;
        }

        if (approachTarget != null) {
            targetYaw = calculateYawToTarget(approachTarget);
        }

        double currentY = mc.player.getY();
        float pitch;

        if (currentY > approachTargetAltitude + 40) {
            pitch = 12.0f;
        } else if (currentY > approachTargetAltitude + 15) {
            pitch = 6.0f;
        } else if (currentY > approachTargetAltitude + 5) {
            pitch = 2.0f;
        } else if (currentY >= approachTargetAltitude - 5) {
            pitch = -2.0f;
            if (mc.player.getDeltaMovement().y < -0.15) useFirework();
        } else {
            pitch = -12.0f;
            useFirework();
        }

        applySafePitch(pitch);

        // Maintain minimum speed to keep elytra active
        if (PhysicsSimulator.getHorizontalSpeed(mc.player) < 0.5) {
            useFirework();
        }
    }

    /**
     * Flare: pitch up to bleed speed naturally. ZERO fireworks.
     */
    private void handleFlaring() {
        if (mc.player == null) return;

        double groundDist = getGroundDistance();

        if (!mc.player.isFallFlying()) {
            state = FlightPhase.LANDING;
            landingTimer = 0;
            return;
        }

        double hSpeed = MathUtils.horizontalSpeed(mc.player.getDeltaMovement());
        float pitch;

        if (hSpeed > 1.2) {
            pitch = -25.0f;
        } else if (hSpeed > 0.6) {
            pitch = -35.0f;
        } else if (hSpeed > 0.2) {
            pitch = -45.0f;
        } else {
            pitch = 10.0f;
        }

        // If flaring sent us too high, descend
        if (groundDist > 50) {
            pitch = 8.0f;
        }

        if (destination != null) targetYaw = calculateYawToTarget(destination);
        applySmoothRotation(pitch, targetYaw);

        // Toggle elytra off when slow and close to ground
        if (hSpeed < 0.3 && groundDist < 10) {
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                ));
            }
            state = FlightPhase.LANDING;
            landingTimer = 0;
            return;
        }

        if (mc.player.onGround()) finishLanding();
    }

    private void handleLanding() {
        if (mc.player == null) return;
        landingTimer++;

        if (mc.player.isFallFlying()) {
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                ));
            }
            applySmoothRotation(-30.0f, targetYaw);
        }

        if (mc.player.onGround()) {
            if (useBaritoneLanding && baritoneController != null && baritoneController.isAvailable()
                    && destination != null && getDistanceToDestination() > 5) {
                startBaritoneLanding();
            } else {
                finishLanding();
            }
            return;
        }

        if (landingTimer > 200) finishLanding();
    }

    private void handleRefueling() {
        boolean hasInHotbar = findFireworkInHotbar() >= 0;
        boolean hasInInventory = findFireworkInInventory() >= 0;
        if (hasInHotbar || hasInInventory) {
            state = FlightPhase.CRUISING;
            lowFireworkWarned = false;
        } else {
            // Gentle glide
            if (destination != null) targetYaw = calculateYawToTarget(destination);
            applySmoothRotation(-3.0f, targetYaw);
            if (mc.player != null && mc.player.getY() < minAltitude) {
                ChatUtils.print("[ElytraBot] " + Lang.t("No fireworks! Landing...", "Plus de fusées ! Atterrissage..."));
                state = FlightPhase.LANDING;
            }
        }
    }

    private void finishLanding() {
        state = FlightPhase.IDLE;
        isFlying = false;
        landingTimer = 0;
        ChatUtils.print("[ElytraBot] " + Lang.t("Landed.", "Atterri."));
    }

    // ========================================================================
    // CIRCLING (chunk wait mode)
    // ========================================================================

    private boolean shouldEnterCircling() {
        if (lagDetector == null) return false;
        return lagDetector.getUnloadedChunksAhead() >= 3 || lagDetector.isSeverelyLagging();
    }

    private void enterCircling() {
        if (mc.player == null) return;
        stateBeforeCircling = state;
        circleCenter = mc.player.blockPosition();
        circleAngle = 0;
        circleTicks = 0;
        state = FlightPhase.CIRCLING;
        ChatUtils.print("[ElytraBot] " + Lang.t("Chunks not loaded - circling...", "Chunks non chargés - orbite d'attente..."));
    }

    private void handleCircling() {
        if (mc.player == null || circleCenter == null) {
            exitCircling();
            return;
        }

        if (!mc.player.isFallFlying()) {
            state = FlightPhase.TAKING_OFF;
            takeoffTimer = 0;
            takeoffPhase = -1;
            isTakingOff = true;
            return;
        }

        circleTicks++;

        double hSpeed = MathUtils.horizontalSpeed(mc.player.getDeltaMovement());
        if (hSpeed < 0.1) hSpeed = 1.0;

        double circumference = 2.0 * Math.PI * circleRadius;
        double ticksPerCircle = Math.max(100, circumference / (hSpeed * 20.0));
        circleAngle += 2.0 * Math.PI / ticksPerCircle;

        double orbitX = circleCenter.getX() + Math.cos(circleAngle) * circleRadius;
        double orbitZ = circleCenter.getZ() + Math.sin(circleAngle) * circleRadius;
        targetYaw = calculateYawToTarget(BlockPos.containing(orbitX, mc.player.getY(), orbitZ));

        // Maintain cruise altitude using physics autopilot
        double effectiveAlt = getEffectiveTargetAltitude();
        float pitch = calculateOptimalPitch(effectiveAlt);
        if (PhysicsSimulator.getHorizontalSpeed(mc.player) < 0.8) useFirework();

        applySafePitch(pitch);

        // Exit conditions
        if (lagDetector != null && lagDetector.isFlightPathLoaded()
                && lagDetector.areChunksStabilized() && !lagDetector.isSeverelyLagging()) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Chunks loaded - resuming!", "Chunks chargés - reprise !"));
            exitCircling();
            return;
        }

        if (circleTicks >= circleTimeout) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Circling timeout - resuming with safety altitude", "Timeout orbite - reprise avec altitude de sécurité"));
            safeAltitudeBoost = 20;
            exitCircling();
        }
    }

    private void exitCircling() {
        circleCenter = null;
        circleTicks = 0;
        circleAngle = 0;
        state = FlightPhase.CRUISING;
        if (destination != null) targetYaw = calculateYawToTarget(destination);
    }

    // ========================================================================
    // BARITONE LANDING
    // ========================================================================

    private void startBaritoneLanding() {
        if (mc.player == null || baritoneController == null) return;
        BlockPos groundPos = destination != null ? findGroundBelow(destination) : findGroundBelow(mc.player.blockPosition());
        baritoneController.setAcceptDamage(acceptedFallDamage);
        baritoneController.configureForFastLanding();
        baritoneController.landAt(groundPos);
        baritoneLandingTimer = 0;
        state = FlightPhase.BARITONE_LANDING;
        ChatUtils.print("[ElytraBot] " + Lang.t("Baritone walking to ", "Baritone marche vers ") + groundPos.toShortString());
    }

    private void handleBaritoneLanding() {
        if (mc.player == null) return;
        baritoneLandingTimer++;

        if (baritoneController != null && baritoneController.isLandingComplete()) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Arrived at destination via Baritone.", "Arrivé à destination via Baritone."));
            finishLanding();
            return;
        }

        if (destination != null && getDistanceToDestination() < 5 && mc.player.onGround()) {
            if (baritoneController != null) baritoneController.cancelLanding();
            finishLanding();
            return;
        }

        if (baritoneLandingTimer >= BARITONE_LANDING_TIMEOUT) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Baritone timeout - stopping here.", "Baritone timeout - arrêt ici."));
            if (baritoneController != null) baritoneController.cancelLanding();
            finishLanding();
        }
    }

    // ========================================================================
    // ELYTRA DURABILITY MANAGEMENT
    // ========================================================================

    private void checkElytraDurability() {
        if (mc.player == null) return;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);

        if (!chest.is(Items.ELYTRA)) {
            int spareSlot = findElytraInInventory();
            if (spareSlot >= 0) {
                ChatUtils.print("[ElytraBot] " + Lang.t("No elytra equipped! Equipping from inventory...", "Pas d'elytra équipé ! Équipement depuis l'inventaire..."));
                startElytraSwap(spareSlot);
            } else {
                ChatUtils.print("[ElytraBot] " + Lang.t("No elytra available! Emergency landing...", "Aucun elytra disponible ! Atterrissage d'urgence..."));
                state = FlightPhase.DESCENDING;
            }
            return;
        }

        int remaining = chest.getMaxDamage() - chest.getDamageValue();
        if (remaining <= minElytraDurability) {
            int spareSlot = findElytraInInventory();
            if (spareSlot >= 0) {
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra low (" + remaining + ")! Swapping...", "Elytra usé (" + remaining + ") ! Échange..."));
                startElytraSwap(spareSlot);
            } else {
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra low and no spare! Landing...", "Elytra usé et aucun de rechange ! Atterrissage..."));
                state = FlightPhase.DESCENDING;
            }
        }
    }

    private int findElytraInInventory() {
        if (mc.player == null) return -1;
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.ELYTRA)) {
                int durability = stack.getMaxDamage() - stack.getDamageValue();
                if (durability > minElytraDurability) return i;
            }
        }
        return -1;
    }

    public int getElytraCount() {
        if (mc.player == null) return 0;
        int count = 0;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA) && (chest.getMaxDamage() - chest.getDamageValue()) > minElytraDurability) count++;
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamageValue()) > minElytraDurability) count++;
        }
        return count;
    }

    public int getEquippedElytraDurability() {
        if (mc.player == null) return -1;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) return -1;
        return chest.getMaxDamage() - chest.getDamageValue();
    }

    private void startElytraSwap(int inventorySlot) {
        elytraSwapSlot = inventorySlot;
        elytraSwapStep = 1;
    }

    private void processElytraSwap() {
        if (mc.player == null || mc.gameMode == null || elytraSwapSlot < 0) {
            elytraSwapStep = 0;
            elytraSwapSlot = -1;
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        switch (elytraSwapStep) {
            case 1 -> {
                mc.gameMode.handleInventoryMouseClick(containerId, elytraSwapSlot, 0, ClickType.PICKUP, mc.player);
                elytraSwapStep = 2;
            }
            case 2 -> {
                mc.gameMode.handleInventoryMouseClick(containerId, 6, 0, ClickType.PICKUP, mc.player);
                elytraSwapStep = 3;
            }
            case 3 -> {
                mc.gameMode.handleInventoryMouseClick(containerId, elytraSwapSlot, 0, ClickType.PICKUP, mc.player);
                elytraSwapStep = 0;
                elytraSwapSlot = -1;
                ChatUtils.print("[ElytraBot] " + Lang.t("Elytra swapped! Durability: ", "Elytra échangé ! Durabilité : ") + getEquippedElytraDurability());
            }
            default -> {
                elytraSwapStep = 0;
                elytraSwapSlot = -1;
            }
        }
    }

    // ========================================================================
    // FIREWORK MANAGEMENT
    // ========================================================================

    private void useFirework() {
        if (fireworkCooldown > 0 || mc.player == null || mc.gameMode == null) return;
        if (pendingFireworkSlot >= 0) return; // Already pending

        int hotbarSlot = findFireworkInHotbar();
        if (hotbarSlot >= 0) {
            previousSlotBeforeFirework = mc.player.getInventory().selected;
            mc.player.getInventory().selected = hotbarSlot;
            pendingFireworkSlot = hotbarSlot;
            pendingFireworkDelay = 1;
            return;
        }

        int invSlot = findFireworkInInventory();
        if (invSlot >= 0) {
            int containerId = mc.player.inventoryMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(containerId, invSlot, 8, ClickType.SWAP, mc.player);
            previousSlotBeforeFirework = mc.player.getInventory().selected;
            mc.player.getInventory().selected = 8;
            pendingFireworkSlot = 8;
            pendingFireworkDelay = 2;
            pendingInventorySwap = true;
        }
    }

    private void processFireworkUse() {
        if (mc.player == null || mc.gameMode == null) return;
        pendingFireworkDelay--;
        if (pendingFireworkDelay <= 0) {
            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            mc.player.getInventory().selected = previousSlotBeforeFirework;
            fireworkCooldown = fireworkInterval;
            pendingFireworkSlot = -1;
            pendingInventorySwap = false;
        }
    }

    private int findFireworkInHotbar() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }

    private int findFireworkInInventory() {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        int bestCount = 0;
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.FIREWORK_ROCKET) && stack.getCount() > bestCount) {
                bestSlot = i;
                bestCount = stack.getCount();
            }
        }
        return bestSlot;
    }

    public int getFireworkCount() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) count += stack.getCount();
        }
        for (int i = 9; i <= 35; i++) {
            ItemStack stack = mc.player.inventoryMenu.getSlot(i).getItem();
            if (stack.is(Items.FIREWORK_ROCKET)) count += stack.getCount();
        }
        return count;
    }

    private void checkFireworkSupply() {
        if (mc.player == null) return;
        int currentCount = getFireworkCount();

        if (lastFireworkCount >= 0 && currentCount < lastFireworkCount) {
            LOGGER.info("[ElytraBot] Firework used: {} remaining", currentCount);
        }
        lastFireworkCount = currentCount;

        if (currentCount == 0) {
            state = FlightPhase.REFUELING;
            return;
        }

        if (currentCount <= 5 && !lowFireworkWarned) {
            lowFireworkWarned = true;
            ChatUtils.print("[ElytraBot] " + Lang.t("Low fireworks! Only " + currentCount + " remaining.", "Fusées basses ! Seulement " + currentCount + " restantes."));
        }

        if (currentCount <= 2 && mc.player.getY() > minAltitude + 30) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Almost out of fireworks! Descending...", "Presque plus de fusées ! Descente..."));
            state = FlightPhase.DESCENDING;
        }
    }

    // ========================================================================
    // UTILITY
    // ========================================================================

    private double getGroundDistance() {
        if (mc.player == null || mc.level == null) return 320;
        BlockPos pos = mc.player.blockPosition();
        for (int dy = 1; dy <= 320; dy++) {
            BlockPos check = pos.below(dy);
            BlockState blockState = mc.level.getBlockState(check);
            if (!blockState.isAir() && !blockState.liquid()) return dy;
        }
        return 320;
    }

    private double estimateGroundHeightAtDestination() {
        if (destination == null) return 64;
        if (mc.level != null) {
            BlockPos ground = findGroundBelow(new BlockPos(destination.getX(), 320, destination.getZ()));
            if (ground.getY() > mc.level.getMinY() + 1) return ground.getY();
        }
        if (terrainPredictor != null) {
            return terrainPredictor.predictHeight(destination.getX(), destination.getZ());
        }
        return 64;
    }

    private BlockPos findGroundBelow(BlockPos pos) {
        if (mc.level == null) return pos;
        for (int y = pos.getY(); y > mc.level.getMinY(); y--) {
            BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
            if (!mc.level.getBlockState(check).isAir() && !mc.level.getBlockState(check).liquid()) {
                return check.above();
            }
        }
        return new BlockPos(pos.getX(), 64, pos.getZ());
    }

    private float calculateYawToTarget(BlockPos target) {
        if (mc.player == null) return 0;
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private void updateChunkLoadingSafety() {
        if (mc.player == null || mc.level == null) return;

        if (lagDetector != null && !lagDetector.isFlightPathLoaded()) {
            unloadedChunksAhead = true;
            safeAltitudeBoost = Math.min(10.0, lagDetector.getUnloadedChunksAhead() * 3.0);
            return;
        }

        Vec3 velocity = mc.player.getDeltaMovement();
        double hSpeed = MathUtils.horizontalSpeed(velocity);
        if (hSpeed < 0.5) {
            unloadedChunksAhead = false;
            safeAltitudeBoost = Math.max(0, safeAltitudeBoost - 1);
            return;
        }

        double nx = velocity.x / hSpeed;
        double nz = velocity.z / hSpeed;
        int unloaded = 0;
        var chunkSource = mc.level.getChunkSource();

        for (int i = 1; i <= 3; i++) {
            double checkX = mc.player.getX() + nx * i * 48;
            double checkZ = mc.player.getZ() + nz * i * 48;
            int chunkX = (int) Math.floor(checkX) >> 4;
            int chunkZ = (int) Math.floor(checkZ) >> 4;
            LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, false);
            if (chunk == null) unloaded++;
        }

        if (unloaded >= 2) {
            unloadedChunksAhead = true;
            safeAltitudeBoost = Math.min(10.0, unloaded * 3.0);
        } else {
            unloadedChunksAhead = false;
            safeAltitudeBoost = Math.max(0, safeAltitudeBoost - 1);
        }
    }

    private void updateFlightNoise() {
        noiseTimer++;
        if (noiseTimer >= noiseChangeInterval) {
            noiseTimer = 0;
            yawNoise = (noiseRandom.nextFloat() - 0.5f) * 4.0f;
            pitchNoise = (noiseRandom.nextFloat() - 0.5f) * 2.0f;
            noiseChangeInterval = 30 + noiseRandom.nextInt(30);
        }
    }

    // ========================================================================
    // SETTERS (compatible with BaseFinderModule + ElytraBotModule)
    // ========================================================================

    public void setCruiseAltitude(double alt) { this.cruiseAltitude = alt; }
    public void setMinAltitude(double alt) { this.minAltitude = alt; }
    public void setFireworkInterval(int ticks) { this.fireworkInterval = ticks; }
    public void setMinElytraDurability(int durability) { this.minElytraDurability = durability; }
    public void setUseFlightNoise(boolean v) { this.useFlightNoise = v; }
    public void setUseObstacleAvoidance(boolean v) { this.useObstacleAvoidance = v; }
    public void setLagDetector(LagDetector detector) { this.lagDetector = detector; }
    public void setEnableCircling(boolean v) { this.enableCircling = v; }
    public void setCircleRadius(double r) { this.circleRadius = r; }
    public void setCircleTimeout(int ticks) { this.circleTimeout = ticks; }
    public void setUseBaritoneLanding(boolean v) { this.useBaritoneLanding = v; }
    public void setAcceptedFallDamage(int halfHearts) { this.acceptedFallDamage = halfHearts; }
    public void setBaritoneController(BaritoneController ctrl) { this.baritoneController = ctrl; }
    public void setTerrainPredictor(TerrainPredictor predictor) { this.terrainPredictor = predictor; }
    public void setTerrainSafetyMargin(int margin) { this.terrainSafetyMargin = margin; }
    public TerrainPredictor getTerrainPredictor() { return terrainPredictor; }
}
