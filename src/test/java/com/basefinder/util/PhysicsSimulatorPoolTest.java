package com.basefinder.util;

import com.basefinder.util.PhysicsSimulator.FlightState;
import com.basefinder.util.PhysicsSimulator.FlightStatePool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link PhysicsSimulator#simulateForwardInto} is bit-equivalent
 * to {@link PhysicsSimulator#simulateForward} and that the pool buffer is
 * reused across calls (zero-alloc steady state).
 *
 * Justifies the FlightStatePool refactor in audit/03-performance.md §3.
 */
class PhysicsSimulatorPoolTest {

    private static FlightState cruising() {
        // 1.5 b/tick eastward, level, yaw -90 (look +X)
        return new FlightState(0, 200, 0, 1.5, 0.0, 0.0, 0f, -90f);
    }

    private static FlightState climbing() {
        // descending pitch (looking down) → speed transfer
        return new FlightState(0, 200, 0, 1.0, -0.2, 0.5, 30f, 0f);
    }

    @Test
    void poolVariantMatchesAllocatingVariant_cruise() {
        PhysicsSimulator sim = new PhysicsSimulator();
        FlightStatePool pool = new FlightStatePool();

        FlightState[] reference = sim.simulateForward(cruising(), -10f, -90f, 20, false);
        FlightState[] pooled = sim.simulateForwardInto(cruising(), -10f, -90f, 20, false, pool);

        assertEquals(reference.length, pooled.length);
        for (int i = 0; i < reference.length; i++) {
            assertStateEqual(reference[i], pooled[i], "tick " + i);
        }
    }

    @Test
    void poolVariantMatchesAllocatingVariant_firework() {
        PhysicsSimulator sim = new PhysicsSimulator();
        FlightStatePool pool = new FlightStatePool();

        FlightState[] reference = sim.simulateForward(cruising(), -5f, -90f, 15, true);
        FlightState[] pooled = sim.simulateForwardInto(cruising(), -5f, -90f, 15, true, pool);

        for (int i = 0; i < reference.length; i++) {
            assertStateEqual(reference[i], pooled[i], "tick " + i + " (firework)");
        }
    }

    @Test
    void poolVariantMatchesAllocatingVariant_descending() {
        PhysicsSimulator sim = new PhysicsSimulator();
        FlightStatePool pool = new FlightStatePool();

        FlightState[] reference = sim.simulateForward(climbing(), 30f, 0f, 20, false);
        FlightState[] pooled = sim.simulateForwardInto(climbing(), 30f, 0f, 20, false, pool);

        for (int i = 0; i < reference.length; i++) {
            assertStateEqual(reference[i], pooled[i], "tick " + i + " (descending)");
        }
    }

    @Test
    void poolReusesBufferAcrossCalls() {
        PhysicsSimulator sim = new PhysicsSimulator();
        FlightStatePool pool = new FlightStatePool();

        FlightState[] first = sim.simulateForwardInto(cruising(), -10f, -90f, 20, false, pool);
        FlightState[] second = sim.simulateForwardInto(cruising(), -10f, -90f, 20, false, pool);

        // Same backing array — proof of zero-alloc steady state.
        assertSame(first, second, "pool must reuse the same array reference");
    }

    @Test
    void poolReusesIndividualStatesAcrossCalls() {
        PhysicsSimulator sim = new PhysicsSimulator();
        FlightStatePool pool = new FlightStatePool();

        FlightState[] first = sim.simulateForwardInto(cruising(), -10f, -90f, 20, false, pool);
        FlightState ref0 = first[0];
        FlightState ref10 = first[10];

        FlightState[] second = sim.simulateForwardInto(cruising(), -10f, -90f, 20, false, pool);

        // Pool reuses the FlightState instances themselves: no GC churn per tick.
        assertSame(ref0, second[0]);
        assertSame(ref10, second[10]);
    }

    @Test
    void poolGrowsWhenLargerTrajectoryRequested() {
        PhysicsSimulator sim = new PhysicsSimulator();
        FlightStatePool pool = new FlightStatePool();

        FlightState[] small = sim.simulateForwardInto(cruising(), -10f, -90f, 10, false, pool);
        assertEquals(10, small.length);

        FlightState[] large = sim.simulateForwardInto(cruising(), -10f, -90f, 30, false, pool);
        assertTrue(large.length >= 30, "pool must grow to fit larger trajectories");
        // Different array references because the buffer was grown.
        assertNotSame(small, large);
    }

    @Test
    void allocatingVariantIsUnchanged() {
        PhysicsSimulator sim = new PhysicsSimulator();

        FlightState[] a = sim.simulateForward(cruising(), -10f, -90f, 20, false);
        FlightState[] b = sim.simulateForward(cruising(), -10f, -90f, 20, false);

        // Old API: every call returns a fresh array with fresh state instances.
        assertNotSame(a, b);
        assertNotSame(a[0], b[0]);
        assertStateEqual(a[5], b[5], "deterministic output");
    }

    private static void assertStateEqual(FlightState expected, FlightState actual, String hint) {
        assertEquals(expected.x, actual.x, 1e-12, "x @ " + hint);
        assertEquals(expected.y, actual.y, 1e-12, "y @ " + hint);
        assertEquals(expected.z, actual.z, 1e-12, "z @ " + hint);
        assertEquals(expected.motionX, actual.motionX, 1e-12, "motionX @ " + hint);
        assertEquals(expected.motionY, actual.motionY, 1e-12, "motionY @ " + hint);
        assertEquals(expected.motionZ, actual.motionZ, 1e-12, "motionZ @ " + hint);
        assertEquals(expected.pitch, actual.pitch, 1e-6, "pitch @ " + hint);
        assertEquals(expected.yaw, actual.yaw, 1e-6, "yaw @ " + hint);
    }
}
