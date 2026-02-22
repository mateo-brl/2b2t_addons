package com.basefinder.hud;

import com.basefinder.elytra.ElytraBot;
import com.basefinder.modules.BaseFinderModule;
import com.basefinder.modules.NewChunksModule;
import com.basefinder.survival.SurvivalManager;
import com.basefinder.terrain.TerrainPredictor;
import com.basefinder.trail.TrailFollower;
import com.basefinder.util.LagDetector;
import com.basefinder.util.Lang;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD element showing BaseFinder status as a structured mini-map panel.
 * Sections: Status, Flight, Terrain, Scan, Chunks, Survival, Stats.
 */
public class BaseFinderHud extends HudElement {

    // Colors
    private static final int BG_COLOR       = 0xCC1A1A2E; // dark blue-black semi-transparent
    private static final int BORDER_COLOR    = 0xFF4A4A6A; // muted purple-grey
    private static final int SEPARATOR_COLOR = 0xFF3A3A5A; // slightly dimmer for section dividers
    private static final int TITLE_COLOR     = 0xFF9B9BFF; // light purple accent for "BaseFinder"
    private static final int SECTION_COLOR   = 0xFF7B9BCC; // blue accent for section headers
    private static final int TEXT_COLOR      = 0xFFD0D0D0; // default text (light grey)
    private static final int VALUE_COLOR     = 0xFFFFFFFF; // white for important values
    private static final int GREEN_COLOR     = 0xFF55FF55; // OK values
    private static final int YELLOW_COLOR    = 0xFFFFFF55; // warnings
    private static final int RED_COLOR       = 0xFFFF5555; // critical
    private static final int DIM_COLOR       = 0xFF808080; // dim/secondary info

    private static final double PADDING = 5.0;
    private static final double SECTION_GAP = 2.0;
    private static final float BORDER_WIDTH = 1.0f;

    // Cached dimensions (updated each render)
    private double panelWidth = 200;
    private double panelHeight = 100;

    public BaseFinderHud() {
        super("BaseFinderHud", "BaseFinder");
    }

    @Override
    public double getWidth() {
        return panelWidth;
    }

    @Override
    public double getHeight() {
        return panelHeight;
    }

    @Override
    public boolean shouldDrawBackground() {
        return true; // use framework background instead of custom shapes
    }

    @Override
    public void renderContent(RenderContext context, double mouseX, double mouseY) {
        IFontRenderer font = getFontRenderer();

        // Build all lines first to compute dimensions
        List<PanelLine> lines = buildLines(font);
        if (lines.isEmpty()) return;

        double lineHeight = font.getFontHeight() + 1.0;

        // Compute dynamic width from longest line
        double maxTextWidth = 0;
        for (PanelLine line : lines) {
            if (!line.isSeparator) {
                double w = font.getStringWidth(line.text) + line.indent;
                if (w > maxTextWidth) maxTextWidth = w;
            }
        }

        panelWidth = maxTextWidth + PADDING * 2 + 4;
        if (panelWidth < 160) panelWidth = 160;

        // Compute height
        double contentHeight = 0;
        for (PanelLine line : lines) {
            contentHeight += line.isSeparator ? lineHeight * 0.4 : lineHeight;
        }
        panelHeight = contentHeight + PADDING * 2;

        // Render text only (no shape drawing to avoid "Already building" crash)
        double curY = PADDING;
        for (PanelLine line : lines) {
            if (line.isSeparator) {
                // Draw separator as a dim text line
                font.drawString("---", PADDING, curY, SEPARATOR_COLOR, true);
                curY += lineHeight * 0.4;
            } else {
                font.drawString(line.text, PADDING + line.indent, curY, line.color, true);
                curY += lineHeight;
            }
        }
    }

    // ===== LINE BUILDING =====

