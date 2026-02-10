package com.basefinder.util;

/**
 * Simple 2D double vector for XZ navigation.
 */
public record Vec2d(double x, double z) {

    public double distanceTo(Vec2d other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vec2d normalize() {
        double len = Math.sqrt(x * x + z * z);
        if (len == 0) return new Vec2d(0, 0);
        return new Vec2d(x / len, z / len);
    }

    public Vec2d add(Vec2d other) {
        return new Vec2d(x + other.x, z + other.z);
    }

    public Vec2d multiply(double scalar) {
        return new Vec2d(x * scalar, z * scalar);
    }

    public double angleTo(Vec2d other) {
        return Math.toDegrees(Math.atan2(other.z - z, other.x - x));
    }
}
