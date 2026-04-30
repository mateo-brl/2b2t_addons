package com.basefinder.domain.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests purs du ViewModel : aucune dépendance Minecraft, on construit un VM
 * avec des données arbitraires et on vérifie son immutabilité et ses constantes.
 */
class BaseFinderViewModelTest {

    @Test
    void inactive_singletonHasExpectedShape() {
        BaseFinderViewModel vm = BaseFinderViewModel.INACTIVE;

        assertFalse(vm.active());
        assertEquals("IDLE", vm.stateName());
        assertSame(BaseFinderViewModel.FlightVm.OFF, vm.flight());
        assertNull(vm.terrain());
        assertSame(BaseFinderViewModel.ScanVm.EMPTY, vm.scan());
        assertSame(BaseFinderViewModel.NavigationVm.EMPTY, vm.navigation());
        assertNull(vm.trail());
        assertSame(BaseFinderViewModel.SurvivalVm.EMPTY, vm.survival());
        assertSame(BaseFinderViewModel.LagVm.OK, vm.lag());
        assertSame(BaseFinderViewModel.PlayerVm.UNKNOWN, vm.player());
    }

    @Test
    void flightOff_hasCanonicalDefaults() {
        BaseFinderViewModel.FlightVm off = BaseFinderViewModel.FlightVm.OFF;

        assertFalse(off.flying());
        assertEquals("IDLE", off.stateName());
        assertEquals(-1.0, off.destinationDistance());
        assertEquals(0, off.fireworkCount());
        assertFalse(off.circling());
        assertFalse(off.unloadedChunksAhead());
    }

    @Test
    void buildActiveSnapshot_assemblesCorrectly() {
        BaseFinderViewModel vm = new BaseFinderViewModel(
                true,
                "FLYING_TO_WAYPOINT",
                new BaseFinderViewModel.FlightVm(true, "CRUISING", 1500.0, 8, false, 0, false),
                new BaseFinderViewModel.TerrainVm(150, "seed"),
                new BaseFinderViewModel.ScanVm(1234, 5, 7),
                new BaseFinderViewModel.NavigationVm(true, 42, 500, 8.4, 12345.6),
                new BaseFinderViewModel.TrailVm("ICE", 25),
                new BaseFinderViewModel.SurvivalVm(8, 3600, false, false),
                new BaseFinderViewModel.LagVm(19.8, false),
                new BaseFinderViewModel.PlayerVm(true, 1500, 200, -2400, "overworld", 18)
        );

        assertEquals(true, vm.active());
        assertEquals("FLYING_TO_WAYPOINT", vm.stateName());
        assertEquals(8, vm.flight().fireworkCount());
        assertEquals(150, vm.terrain().predictedMaxHeight());
        assertEquals(1234, vm.scan().scannedCount());
        assertEquals(7, vm.scan().basesFound());
        assertEquals(42, vm.navigation().waypointIndex());
        assertEquals("ICE", vm.trail().trailType());
        assertEquals(8, vm.survival().totemCount());
        assertEquals(19.8, vm.lag().estimatedTps());
        assertEquals(200, vm.player().y());
    }

    @Test
    void inactiveSingleton_isReused() {
        // Vérifie que INACTIVE est bien un singleton — utile pour limiter les allocations
        // côté HUD quand le module est OFF
        BaseFinderViewModel a = BaseFinderViewModel.INACTIVE;
        BaseFinderViewModel b = BaseFinderViewModel.INACTIVE;
        assertSame(a, b);
    }
}
