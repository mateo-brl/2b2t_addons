package com.basefinder.util;

import net.minecraft.world.phys.Vec3;

/**
 * Mathematical utility helpers for flight calculations.
 */
public final class MathUtils {

    private MathUtils() {}

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp(t, 0.0, 1.0);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp(t, 0.0f, 1.0f);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Normalize angle to [-180, 180]. */
    public static float wrapAngle(float angle) {
        angle = angle % 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    /** Yaw from a direction vector (degrees). */
    public static float yawFromDirection(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Pitch from a direction vector (degrees). */
    public static float pitchFromDirection(double dx, double dy, double dz) {
        double hDist = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(dy, hDist)));
    }

    public static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double horizontalSpeed(Vec3 motion) {
        return Math.sqrt(motion.x * motion.x + motion.z * motion.z);
    }

    public static double totalSpeed(Vec3 motion) {
        return motion.length();
    }

    /**
     * Direction vector from pitch/yaw (Minecraft convention).
     * pitch positive = looking down, yaw 0 = south (+Z).
     */
    public static Vec3 directionFromAngles(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosP = Math.cos(pitchRad);
        return new Vec3(
                -Math.sin(yawRad) * cosP,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosP
        );
    }
}
