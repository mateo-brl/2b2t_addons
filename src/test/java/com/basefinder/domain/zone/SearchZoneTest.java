package com.basefinder.domain.zone;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchZoneTest {

    @Test
    void circleContains_centerAndEdge() {
        SearchZone z = SearchZone.circle(1, "z", "overworld", true,
                new SearchZone.Circle(1000, 2000, 500));
        assertTrue(z.contains(1000, 2000));
        assertTrue(z.contains(1499, 2000));   // inside (just under radius)
        assertTrue(z.contains(1000, 2500));   // on edge
        assertFalse(z.contains(1501, 2000));  // outside
    }

    @Test
    void polygonContains_axisAlignedSquare() {
        // 1000-block square centered at origin
        List<double[]> ring = Arrays.asList(
                new double[]{-500, -500},
                new double[]{500, -500},
                new double[]{500, 500},
                new double[]{-500, 500},
                new double[]{-500, -500} // closed
        );
        SearchZone z = SearchZone.polygon(2, "z", "overworld", true,
                new SearchZone.Polygon(ring));
        assertTrue(z.contains(0, 0));
        assertTrue(z.contains(-499, -499));
        assertFalse(z.contains(501, 0));
        assertFalse(z.contains(0, 501));
    }

    @Test
    void polygonContains_concaveLShape() {
        List<double[]> ring = Arrays.asList(
                new double[]{0, 0},
                new double[]{200, 0},
                new double[]{200, 100},
                new double[]{100, 100},
                new double[]{100, 200},
                new double[]{0, 200},
                new double[]{0, 0}
        );
        SearchZone z = SearchZone.polygon(3, "z", "overworld", true,
                new SearchZone.Polygon(ring));
        assertTrue(z.contains(50, 50));
        assertTrue(z.contains(150, 50));
        assertFalse(z.contains(150, 150)); // notch is excluded
    }

    @Test
    void zoneFilter_emptyMeansAllowAll() {
        ZoneFilter f = new ZoneFilter();
        assertTrue(f.allows("overworld", 0, 0));
        assertTrue(f.allows("nether", 12345, -67890));
    }

    @Test
    void zoneFilter_restrictsWhenZoneExistsForDim() {
        ZoneFilter f = new ZoneFilter();
        f.setZones(List.of(SearchZone.circle(
                1, "z", "overworld", true,
                new SearchZone.Circle(0, 0, 1000))));
        // chunk center = chunk*16+8
        // chunk (0,0) → (8,8) inside → allowed
        assertTrue(f.allows("overworld", 0, 0));
        // chunk (200,200) → (3208, 3208) outside → not allowed
        assertFalse(f.allows("overworld", 200, 200));
        // No nether zones → unconstrained for nether
        assertTrue(f.allows("nether", 200, 200));
    }

    @Test
    void zoneFilter_inactiveZoneIsIgnored() {
        ZoneFilter f = new ZoneFilter();
        f.setZones(List.of(SearchZone.circle(
                1, "z", "overworld", false,
                new SearchZone.Circle(0, 0, 100))));
        // Even out of the only (inactive) zone, allows = true because no
        // active zone restricts the dim.
        assertTrue(f.allows("overworld", 999, 999));
    }
}
