package com.basefinder.adapter.io.zones;

import com.basefinder.domain.zone.SearchZone;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZonePollerTest {

    @Test
    void parse_circle() {
        String body = """
                {
                  "total": 1,
                  "zones": [
                    {
                      "id": 7,
                      "name": "stash belt",
                      "dim": "overworld",
                      "shape": "Circle",
                      "active": true,
                      "geometry": {
                        "type": "Circle",
                        "coordinates": { "centerX": 1000, "centerZ": -2000, "radius": 500 }
                      },
                      "createdAt": 0, "updatedAt": 0
                    }
                  ]
                }
                """;
        List<SearchZone> zones = ZonePoller.parse(body);
        assertEquals(1, zones.size());
        SearchZone z = zones.get(0);
        assertEquals(SearchZone.Type.CIRCLE, z.type);
        assertEquals("overworld", z.dimension);
        assertTrue(z.contains(1000, -2000));
        assertTrue(z.contains(1499, -2000));
    }

    @Test
    void parse_polygon() {
        String body = """
                {
                  "total": 1,
                  "zones": [
                    {
                      "id": 9,
                      "name": "rectangle",
                      "dim": "nether",
                      "shape": "Rectangle",
                      "active": true,
                      "geometry": {
                        "type": "Polygon",
                        "coordinates": [
                          [[-100, -100], [100, -100], [100, 100], [-100, 100], [-100, -100]]
                        ]
                      },
                      "createdAt": 0, "updatedAt": 0
                    }
                  ]
                }
                """;
        List<SearchZone> zones = ZonePoller.parse(body);
        assertEquals(1, zones.size());
        SearchZone z = zones.get(0);
        assertEquals(SearchZone.Type.POLYGON, z.type);
        assertEquals("nether", z.dimension);
        assertTrue(z.contains(0, 0));
    }

    @Test
    void parse_skipsMalformed() {
        String body = """
                {
                  "total": 2,
                  "zones": [
                    { "id": 1, "name": "ok", "dim": "overworld", "shape": "Circle", "active": true,
                      "geometry": { "type": "Circle", "coordinates": { "centerX": 0, "centerZ": 0, "radius": 100 } } },
                    { "id": 2, "name": "bad", "dim": "overworld", "shape": "???", "active": true,
                      "geometry": { "type": "Unsupported", "coordinates": null } }
                  ]
                }
                """;
        List<SearchZone> zones = ZonePoller.parse(body);
        assertEquals(1, zones.size(), "malformed zones must be skipped, not crash");
    }
}
