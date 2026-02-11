package com.basefinder.elytra;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
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

    public enum FlightState {
        IDLE,
        TAKING_OFF,
        CLIMBING,
        CRUISING,
        DESCENDING,
        LANDING,
        REFUELING // Looking for fireworks in inventory
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
            case LANDING -> handleLanding();
            case REFUELING -> handleRefueling();
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

    // ===== FLIGHT STATE HANDLERS =====

    private void handleTakeoff() {
        if (mc.player == null) return;

        takeoffTimer++;

        // Already flying? Go to climbing
        if (mc.player.isFallFlying()) {
            ChatUtils.print("[ElytraBot] " + Lang.t("Elytra deployed! Climbing...", "Elytra déployé ! Montée..."));
            state = FlightState.CLIMBING;
            takeoffTimer = 0;
            return;
        }

        // Phase 1: Jump to get airborne
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
            return;
        }

        // Phase 2: Once in air and falling, send elytra activation packet
        if (!mc.player.onGround() && !mc.player.isFallFlying() && mc.player.getDeltaMovement().y < 0) {
            // Send the START_FALL_FLYING packet to deploy elytra
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                        mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                ));
            }
        }

        // Timeout - retry the whole cycle
        if (takeoffTimer > 40) {
            takeoffTimer = 0;
            LOGGER.info("[ElytraBot] Takeoff retry...");
        }
    }

    private void handleClimbing() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            LOGGER.info("[ElytraBot] Lost flight during climbing, going back to takeoff");
            state = FlightState.TAKING_OFF;
            takeoffTimer = 0;
            return;
        }

        // Pitch up and use fireworks to gain altitude
        targetPitch = -45.0f;
        applyRotation();
        useFireworkIfNeeded();

        if (mc.player.getY() >= cruiseAltitude) {
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
            return;
        }

        // Update yaw towards destination
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }

        // Maintain altitude (with noise variation for anti-detection)
        double effectiveCruise = cruiseAltitude + (useFlightNoise ? altitudeNoise : 0);
        double y = mc.player.getY();
        if (y < effectiveCruise - 10) {
            targetPitch = -20.0f; // nose up
            useFireworkIfNeeded();
        } else if (y > effectiveCruise + 10) {
            targetPitch = 10.0f; // nose down slightly
        } else {
            targetPitch = -2.0f; // cruise
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
            if (distXZ < 50) {
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
            return;
        }

        // Gentle descent - aim for safe landing
        targetPitch = 15.0f; // nose down gently
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }
        applyRotation();

        // Near ground - slow down
        if (mc.player.getY() < 80) {
            targetPitch = 5.0f; // flatten out to slow descent
        }

        if (mc.player.getY() < minAltitude || mc.player.onGround()) {
            state = FlightState.LANDING;
        }
    }

    private void handleLanding() {
        // Just let the player land naturally
        if (mc.player != null && mc.player.onGround()) {
            state = FlightState.IDLE;
            isFlying = false;
            ChatUtils.print("[ElytraBot] " + Lang.t("Landed.", "Atterri."));
        }
    }

    private void handleRefueling() {
        // Try to find fireworks in inventory and switch to hotbar
        int fireworkSlot = findFireworkSlot();
        if (fireworkSlot >= 0) {
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

    // ===== FIREWORK MANAGEMENT =====

    /**
     * Use a firework rocket if cooldown allows.
     * Uses a 2-tick delay between slot switch and use to allow server sync.
     */
    private int pendingFireworkSlot = -1;
    private int pendingFireworkDelay = 0;
    private int previousSlotBeforeFirework = -1;

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
            }
            return;
        }

        int slot = findFireworkInHotbar();
        if (slot < 0) {
            // Try to move firework from inventory to hotbar
            int invSlot = findFireworkSlot();
            if (invSlot >= 0) {
                // Swap to hotbar slot 8
                InventoryUtils.swapSlots(invSlot, 44); // slot 44 = hotbar slot 8 (index 8)
                slot = 8;
            } else {
                return;
            }
        }

        // Switch to firework slot and schedule use for next tick
        previousSlotBeforeFirework = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
        pendingFireworkSlot = slot;
        pendingFireworkDelay = 1; // Wait 1 tick for server to sync
    }

    private int findFireworkInHotbar() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) return i;
        }
        return -1;
    }

    private int findFireworkSlot() {
        return InventoryUtils.findItem(Items.FIREWORK_ROCKET, true, false);
    }

    private boolean hasFireworks() {
        return findFireworkSlot() >= 0 || findFireworkInHotbar() >= 0;
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

        float noiseY = useFlightNoise ? yawNoise : 0;
        float noiseP = useFlightNoise ? pitchNoise : 0;

        float yawDiff = wrapDegrees((targetYaw + noiseY) - currentYaw);
        float pitchDiff = (targetPitch + noiseP) - currentPitch;

        float yawStep = Math.min(Math.abs(yawDiff), 5.0f) * Math.signum(yawDiff);
        float pitchStep = Math.min(Math.abs(pitchDiff), 3.0f) * Math.signum(pitchDiff);

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

    public int getFireworkCount() {
        int count = 0;
        if (mc.player != null) {
            count = InventoryUtils.getItemCount(Items.FIREWORK_ROCKET, true, false);
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
