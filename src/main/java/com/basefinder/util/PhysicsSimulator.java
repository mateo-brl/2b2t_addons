package com.basefinder.util;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Simulates Minecraft's elytra flight physics tick-by-tick.
 * Used to predict future positions and verify safety BEFORE applying rotations.
 *
 * Based on vanilla LivingEntity.travel() elytra branch.
 */
public class PhysicsSimulator {

    // Vanilla elytra physics constants
    public static final double GRAVITY = 0.05;
    public static final double DRAG_HORIZONTAL = 0.99;
    public static final double DRAG_VERTICAL = 0.98;
    public static final double LIFT_COEFF = 0.04;
    public static final double FIREWORK_BOOST_FACTOR = 0.1;
    public static final double TERMINAL_VELOCITY = 3.92;

    // Safety thresholds
    /** motionY below which fall damage occurs on ground contact. */
    public static final double FALL_DAMAGE_MOTION_THRESHOLD = -0.5;

    /**
     * Snapshot of flight state at a given tick.
     */
    public static class FlightState {
        public double x, y, z;
        public double motionX, motionY, motionZ;
        public float pitch, yaw;

        public FlightState(double x, double y, double z,
                           double motionX, double motionY, double motionZ,
                           float pitch, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        public static FlightState fromPlayer(Player player) {
            Vec3 m = player.getDeltaMovement();
            return new FlightState(
                    player.getX(), player.getY(), player.getZ(),
                    m.x, m.y, m.z,
                    player.getXRot(), player.getYRot()
            );
        }

        public FlightState copy() {
            return new FlightState(x, y, z, motionX, motionY, motionZ, pitch, yaw);
        }

        public double getHorizontalSpeed() {
            return Math.sqrt(motionX * motionX + motionZ * motionZ);
        }

        public double getTotalSpeed() {
            return Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
        }

        public Vec3 getPosition() {
            return new Vec3(x, y, z);
        }

        public Vec3 getMotion() {
            return new Vec3(motionX, motionY, motionZ);
        }
    }

    /**
     * Simulate one tick of elytra physics.
     *
     * @param state Flight state (mutated in place)
     * @param targetPitch Target pitch (degrees)
     * @param targetYaw Target yaw (degrees)
     * @param fireworkBoost Whether a firework is boosting this tick
     * @return The same state reference (mutated)
     */
    public FlightState simulateTick(FlightState state, float targetPitch, float targetYaw, boolean fireworkBoost) {
        state.pitch = targetPitch;
        state.yaw = targetYaw;

        double pitchRad = Math.toRadians(state.pitch);
        double yawRad = Math.toRadians(state.yaw);

        // Look direction
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double hSpeed = state.getHorizontalSpeed();

        // Lift from looking up while moving horizontally
        if (state.pitch < 0 && hSpeed > 0.001) {
            double lift = hSpeed * (-Math.sin(pitchRad)) * LIFT_COEFF;
            state.motionY += lift;
        }

        // Speed redistribution: looking down converts vertical to horizontal
        double cosPitch = Math.cos(pitchRad);
        double cosPitchSq = cosPitch * cosPitch;
        if (hSpeed > 0.001 && state.pitch > 0) {
            double speedTransfer = (-state.motionY * 0.1) * cosPitchSq;
            if (speedTransfer > 0) {
                state.motionX += lookX * speedTransfer;
                state.motionZ += lookZ * speedTransfer;
            }
        }

        // Gravity
        state.motionY -= GRAVITY;

        // Drag
        state.motionX *= DRAG_HORIZONTAL;
        state.motionZ *= DRAG_HORIZONTAL;
        state.motionY *= DRAG_VERTICAL;

        // Firework boost
        if (fireworkBoost) {
            state.motionX += lookX * FIREWORK_BOOST_FACTOR + (lookX * 1.5 - state.motionX) * 0.5;
            state.motionY += lookY * FIREWORK_BOOST_FACTOR + (lookY * 1.5 - state.motionY) * 0.5;
            state.motionZ += lookZ * FIREWORK_BOOST_FACTOR + (lookZ * 1.5 - state.motionZ) * 0.5;
        }

        // Terminal velocity cap
        if (state.motionY < -TERMINAL_VELOCITY) {
            state.motionY = -TERMINAL_VELOCITY;
        }

        // Update position
        state.x += state.motionX;
        state.y += state.motionY;
        state.z += state.motionZ;

        return state;
    }

    /**
     * Simulate N ticks forward, returning all intermediate states.
     */
    public FlightState[] simulateForward(FlightState initial, float pitch, float yaw,
                                          int ticks, boolean fireworkBoost) {
        FlightState[] states = new FlightState[ticks];
        FlightState current = initial.copy();

        for (int i = 0; i < ticks; i++) {
            // Firework only applies on first tick
            simulateTick(current, pitch, yaw, fireworkBoost && i == 0);
            states[i] = current.copy();
        }
        return states;
    }

    /** Check if vertical speed would cause fall damage on ground contact. */
    public static boolean wouldCauseFallDamage(double motionY) {
        return motionY < FALL_DAMAGE_MOTION_THRESHOLD;
    }

    /** Estimate fall damage from impact speed. */
    public static double estimateFallDamage(double motionY) {
        if (motionY >= FALL_DAMAGE_MOTION_THRESHOLD) return 0;
        return (Math.abs(motionY) - Math.abs(FALL_DAMAGE_MOTION_THRESHOLD)) * 20.0;
    }

    /** Player total speed. */
    public static double getSpeed(Player player) {
        return player.getDeltaMovement().length();
    }

    /** Player horizontal speed. */
    public static double getHorizontalSpeed(Player player) {
        Vec3 m = player.getDeltaMovement();
        return Math.sqrt(m.x * m.x + m.z * m.z);
    }
}