    private List<PanelLine> buildLines(IFontRenderer font) {
        List<PanelLine> lines = new ArrayList<>();

        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (!(module instanceof BaseFinderModule baseFinder) || !baseFinder.isToggled()) {
            lines.add(new PanelLine("BaseFinder: OFF", DIM_COLOR));
            return lines;
        }

        // === Title ===
        lines.add(new PanelLine("BaseFinder", TITLE_COLOR));

        // === Status ===
        String stateName = baseFinder.getState().name();
        int stateColor = getStateColor(stateName);
        lines.add(new PanelLine("Status: " + stateName, stateColor));

        // === Separator + Flight Section ===
        lines.add(PanelLine.separator());

        ElytraBot elytra = baseFinder.getElytraBot();
        if (elytra.isFlying()) {
            String flightState = elytra.getState().name();
            int flightColor = getFlightStateColor(flightState);
            String altStr = "";
            if (mc.player != null) {
                altStr = "  " + (int) mc.player.getY() + "m";
            }
            lines.add(new PanelLine(Lang.t("\u2708 Flight", "\u2708 Vol") + "    " + flightState + altStr, flightColor, 0));

            // Distance + fireworks
            StringBuilder detailSb = new StringBuilder();
            double destDist = elytra.getDistanceToDestination();
            if (destDist >= 0) {
                detailSb.append("-> ");
                if (destDist > 1000) {
                    detailSb.append(String.format("%.1fkm", destDist / 1000));
                } else {
                    detailSb.append(String.format("%.0fm", destDist));
                }
                detailSb.append("    ");
            }
            detailSb.append("FW: ").append(elytra.getFireworkCount());
            int fwColor = elytra.getFireworkCount() <= 5 ? (elytra.getFireworkCount() <= 2 ? RED_COLOR : YELLOW_COLOR) : TEXT_COLOR;
            lines.add(new PanelLine(detailSb.toString(), fwColor, 8));

            // Circling indicator
            if (elytra.isCircling()) {
                String circleText = Lang.t("CIRCLING ", "ORBITE ") + String.format("%.0fs", elytra.getCircleTicks() / 20.0);
                lines.add(new PanelLine(circleText, YELLOW_COLOR, 8));
            }
        } else {
            lines.add(new PanelLine(Lang.t("\u2708 Flight    OFF", "\u2708 Vol    OFF"), DIM_COLOR));
        }

        // === Terrain (conditional) ===
        TerrainPredictor terrain = baseFinder.getTerrainPredictor();
        if (terrain != null && elytra.isFlying() && mc.player != null) {
            int predicted = terrain.getMaxHeightAhead(mc.player.position(), mc.player.getDeltaMovement(), 200);
            String src = terrain.getLastSource();
            int terrainColor = TEXT_COLOR;
            if (mc.player.getY() < predicted + 30) terrainColor = YELLOW_COLOR;
            if (mc.player.getY() < predicted + 10) terrainColor = RED_COLOR;
            lines.add(new PanelLine(Lang.t("Terrain: ~", "Terrain: ~") + predicted + "m  " + src, terrainColor, 8));
        }

        // === Separator + Scan Section ===
        lines.add(PanelLine.separator());

        int scanned = baseFinder.getScanner().getScannedCount();
        int baseCount = baseFinder.getBaseLogger().getCount();
        lines.add(new PanelLine(Lang.t("\u2593 Scan", "\u2593 Scan") + "    " + String.format("%,d", scanned) + " chunks", SECTION_COLOR, 0));

        StringBuilder scanDetailSb = new StringBuilder();
        scanDetailSb.append(baseCount).append(Lang.t(" bases", " bases"));

        // Waypoint progress
        if (baseFinder.getNavigation().getCurrentTarget() != null) {
            scanDetailSb.append("  WP ")
                .append(baseFinder.getNavigation().getCurrentWaypointIndex() + 1)
                .append("/").append(baseFinder.getNavigation().getWaypointCount());
        }
        lines.add(new PanelLine(scanDetailSb.toString(), TEXT_COLOR, 8));

        // Trail info
        if (baseFinder.getTrailFollower().isFollowingTrail()) {
            TrailFollower.TrailType trailType = baseFinder.getTrailFollower().getCurrentTrailType();
            String trailText = Lang.t("Trail(", "Piste(") + trailType.name() + "): " + baseFinder.getTrailFollower().getTrailLength();
            lines.add(new PanelLine(trailText, TEXT_COLOR, 8));
        }

        // NewChunks info
        IModule ncModule = RusherHackAPI.getModuleManager().getFeature("ChunkHistory").orElse(null);
        int deferred = baseFinder.getScanner().getDeferredCount();
        if (ncModule instanceof NewChunksModule nc && nc.isToggled()) {
            String ncText = "NC: " + nc.getDetector().getNewChunkCount()
                + Lang.t(" new / ", " new / ") + nc.getDetector().getOldChunkCount() + " old";
            if (deferred > 0) {
                ncText += Lang.t("  Defer: ", "  Diff: ") + deferred;
            }
            lines.add(new PanelLine(ncText, TEXT_COLOR, 8));
        } else if (deferred > 0) {
            lines.add(new PanelLine(Lang.t("Deferred: ", "Différés: ") + deferred, DIM_COLOR, 8));
        }

        // === Separator + Survival Section ===
        lines.add(PanelLine.separator());

        SurvivalManager survival = baseFinder.getSurvivalManager();
        LagDetector lag = baseFinder.getLagDetector();
        double tps = lag.getEstimatedTPS();

        // HP + TPS header line
        int hp = mc.player != null ? (int) mc.player.getHealth() : 0;
        int hpColor = hp > 14 ? GREEN_COLOR : (hp > 6 ? YELLOW_COLOR : RED_COLOR);
        int tpsColor = tps > 17 ? TEXT_COLOR : (tps > 12 ? YELLOW_COLOR : RED_COLOR);

        String survivalHeader = Lang.t("\u2764 Survival", "\u2764 Survie") + "   HP " + hp + "  TPS " + String.format("%.1f", tps);
        int headerColor = (hp <= 6 || tps <= 12) ? RED_COLOR : (hp <= 14 || tps <= 17) ? YELLOW_COLOR : SECTION_COLOR;
        lines.add(new PanelLine(survivalHeader, headerColor, 0));

        // Totems + uptime detail line
        StringBuilder survDetailSb = new StringBuilder();
        survDetailSb.append(Lang.t("Totems: ", "Totems: ")).append(survival.getTotemCount());

        long uptime = survival.getUptimeSeconds();
        if (uptime > 0) {
            long hours = uptime / 3600;
            long minutes = (uptime % 3600) / 60;
            survDetailSb.append("  Uptime: ");
            if (hours > 0) {
                survDetailSb.append(hours).append("h").append(minutes).append("m");
            } else {
                survDetailSb.append(minutes).append("m");
            }
        }
        lines.add(new PanelLine(survDetailSb.toString(), TEXT_COLOR, 8));

        // Warning flags
        List<String> warnings = new ArrayList<>();
        if (lag.isSeverelyLagging()) warnings.add(Lang.t("LAG!", "LAG!"));
        if (survival.isEmergencyLanding()) warnings.add(Lang.t("HP LOW!", "PV BAS!"));
        if (survival.isResupplying()) warnings.add(Lang.t("RESUPPLY", "REAPPRO"));
        if (elytra.isFlying() && elytra.hasUnloadedChunksAhead()) warnings.add(Lang.t("UNLOADED!", "NON CHARGE!"));

        if (!warnings.isEmpty()) {
            lines.add(new PanelLine(String.join("  ", warnings), RED_COLOR, 8));
        }

        // === Distance traveled (if any) ===
        double dist = baseFinder.getNavigation().getTotalDistanceTraveled();
        if (dist > 0) {
            String distText;
            if (dist > 1000) {
                distText = Lang.t("Traveled: ", "Parcouru: ") + String.format("%.1fk", dist / 1000);
            } else {
                distText = Lang.t("Traveled: ", "Parcouru: ") + String.format("%.0f", dist);
            }
            lines.add(new PanelLine(distText, DIM_COLOR, 8));
        }

        return lines;
    }

