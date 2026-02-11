package com.basefinder.hud;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.NewChunksModule;
import com.basefinder.survival.SurvivalManager;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.LagDetector;
import com.basefinder.util.Lang;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.hud.TextHudElement;
import org.rusherhack.client.api.feature.module.IModule;

/**
 * HUD element showing BaseFinder status information.
 */
public class BaseFinderHud extends TextHudElement {

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
        sb.append("BaseFinder: ").append(baseFinder.getState().name());

        // Info scan
        sb.append(" | Chunks: ").append(baseFinder.getScanner().getScannedCount());
        sb.append(" | Bases: ").append(baseFinder.getBaseLogger().getCount());

        // Navigation info
        if (baseFinder.getNavigation().getCurrentTarget() != null) {
            sb.append(" | WP: ").append(baseFinder.getNavigation().getCurrentWaypointIndex() + 1)
              .append("/").append(baseFinder.getNavigation().getWaypointCount());
        }

        // Trail info
        if (baseFinder.getTrailFollower().isFollowingTrail()) {
            TrailFollower.TrailType trailType = baseFinder.getTrailFollower().getCurrentTrailType();
            sb.append(Lang.t(" | Trail(", " | Piste(")).append(trailType.name()).append("): ")
              .append(baseFinder.getTrailFollower().getTrailLength());
        }

        // NewChunks info
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("ChunkHistory").orElse(null);
        if (ncModule instanceof NewChunksModule nc && nc.isToggled()) {
            sb.append(" | NC: ").append(nc.getDetector().getNewChunkCount())
              .append("/").append(nc.getDetector().getOldChunkCount());
        }

        // Elytra info
        ElytraBot elytra = baseFinder.getElytraBot();
        if (elytra.isFlying()) {
            sb.append(Lang.t(" | Fly: ", " | Vol: ")).append(elytra.getState().name());
            sb.append(Lang.t(" | FW: ", " | Fusées: ")).append(elytra.getFireworkCount());
            double dist = elytra.getDistanceToDestination();
            if (dist >= 0) {
                sb.append(" | Dist: ").append(String.format("%.0f", dist));
            }
        }

        // Distance traveled
        double dist = baseFinder.getNavigation().getTotalDistanceTraveled();
        if (dist > 0) {
            if (dist > 1000) {
                sb.append(Lang.t(" | Travel: ", " | Parcouru: ")).append(String.format("%.1fk", dist / 1000));
            } else {
                sb.append(Lang.t(" | Travel: ", " | Parcouru: ")).append(String.format("%.0f", dist));
            }
        }

        // Survival stats
        SurvivalManager survival = baseFinder.getSurvivalManager();
        int totems = survival.getTotemCount();
        sb.append(Lang.t(" | Totems: ", " | Totems: ")).append(totems);

        if (survival.isEmergencyLanding()) {
            sb.append(Lang.t(" | HP LOW!", " | PV BAS !"));
        }

        if (survival.isResupplying()) {
            sb.append(Lang.t(" | RESUPPLY", " | RÉAPPRO"));
        }

        // TPS / lag info
        LagDetector lag = baseFinder.getLagDetector();
        double tps = lag.getEstimatedTPS();
        if (tps < 19.5) { // Only show if not perfect TPS
            sb.append(" | TPS: ").append(String.format("%.1f", tps));
            if (lag.isSeverelyLagging()) {
                sb.append(Lang.t(" LAG!", " LAG !"));
            }
        }

        // Unloaded chunks warning
        ElytraBot elytraForLag = baseFinder.getElytraBot();
        if (elytraForLag.isFlying() && elytraForLag.hasUnloadedChunksAhead()) {
            sb.append(Lang.t(" | UNLOADED!", " | NON CHARGÉ !"));
        }

        // Deferred chunks (skipped due to lag)
        int deferred = baseFinder.getScanner().getDeferredCount();
        if (deferred > 0) {
            sb.append(Lang.t(" | Deferred: ", " | Différés: ")).append(deferred);
        }

        // Uptime
        long uptime = survival.getUptimeSeconds();
        if (uptime > 0) {
            long hours = uptime / 3600;
            long minutes = (uptime % 3600) / 60;
            if (hours > 0) {
                sb.append(" | ").append(hours).append("h").append(minutes).append("m");
            } else {
                sb.append(" | ").append(minutes).append("m");
            }
        }

        return sb.toString();
    }
}
