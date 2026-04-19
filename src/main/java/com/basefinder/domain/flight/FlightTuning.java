package com.basefinder.domain.flight;

/**
 * Flight control tuning parameters.
 *
 * {@link #DEFAULT} mirrors the values hardcoded in {@code ElytraBot} before
 * extraction (HEAD 7d41d32). Tests can pass different values to probe corner
 * cases without mutating global state.
 */
public record FlightTuning(
        int simulationTicks,
        int pitchCandidates,
        float minPitch,
        float maxPitch,
        double safetyClearance,
        double stallSpeed,
        double smoothnessPenalty
) {

    public static final FlightTuning DEFAULT = new FlightTuning(
            40,     // simulationTicks
            21,     // pitchCandidates
            -55.0f, // minPitch
            45.0f,  // maxPitch
            3.0,    // safetyClearance
            0.3,    // stallSpeed
            2.5     // smoothnessPenalty (per degree of pitch change)
    );
}
