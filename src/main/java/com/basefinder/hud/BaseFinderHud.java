package com.basefinder.hud;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.NewChunksModule;
import com.basefinder.survival.SurvivalManager;
import com.basefinder.terrain.TerrainPredictor;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.LagDetector;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.hud.TextHudElement;
import org.rusherhack.client.api.feature.module.IModule;

/**
 * HUD element showing BaseFinder status information.
 * Multi-line structured layout with sections for:
 * 1. Status - main module state
 * 2. Flight - elytra state, altitude, destination, fireworks, circling
 * 3. Terrain - predicted height, seed type, safety margin
 * 4. Scan - chunks scanned, bases found, waypoint progress
 * 5. Chunks - new/old chunks, freshness, deferred
 * 6. Survival - HP, totems, TPS, lag warnings
 * 7. Stats - uptime, distance traveled
 */
public class BaseFinderHud extends TextHudElement {

    private final Minecraft mc = Minecraft.getInstance();

    public BaseFinderHud() {
        super("BaseFinderHud");
    }

    @Override
    public String getText() {
        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (!(module instanceof BaseFinderModule baseFinder) || !baseFinder.isToggled()) {
            return "BaseFinder: OFF";
        }

        StringBuilder sb = new StringBuilder();

        // === Line 1: Status ===
        sb.append("BaseFinder: ").append(baseFinder.getState().name());

        // === Line 2: Flight ===
        ElytraBot elytra = baseFinder.getElytraBot();
        sb.append("\n");
        sb.append(Lang.t("Flight: ", "Vol: "));
        if (elytra.isFlying()) {
            sb.append(elytra.getState().name());

            // Altitude
            if (mc.player != null) {
                sb.append(" ").append((int) mc.player.getY()).append("m");
            }

            // Distance to destination
            double destDist = elytra.getDistanceToDestination();
            if (destDist >= 0) {
                if (destDist > 1000) {
                    sb.append(Lang.t(" | -> ", " | -> ")).append(String.format("%.1fkm", destDist / 1000));
                } else {
                    sb.append(Lang.t(" | -> ", " | -> ")).append(String.format("%.0fm", destDist));
                }
            }

            // Fireworks
            sb.append(Lang.t(" | FW: ", " | FW: ")).append(elytra.getFireworkCount());

            // Circling indicator
            if (elytra.isCircling()) {
                sb.append(Lang.t(" | CIRCLING ", " | ORBITE "));
                sb.append(String.format("%.0fs", elytra.getCircleTicks() / 20.0));
            }
        } else {
            sb.append(Lang.t("OFF", "OFF"));
        }

        // === Line 3: Terrain ===
        TerrainPredictor terrain = baseFinder.getTerrainPredictor();
        if (terrain != null && elytra.isFlying() && mc.player != null) {
            sb.append("\n");
            sb.append(Lang.t("Terrain: ", "Terrain: "));
            int predicted = terrain.getMaxHeightAhead(mc.player.position(), mc.player.getDeltaMovement(), 200);
            sb.append("~").append(predicted).append("m");
            sb.append(Lang.t(" ahead", " devant"));
            sb.append(" | ").append(terrain.getLastSource());
        }

        // === Line 4: Scan ===
        sb.append("\n");
        sb.append("Scan: ").append(String.format("%,d", baseFinder.getScanner().getScannedCount()));
        sb.append(" chunks | ").append(baseFinder.getBaseLogger().getCount());
        sb.append(Lang.t(" bases", " bases"));

        // Waypoint progress
        if (baseFinder.getNavigation().getCurrentTarget() != null) {
            sb.append(" | WP ").append(baseFinder.getNavigation().getCurrentWaypointIndex() + 1)
              .append("/").append(baseFinder.getNavigation().getWaypointCount());
        }

        // Trail info
        if (baseFinder.getTrailFollower().isFollowingTrail()) {
            TrailFollower.TrailType trailType = baseFinder.getTrailFollower().getCurrentTrailType();
            sb.append(Lang.t(" | Trail(", " | Piste(")).append(trailType.name()).append("): ")
              .append(baseFinder.getTrailFollower().getTrailLength());
        }

        // === Line 5: Chunks (NewChunks) ===
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("ChunkHistory").orElse(null);
        int deferred = baseFinder.getScanner().getDeferredCount();
        boolean hasNewChunks = ncModule instanceof NewChunksModule nc0 && nc0.isToggled();
        if (hasNewChunks || deferred > 0) {
            sb.append("\n");
            sb.append("NC: ");
            if (ncModule instanceof NewChunksModule nc && nc.isToggled()) {
                sb.append(nc.getDetector().getNewChunkCount())
                  .append(Lang.t(" new / ", " new / "))
                  .append(nc.getDetector().getOldChunkCount())
                  .append(" old");
            }
            if (deferred > 0) {
                sb.append(Lang.t(" | Deferred: ", " | Différés: ")).append(deferred);
            }
        }

        // === Line 6: Survival ===
        sb.append("\n");
        SurvivalManager survival = baseFinder.getSurvivalManager();
        sb.append(Lang.t("Survival: ", "Survie: "));

        // Health
        if (mc.player != null) {
            int hp = (int) mc.player.getHealth();
            sb.append("HP ").append(hp);
        }

        // Totems
        sb.append(Lang.t(" | Totems: ", " | Totems: ")).append(survival.getTotemCount());

        // TPS
        LagDetector lag = baseFinder.getLagDetector();
        double tps = lag.getEstimatedTPS();
        sb.append(" | TPS: ").append(String.format("%.1f", tps));
        if (lag.isSeverelyLagging()) {
            sb.append(Lang.t(" LAG!", " LAG!"));
        }

        // Status warnings
        if (survival.isEmergencyLanding()) {
            sb.append(Lang.t(" | HP LOW!", " | PV BAS!"));
        }
        if (survival.isResupplying()) {
            sb.append(Lang.t(" | RESUPPLY", " | REAPPRO"));
        }
        if (elytra.isFlying() && elytra.hasUnloadedChunksAhead()) {
            sb.append(Lang.t(" | UNLOADED!", " | NON CHARGE!"));
        }

        // === Line 7: Stats ===
        long uptime = survival.getUptimeSeconds();
        double dist = baseFinder.getNavigation().getTotalDistanceTraveled();
        if (uptime > 0 || dist > 0) {
            sb.append("\n");

            // Uptime
            if (uptime > 0) {
                long hours = uptime / 3600;
                long minutes = (uptime % 3600) / 60;
                sb.append("Uptime: ");
                if (hours > 0) {
                    sb.append(hours).append("h ").append(minutes).append("m");
                } else {
                    sb.append(minutes).append("m");
                }
            }

            // Distance traveled
            if (dist > 0) {
                if (uptime > 0) sb.append(" | ");
                if (dist > 1000) {
                    sb.append(Lang.t("Traveled: ", "Parcouru: ")).append(String.format("%.1fk", dist / 1000));
                } else {
                    sb.append(Lang.t("Traveled: ", "Parcouru: ")).append(String.format("%.0f", dist));
                }
            }
        }

        return sb.toString();
    }
}
