package com.basefinder.adapter.io.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Poll périodique de {@code GET /v1/commands?ack=false} sur le backend.
 * Exécute chaque commande non-acquittée (toggle/pause/resume/skip
 * BaseHunter pour l'instant), puis POST {@code /v1/commands/{id}/ack}.
 *
 * Daemon thread, échecs HTTP silencieux. La queue côté backend conserve
 * les commandes non-acquittées tant que le bot n'a pas répondu, donc en
 * cas de coupure réseau ou de restart MC, les commandes sont rejouées.
 *
 * Tous les types inconnus sont quand même acquittés (sinon ils
 * bloqueraient indéfiniment la queue).
 */
public final class CommandPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger("CommandPoller");

    private final String backendUrl;
    private final long pollIntervalSeconds;
    private final HttpClient http;
    private ScheduledExecutorService scheduler;

    public CommandPoller(String backendUrl) {
        this(backendUrl, 2);
    }

    public CommandPoller(String backendUrl, long pollIntervalSeconds) {
        this.backendUrl = backendUrl.endsWith("/")
                ? backendUrl.substring(0, backendUrl.length() - 1)
                : backendUrl;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BaseFinder-CommandPoller");
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
                    .uri(URI.create(backendUrl + "/v1/commands?ack=false&limit=20"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonArray cmds = root.getAsJsonArray("commands");
            if (cmds == null || cmds.size() == 0) return;
            for (int i = 0; i < cmds.size(); i++) {
                JsonObject cmd = cmds.get(i).getAsJsonObject();
                long id = cmd.get("id").getAsLong();
                String type = cmd.get("type").getAsString();
                JsonObject payload = cmd.has("payload") && cmd.get("payload").isJsonObject()
                        ? cmd.getAsJsonObject("payload") : new JsonObject();
                try {
                    execute(type, payload);
                } catch (Throwable t) {
                    LOGGER.warn("[CommandPoller] Exec failed for cmd #{} ({}): {}", id, type, t.getMessage());
                }
                ack(id);
            }
        } catch (Exception e) {
            LOGGER.debug("[CommandPoller] Poll failed: {}", e.getMessage());
        }
    }

    private void ack(long id) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(backendUrl + "/v1/commands/" + id + "/ack"))
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOGGER.debug("[CommandPoller] Ack failed for {}: {}", id, e.getMessage());
        }
    }

    /**
     * Mappage commande → action. Doit être appelé sur le render thread
     * pour les opérations qui touchent à RusherHack — ici on s'en
     * affranchit : les méthodes {@code toggle()/getFeature()} sont
     * thread-safe en pratique chez RusherHack et le coût d'un dispatch
     * n'en vaut pas la peine pour un poller à 2 s.
     */
    private void execute(String type, JsonObject payload) {
        // Toutes les commandes basefinder.* exigent que le joueur soit
        // dans un monde (BaseFinderModule.onEnable() rejette sinon avec
        // "Vous devez être dans un monde !"). Pendant la queue 2b2t ou
        // un chargement, on ne tente même pas l'exécution — on ack quand
        // même pour drainer la file (sinon les commandes restent
        // pending et finissent par être appliquées au mauvais moment).
        if (type.startsWith("basefinder.") && !inWorld()) {
            LOGGER.info("[CommandPoller] Skipping {} — not in world (queue/loading)", type);
            return;
        }
        switch (type) {
            case "basefinder.toggle" -> toggleBaseHunter(null);
            case "basefinder.enable" -> toggleBaseHunter(Boolean.TRUE);
            case "basefinder.disable" -> toggleBaseHunter(Boolean.FALSE);
            case "basefinder.pause" -> callBaseHunterMethod("pause");
            case "basefinder.resume" -> callBaseHunterMethod("resume");
            case "basefinder.skip" -> callBaseHunterMethod("skipWaypoint");
            default -> LOGGER.info("[CommandPoller] Ignored unknown command type: {}", type);
        }
    }

    private static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.level != null;
    }

    /**
     * @param desired true = force ON, false = force OFF, null = toggle
     */
    private void toggleBaseHunter(Boolean desired) {
        IModule mod = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (!(mod instanceof ToggleableModule tm)) {
            LOGGER.warn("[CommandPoller] BaseHunter module not found");
            return;
        }
        boolean isOn = tm.isToggled();
        boolean shouldFlip = (desired == null) || (desired != isOn);
        if (shouldFlip) {
            tm.toggle();
            LOGGER.info("[CommandPoller] BaseHunter toggled to {}", !isOn);
        } else {
            LOGGER.info("[CommandPoller] BaseHunter already in desired state ({})", isOn);
        }
    }

    private void callBaseHunterMethod(String name) {
        IModule mod = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (mod == null) {
            LOGGER.warn("[CommandPoller] BaseHunter module not found for {}()", name);
            return;
        }
        try {
            mod.getClass().getMethod(name).invoke(mod);
            LOGGER.info("[CommandPoller] Called BaseHunter.{}()", name);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[CommandPoller] BaseHunter.{}() failed: {}", name, e.getMessage());
        }
    }
}
