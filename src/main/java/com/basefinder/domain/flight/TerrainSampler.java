package com.basefinder.domain.flight;

/**
 * Port: samples ground surface height at an integer world column.
 *
 * Production implementation lives in {@code adapter/mc/} and bridges to
 * {@code TerrainPredictor} + {@code ClientLevel#getHeight(WORLD_SURFACE, x, z)}.
 * Tests provide pure lambdas like {@code (x, z) -> 64}.
 */
@FunctionalInterface
public interface TerrainSampler {
    int heightAt(int blockX, int blockZ);
}
