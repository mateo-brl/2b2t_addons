package com.basefinder.domain.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof-of-concept: {@link FlightController} runs entirely without Minecraft.
 * These are the first JUnit tests in the BaseFinder codebase — their purpose
 * is to prove the extraction path works before scaling the architecture
 * migration (audit/10-roadmap.md Jalon 1+).
 *
 * We test the scoring invariants directly rather than expected pitch values
 * from the optimiser, because the coarse grid search is sensitive to scoring
 * weight changes — behavioural tests on invariants are stable across tuning.
 */
class FlightControllerTest {

    // Bot cruising east at ~1.5 b/tick (vanilla elytra cruise without firework).
    // yaw = -90 → look vector +X, motion aligned with look.
    private static FlightState cruisingEastAt(double x, double y, double z) {
        return new FlightState(
                x, y, z,
                1.5, 0.0, 0.0,
                0.0f, -90.0f
        );
    }

    private static final TerrainSampler FLAT_SEA_LEVEL = (bx, bz) -> 64;

    @Test
    void planOptimalPitch_returnsValueInsideTuningBounds() {
        FlightController controller = new FlightController();
        FlightState start = cruisingEastAt(0.0, 100.0, 0.0);

        float pitch = controller.planOptimalPitch(start, 100.0, FLAT_SEA_LEVEL);

        FlightTuning t = FlightTuning.DEFAULT;
        assertTrue(pitch >= t.minPitch() && pitch <= t.maxPitch(),
                "pitch " + pitch + " escaped bounds [" + t.minPitch() + ", " + t.maxPitch() + "]");
    }

    @Test
    void scoreCandidate_returnsHardRejectWhenTrajectoryIntersectsTerrain() {
        FlightController controller = new FlightController();
        // Bot skimming 2 blocks above ground, already diving. Any dive pitch
        // guarantees a trajectory tick with clearance < 0.
        FlightState skimming = new FlightState(
                0.0, 66.0, 0.0,
                1.5, -0.3, 0.0,
                10.0f, -90.0f
        );

        double diveScore = controller.scoreCandidate(skimming, 40.0f, 80.0, FLAT_SEA_LEVEL);

        assertEquals(-1_000_000.0, diveScore, 0.0,
                "collision trajectory must trigger hard reject sentinel -1_000_000");
    }

    @Test
    void scoreCandidate_prefersAltitudeAnchoredPitchOverAltitudeDriftingPitch() {
        FlightController controller = new FlightController();
        // Bot at target altitude, flat safe terrain.
        // Max climb (-55°) holds altitude near 100 because lift ~ cancels gravity.
        // Max dive (+45°) drops altitude fast → altError grows → worse score.
        FlightState atTarget = cruisingEastAt(0.0, 100.0, 0.0);

        double climbScore = controller.scoreCandidate(atTarget, -55.0f, 100.0, FLAT_SEA_LEVEL);
        double diveScore  = controller.scoreCandidate(atTarget, 45.0f,  100.0, FLAT_SEA_LEVEL);

        assertTrue(climbScore > diveScore,
                "holding target altitude must beat diving away from it: climb=" + climbScore + " dive=" + diveScore);
    }
}
