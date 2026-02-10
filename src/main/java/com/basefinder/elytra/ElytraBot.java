package com.basefinder.elytra;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.utils.InventoryUtils;

/**
 * Automated elytra flight controller.
 * Handles takeoff, cruise altitude, firework boosting, and landing.
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

        // Process pending firework use (delayed slot sync)
        if (pendingFireworkSlot >= 0) {
            useFireworkIfNeeded();
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
            LOGGER.info("[ElytraBot] State: {}, isFallFlying: {}, onGround: {}, Y: {:.1f}, Dest: {}",
                state, mc.player.isFallFlying(), mc.player.onGround(),
                mc.player.getY(), destination != null ? destination.toShortString() : "none");
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
    }

    private void handleTakeoff() {
        if (mc.player == null) return;

        takeoffTimer++;

        // Already flying? Go to climbing
        if (mc.player.isFallFlying()) {
            org.rusherhack.client.api.utils.ChatUtils.print("[ElytraBot] Elytra active! Taking control...");
            state = FlightState.CLIMBING;
            takeoffTimer = 0;
            return;
        }

        // Just wait for player to deploy elytra manually
        // Once flying, bot takes over
        if (takeoffTimer == 1) {
            org.rusherhack.client.api.utils.ChatUtils.print("[ElytraBot] Deploy your elytra manually (double-tap SPACE while falling)");
            org.rusherhack.client.api.utils.ChatUtils.print("[ElytraBot] Once flying, I'll take control automatically!");
        }

        // Periodic reminder
        if (takeoffTimer % 100 == 0) {
            org.rusherhack.client.api.utils.ChatUtils.print("[ElytraBot] Still waiting for elytra... Double-tap SPACE while in air!");
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
            org.rusherhack.client.api.utils.ChatUtils.print("[ElytraBot] Cruising at altitude " + (int)cruiseAltitude);
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

        // Maintain altitude
        double y = mc.player.getY();
        if (y < cruiseAltitude - 10) {
            targetPitch = -20.0f; // nose up
            useFireworkIfNeeded();
        } else if (y > cruiseAltitude + 10) {
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

        // Check firework supply
        if (!hasFireworks()) {
            state = FlightState.REFUELING;
        }
    }

    private void handleDescending() {
        if (mc.player == null) return;

        if (!mc.player.isFallFlying()) {
            state = FlightState.LANDING;
            return;
        }

        targetPitch = 30.0f; // nose down
        if (destination != null) {
            targetYaw = calculateYawToTarget(destination);
        }
        applyRotation();

        if (mc.player.getY() < minAltitude || mc.player.onGround()) {
            state = FlightState.LANDING;
        }
    }

    private void handleLanding() {
        // Just let the player land naturally
        if (mc.player != null && mc.player.onGround()) {
            state = FlightState.IDLE;
            isFlying = false;
        }
    }

    private void handleRefueling() {
        // Try to find fireworks in inventory and switch to hotbar
        int fireworkSlot = findFireworkSlot();
        if (fireworkSlot >= 0) {
            state = FlightState.CRUISING;
        } else {
            // No fireworks, try to glide
            targetPitch = -5.0f;
            applyRotation();
            if (mc.player != null && mc.player.getY() < minAltitude) {
                state = FlightState.LANDING;
            }
        }
    }

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

    private void applyRotation() {
        if (mc.player == null) return;
        // Smoothly interpolate rotation
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float yawDiff = wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

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

    // Getters/Setters
    public boolean isFlying() { return isFlying; }
    public FlightState getState() { return state; }
    public BlockPos getDestination() { return destination; }

    public void setCruiseAltitude(double alt) { this.cruiseAltitude = alt; }
    public void setMinAltitude(double alt) { this.minAltitude = alt; }
    public void setFireworkInterval(int ticks) { this.fireworkInterval = ticks; }
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
