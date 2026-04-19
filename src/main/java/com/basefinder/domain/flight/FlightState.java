package com.basefinder.domain.flight;

/**
 * Immutable snapshot of elytra flight state at a given tick.
 *
 * Pure domain type: no Minecraft import. Construct via
 * {@code new FlightState(...)} from an adapter (e.g. {@code McFlightStateAdapter}).
 *
 * Mutable tick simulation is available in {@link FlightPhysics#simulateTickInPlace}
 * for hot paths that want to avoid allocations; the immutable variant
 * {@link FlightPhysics#simulateTick} is preferred for domain code and tests.
 */
public record FlightState(
        double x, double y, double z,
        double motionX, double motionY, double motionZ,
        float pitch, float yaw
) {

    public double horizontalSpeed() {
        return Math.sqrt(motionX * motionX + motionZ * motionZ);
    }

    public double totalSpeed() {
        return Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
    }

    public FlightState withPosition(double newX, double newY, double newZ) {
        return new FlightState(newX, newY, newZ, motionX, motionY, motionZ, pitch, yaw);
    }

    public FlightState withMotion(double newMx, double newMy, double newMz) {
        return new FlightState(x, y, z, newMx, newMy, newMz, pitch, yaw);
    }

    public FlightState withRotation(float newPitch, float newYaw) {
        return new FlightState(x, y, z, motionX, motionY, motionZ, newPitch, newYaw);
    }
}