    // ===== COLOR HELPERS =====

    private int getStateColor(String state) {
        return switch (state) {
            case "IDLE", "STOPPED" -> DIM_COLOR;
            case "SCANNING", "CRUISING", "FLYING_TO_WAYPOINT" -> GREEN_COLOR;
            case "APPROACHING_BASE", "PHOTOGRAPHING" -> YELLOW_COLOR;
            case "EMERGENCY_LANDING", "ERROR" -> RED_COLOR;
            default -> VALUE_COLOR;
        };
    }

    private int getFlightStateColor(String state) {
        return switch (state) {
            case "CRUISING" -> GREEN_COLOR;
            case "CLIMBING", "TAKING_OFF" -> VALUE_COLOR;
            case "DESCENDING", "SAFE_DESCENDING", "FLARING" -> YELLOW_COLOR;
            case "LANDING", "BARITONE_LANDING" -> YELLOW_COLOR;
            case "CIRCLING", "REFUELING" -> YELLOW_COLOR;
            case "IDLE" -> DIM_COLOR;
            default -> TEXT_COLOR;
        };
    }

    // ===== INNER CLASS =====

    private static class PanelLine {
        final String text;
        final int color;
        final double indent;
        final boolean isSeparator;

        PanelLine(String text, int color) {
            this(text, color, 0);
        }

        PanelLine(String text, int color, double indent) {
            this.text = text;
            this.color = color;
            this.indent = indent;
            this.isSeparator = false;
        }

        private PanelLine() {
            this.text = "";
            this.color = 0;
            this.indent = 0;
            this.isSeparator = true;
        }

        static PanelLine separator() {
            return new PanelLine();
        }
    }
}
