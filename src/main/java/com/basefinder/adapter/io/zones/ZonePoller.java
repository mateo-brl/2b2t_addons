package com.basefinder.adapter.io.zones;

import com.basefinder.domain.zone.SearchZone;
import com.basefinder.domain.zone.ZoneFilter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Poll périodique de {@code GET /v1/zones} sur le backend. Met à jour
 * un {@link ZoneFilter} partagé que le scanner consulte.
 *
 * Daemon thread pour ne pas empêcher la JVM de s'arrêter. Échecs HTTP
 * silencieux (le filtre garde l'ancienne valeur) ; en cas de timeout
 * répété la dernière liste connue continue d'être appliquée.
 */
public final class ZonePoller {

    private static final Logger LOGGER = LoggerFactory.getLogger("ZonePoller");

    private final String backendUrl;
    private final ZoneFilter zoneFilter;
    private final long pollIntervalSeconds;
    private final HttpClient http;
    private ScheduledExecutorService scheduler;

    public ZonePoller(String backendUrl, ZoneFilter zoneFilter) {
        this(backendUrl, zoneFilter, 5);
    }

    public ZonePoller(String backendUrl, ZoneFilter zoneFilter, long pollIntervalSeconds) {
        this.backendUrl = backendUrl.endsWith("/")
                ? backendUrl.substring(0, backendUrl.length() - 1)
                : backendUrl;
        this.zoneFilter = zoneFilter;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BaseFinder-ZonePoller");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollOnce, 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
    }

    private void pollOnce() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(backendUrl + "/v1/zones"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                LOGGER.debug("[ZonePoller] HTTP {} polling zones", res.statusCode());
                return;
            }
            List<SearchZone> zones = parse(res.body());
            zoneFilter.setZones(zones);
            LOGGER.debug("[ZonePoller] Loaded {} zones", zones.size());
        } catch (Exception e) {
            LOGGER.debug("[ZonePoller] Poll failed: {}", e.getMessage());
        }
    }

    static List<SearchZone> parse(String body) {
        List<SearchZone> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("zones");
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JsonObject z = arr.get(i).getAsJsonObject();
            try {
                out.add(parseZone(z));
            } catch (Exception e) {
                LOGGER.warn("[ZonePoller] Skipping malformed zone {}: {}", z, e.getMessage());
            }
        }
        return out;
    }

    private static SearchZone parseZone(JsonObject z) {
        long id = z.get("id").getAsLong();
        String name = z.has("name") && !z.get("name").isJsonNull()
                ? z.get("name").getAsString() : ("zone-" + id);
        String dim = z.get("dim").getAsString();
        boolean active = !z.has("active") || z.get("active").getAsBoolean();
        JsonObject geom = z.getAsJsonObject("geometry");
        String type = geom.get("type").getAsString();
        if ("Circle".equals(type)) {
            JsonObject c = geom.getAsJsonObject("coordinates");
            return SearchZone.circle(id, name, dim, active, new SearchZone.Circle(
                    c.get("centerX").getAsDouble(),
                    c.get("centerZ").getAsDouble(),
                    c.get("radius").getAsDouble()));
        }
        if ("Polygon".equals(type)) {
            JsonArray rings = geom.getAsJsonArray("coordinates");
            JsonArray outer = rings.get(0).getAsJsonArray();
            List<double[]> ring = new ArrayList<>(outer.size());
            for (int j = 0; j < outer.size(); j++) {
                JsonArray p = outer.get(j).getAsJsonArray();
                ring.add(new double[] { p.get(0).getAsDouble(), p.get(1).getAsDouble() });
            }
            return SearchZone.polygon(id, name, dim, active, new SearchZone.Polygon(ring));
        }
        throw new IllegalArgumentException("Unsupported geometry type: " + type);
    }
}
