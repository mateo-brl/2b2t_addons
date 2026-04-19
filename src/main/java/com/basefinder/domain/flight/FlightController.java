package com.basefinder.domain.flight;

/**
 * Pure domain: decides the optimal pitch for the next tick.
 *
 * Extracted from {@code ElytraBot.calculateOptimalPitch} and
 * {@code ElytraBot.evaluatePitchCandidate} (HEAD 7d41d32, L335-416).
 * Scoring policy is unchanged: terrain clearance first, altitude proximity,
 * speed maintenance, fall-damage guard, smoothness bonus.
 *
 * Stateless and thread-safe. Inject via composition root.
 * See audit/05-target-architecture.md §5 step 1 and audit/10-roadmap.md Jalon 0.
 */
public final class FlightController {

    private final FlightTuning tuning;

    public FlightController() {
        this(FlightTuning.DEFAULT);
    }

    public FlightController(FlightTuning tuning) {
        this.tuning = tuning;
    }

    /**
     * Find the pitch that maximises the scoring policy for the next
     * {@code simulationTicks} ticks, given a target altitude and a terrain sampler.
     *
     * Coarse grid (size {@code pitchCandidates}) then a local refine step
     * (+/- 4 degrees, 1-degree resolution) around the coarse best.
     *
     * @return pitch in degrees, clamped to {@code [minPitch, maxPitch]}
     */
    public float planOptimalPitch(FlightState current, double targetAlt, TerrainSampler terrain) {
        float bestPitch = current.pitch();
        double bestScore = Double.NEGATIVE_INFINITY;

        int candidates = tuning.pitchCandidates();
        float min = tuning.minPitch();
        float max = tuning.maxPitch();
        float span = max - min;

        for (int i = 0; i < candidates; i++) {
            float candidate = min + span * ((float) i / (candidates - 1));
            double score = evaluateCandidate(current, candidate, targetAlt, terrain);
            if (score > bestScore) {
                bestScore = score;
                bestPitch = candidate;
            }
        }

        float refined = bestPitch;
        double refinedScore = bestScore;
        for (float delta = -4.0f; delta <= 4.0f; delta += 1.0f) {
            float candidate = clamp(bestPitch + delta, min, max);
            double score = evaluateCandidate(current, candidate, targetAlt, terrain);
            if (score > refinedScore) {
                refinedScore = score;
                refined = candidate;
            }
        }

        return refined;
    }

    /**
     * Expose the scoring function for tests that want to probe specific candidates
     * without relying on the search grid.
     */
    public double scoreCandidate(FlightState current, float candidatePitch,
                                 double targetAlt, TerrainSampler terrain) {
        return evaluateCandidate(current, candidatePitch, targetAlt, terrain);
    }

    private double evaluateCandidate(FlightState current, float pitch,
                                     double targetAlt, TerrainSampler terrain) {
        FlightState[] trajectory = FlightPhysics.simulateForward(
                current, pitch, current.yaw(), tuning.simulationTicks(), false);

        double score = 0.0;
        int len = trajectory.length;

        for (int i = 0; i < len; i++) {
            FlightState s = trajectory[i];
            double weight = 1.0 - ((double) i / len);

            int groundY = terrain.heightAt((int) s.x(), (int) s.z());
            double clearance = s.y() - groundY;

            if (clearance < 0) {
                return -1_000_000.0;
            }
            if (clearance < tuning.safetyClearance()) {
                score -= (tuning.safetyClearance() - clearance) * 500.0 * weight;
            } else {
                score += Math.min(clearance, 30.0) * weight;
            }

            double altError = Math.abs(s.y() - targetAlt);
            score -= altError * 8.0 * weight;

            double speed = s.totalSpeed();
            if (speed < tuning.stallSpeed()) {
                score -= 300.0 * weight;
            } else if (speed > 0.5 && speed < 2.5) {
                score += 15.0 * weight;
            }

            if (clearance < 6.0 && FlightPhysics.wouldCauseFallDamage(s.motionY())) {
                score -= 5000.0 * weight;
            }
        }

        double pitchChange = Math.abs(pitch - current.pitch());
        score -= pitchChange * tuning.smoothnessPenalty();

        return score;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
