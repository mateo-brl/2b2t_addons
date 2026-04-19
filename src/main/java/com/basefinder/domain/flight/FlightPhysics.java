package com.basefinder.domain.flight;

/**
 * Pure vanilla-faithful elytra physics simulator.
 *
 * Mirrors {@code LivingEntity.travel()} elytra branch (MC 1.21.4).
 * No Minecraft dependency — tests drive this directly.
 *
 * Constants duplicate {@link com.basefinder.util.PhysicsSimulator} on purpose:
 * the legacy simulator imports {@code net.minecraft.world.entity.player.Player}
 * via its {@code FlightState.fromPlayer} helper. Consolidation is scheduled
 * for migration step 8 (see audit/10-roadmap.md, Jalon 3) — this class is the
 * domain-pure target, the legacy one is the adapter surface.
 */
public final class FlightPhysics {

    public static final double GRAVITY = 0.05;
    public static final double DRAG_HORIZONTAL = 0.99;
    public static final double DRAG_VERTICAL = 0.98;
    public static final double LIFT_COEFF = 0.04;
    public static final double FIREWORK_BOOST_FACTOR = 0.1;
    public static final double TERMINAL_VELOCITY = 3.92;
    public static final double FALL_DAMAGE_MOTION_THRESHOLD = -0.5;

    private FlightPhysics() {}

    /**
     * Simulate one tick of elytra physics and return the resulting state.
     * Pure function: allocates one new {@link FlightState}.
     */
    public static FlightState simulateTick(FlightState state,
                                           float targetPitch, float targetYaw,
                                           boolean fireworkBoost) {
        double pitchRad = Math.toRadians(targetPitch);
        double yawRad = Math.toRadians(targetYaw);

        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double mx = state.motionX();
        double my = state.motionY();
        double mz = state.motionZ();
        double hSpeed = Math.sqrt(mx * mx + mz * mz);

        if (targetPitch < 0 && hSpeed > 0.001) {
            my += hSpeed * (-Math.sin(pitchRad)) * LIFT_COEFF;
        }

        double cosPitch = Math.cos(pitchRad);
        double cosPitchSq = cosPitch * cosPitch;
        if (hSpeed > 0.001 && targetPitch > 0) {
            double speedTransfer = (-my * 0.1) * cosPitchSq;
            if (speedTransfer > 0) {
                mx += lookX * speedTransfer;
                mz += lookZ * speedTransfer;
            }
        }

        my -= GRAVITY;

        mx *= DRAG_HORIZONTAL;
        mz *= DRAG_HORIZONTAL;
        my *= DRAG_VERTICAL;

        if (fireworkBoost) {
            mx += lookX * FIREWORK_BOOST_FACTOR + (lookX * 1.5 - mx) * 0.5;
            my += lookY * FIREWORK_BOOST_FACTOR + (lookY * 1.5 - my) * 0.5;
            mz += lookZ * FIREWORK_BOOST_FACTOR + (lookZ * 1.5 - mz) * 0.5;
        }

        if (my < -TERMINAL_VELOCITY) {
            my = -TERMINAL_VELOCITY;
        }

        return new FlightState(
                state.x() + mx, state.y() + my, state.z() + mz,
                mx, my, mz,
                targetPitch, targetYaw
        );
    }

    /**
     * Simulate N ticks forward with fixed pitch/yaw. Firework boost (if any)
     * applies only on the first tick, matching vanilla behaviour.
     * Returns an array of length {@code ticks}.
     */
    public static FlightState[] simulateForward(FlightState initial,
                                                float pitch, float yaw,
                                                int ticks, boolean fireworkBoost) {
        FlightState[] states = new FlightState[ticks];
        FlightState current = initial;
        for (int i = 0; i < ticks; i++) {
            current = simulateTick(current, pitch, yaw, fireworkBoost && i == 0);
            states[i] = current;
        }
        return states;
    }

    public static boolean wouldCauseFallDamage(double motionY) {
        return motionY < FALL_DAMAGE_MOTION_THRESHOLD;
    }
}
