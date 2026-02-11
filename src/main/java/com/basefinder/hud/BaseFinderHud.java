package com.basefinder.hud;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.NewChunksModule;
import com.basefinder.trail.TrailFollower;
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

        // Info piste
        if (baseFinder.getTrailFollower().isFollowingTrail()) {
            TrailFollower.TrailType trailType = baseFinder.getTrailFollower().getCurrentTrailType();
            sb.append(" | Piste(").append(trailType.name()).append("): ")
              .append(baseFinder.getTrailFollower().getTrailLength());
        }

        // NewChunks info
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("ChunkHistory").orElse(null);
        if (ncModule instanceof NewChunksModule nc && nc.isToggled()) {
            sb.append(" | NC: ").append(nc.getDetector().getNewChunkCount())
              .append("/").append(nc.getDetector().getOldChunkCount());
        }

        // Info elytra
        ElytraBot elytra = baseFinder.getElytraBot();
        if (elytra.isFlying()) {
            sb.append(" | Vol: ").append(elytra.getState().name());
            sb.append(" | Fusées: ").append(elytra.getFireworkCount());
            double dist = elytra.getDistanceToDestination();
            if (dist >= 0) {
                sb.append(" | Dist: ").append(String.format("%.0f", dist));
            }
        }

        // Distance parcourue
        double dist = baseFinder.getNavigation().getTotalDistanceTraveled();
        if (dist > 0) {
            if (dist > 1000) {
                sb.append(" | Parcouru: ").append(String.format("%.1fk", dist / 1000));
            } else {
                sb.append(" | Parcouru: ").append(String.format("%.0f", dist));
            }
        }

        return sb.toString();
    }
}
