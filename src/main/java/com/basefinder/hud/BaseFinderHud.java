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
        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseFinder").orElse(null);
        if (!(module instanceof BaseFinderModule baseFinder) || !baseFinder.isToggled()) {
            return "BaseFinder: OFF";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("BaseFinder: ").append(baseFinder.getState().name());

        // Scan info
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
            sb.append(" | Trail(").append(trailType.name()).append("): ")
              .append(baseFinder.getTrailFollower().getTrailLength());
        }

        // NewChunks info
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("NewChunks").orElse(null);
        if (ncModule instanceof NewChunksModule nc && nc.isToggled()) {
            sb.append(" | NC: ").append(nc.getDetector().getNewChunkCount())
              .append("/").append(nc.getDetector().getOldChunkCount());
        }

        // Elytra info
        ElytraBot elytra = baseFinder.getElytraBot();
        if (elytra.isFlying()) {
            sb.append(" | Fly: ").append(elytra.getState().name());
            sb.append(" | FW: ").append(elytra.getFireworkCount());
            double dist = elytra.getDistanceToDestination();
            if (dist >= 0) {
                sb.append(" | Dist: ").append(String.format("%.0f", dist));
            }
        }

        // Distance traveled
        double dist = baseFinder.getNavigation().getTotalDistanceTraveled();
        if (dist > 0) {
            if (dist > 1000) {
                sb.append(" | Travel: ").append(String.format("%.1fk", dist / 1000));
            } else {
                sb.append(" | Travel: ").append(String.format("%.0f", dist));
            }
        }

        return sb.toString();
    }
}
