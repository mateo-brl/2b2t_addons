package com.basefinder.hud;

import com.basefinder.domain.view.BaseFinderViewModel;
import com.basefinder.modules.BaseFinderModule;
import com.basefinder.util.Lang;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD element showing BaseFinder status as a structured panel.
 * Consomme uniquement {@link BaseFinderViewModel} — plus de pull direct des
 * sous-services (audit/05 §5 étape 4).
 */
public class BaseFinderHud extends HudElement {

    // ===== COLOR PALETTE =====
    private static final int BG_COLOR       = 0xCC1A1A2E;
    private static final int BORDER_COLOR   = 0xFF4A4A6A;
    private static final int SEPARATOR_COLOR = 0xFF3A3A5A;

    private static final int TITLE_COLOR    = 0xFFB8B8FF;
    private static final int SECTION_COLOR  = 0xFF7BA4D4;
    private static final int LABEL_COLOR    = 0xFFA0A0B8;
    private static final int TEXT_COLOR     = 0xFFD0D0D0;
    private static final int VALUE_COLOR    = 0xFFFFFFFF;
    private static final int DIM_COLOR      = 0xFF686878;

    private static final int GREEN_COLOR    = 0xFF55FF55;
    private static final int YELLOW_COLOR   = 0xFFFFFF55;
    private static final int RED_COLOR      = 0xFFFF5555;
    private static final int ORANGE_COLOR   = 0xFFFFAA33;
    private static final int CYAN_COLOR     = 0xFF55FFFF;

    private static final int BAR_FILLED_COLOR = 0xFF55CC55;
    private static final int BAR_EMPTY_COLOR  = 0xFF404050;
    private static final int BAR_BRACKET_COLOR = 0xFF8080A0;

    private static final double PADDING = 6.0;
    private static final double SECTION_INDENT = 6.0;
    private static final double DETAIL_INDENT = 12.0;

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
        return true;
    }

    @Override
    public void renderContent(RenderContext context, double mouseX, double mouseY) {
        IFontRenderer font = getFontRenderer();

        List<HudLine> lines = buildLines();
        if (lines.isEmpty()) return;

        double lineHeight = font.getFontHeight() + 2.0;

        double maxTextWidth = 0;
        for (HudLine line : lines) {
            if (line.type == LineType.SEPARATOR) continue;
            double w = line.indent + computeLineWidth(font, line);
            if (w > maxTextWidth) maxTextWidth = w;
        }

        panelWidth = maxTextWidth + PADDING * 2 + 6;
        if (panelWidth < 180) panelWidth = 180;

        double contentHeight = 0;
        for (HudLine line : lines) {
            contentHeight += getLineHeight(line, lineHeight);
        }
        panelHeight = contentHeight + PADDING * 2;

        double curY = PADDING;
        for (HudLine line : lines) {
            switch (line.type) {
                case SEPARATOR -> {
                    String dots = "..........................";
                    font.drawString(dots, PADDING, curY + 1, SEPARATOR_COLOR, false);
                    curY += lineHeight * 0.5;
                }
                case SPACER -> curY += lineHeight * 0.25;
                default -> {
                    renderLine(font, line, PADDING + line.indent, curY);
                    curY += lineHeight;
                }
            }
        }
    }

    private double computeLineWidth(IFontRenderer font, HudLine line) {
        if (line.segments != null) {
            double w = 0;
            for (TextSegment seg : line.segments) {
                w += font.getStringWidth(seg.text);
            }
            return w;
        }
        return font.getStringWidth(line.text);
    }

    private double getLineHeight(HudLine line, double normalHeight) {
        return switch (line.type) {
            case SEPARATOR -> normalHeight * 0.5;
            case SPACER -> normalHeight * 0.25;
            default -> normalHeight;
        };
    }

    private void renderLine(IFontRenderer font, HudLine line, double x, double y) {
        if (line.segments != null) {
            double curX = x;
            for (TextSegment seg : line.segments) {
                font.drawString(seg.text, curX, y, seg.color, true);
                curX += font.getStringWidth(seg.text);
            }
        } else {
            font.drawString(line.text, x, y, line.color, true);
        }
    }

    // ===== LINE BUILDING (consomme uniquement le ViewModel) =====

    private List<HudLine> buildLines() {
        List<HudLine> lines = new ArrayList<>();

        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (!(module instanceof BaseFinderModule baseFinder)) {
            lines.add(text("BaseFinder OFF", DIM_COLOR));
            return lines;
        }
        BaseFinderViewModel vm = baseFinder.snapshot();
        if (!vm.active()) {
            lines.add(text("BaseFinder OFF", DIM_COLOR));
            return lines;
        }

        // ====== TITLE + STATUS ======
        int stateColor = getStateColor(vm.stateName());
        lines.add(multiColor(0,
                seg("BaseFinder ", TITLE_COLOR),
                seg("> ", DIM_COLOR),
                seg(formatStateName(vm.stateName()), stateColor)
        ));

        // ====== FLIGHT SECTION ======
        lines.add(separator());

        BaseFinderViewModel.FlightVm flight = vm.flight();
        BaseFinderViewModel.PlayerVm player = vm.player();
        if (flight.flying()) {
            int flightColor = getFlightStateColor(flight.stateName());
            String alt = player.present() ? player.y() + "m" : "?";

            lines.add(multiColor(0,
                    seg(">> ", SECTION_COLOR),
                    seg(Lang.t("Flight", "Vol"), SECTION_COLOR),
                    seg("  ", DIM_COLOR),
                    seg(formatStateName(flight.stateName()), flightColor),
                    seg("  ", DIM_COLOR),
                    seg(alt, LABEL_COLOR)
            ));

            List<TextSegment> detailSegs = new ArrayList<>();
            double destDist = flight.destinationDistance();
            if (destDist >= 0) {
                String distStr = destDist > 1000
                        ? String.format("%.1fkm", destDist / 1000)
                        : String.format("%.0fm", destDist);
                detailSegs.add(seg("-> " + distStr, TEXT_COLOR));
                detailSegs.add(seg("  ", DIM_COLOR));
            }
            int fwCount = flight.fireworkCount();
            int fwColor = fwCount <= 2 ? RED_COLOR : (fwCount <= 5 ? YELLOW_COLOR : TEXT_COLOR);
            detailSegs.add(seg("FW:", LABEL_COLOR));
            detailSegs.add(seg(" " + fwCount, fwColor));
            lines.add(multiColor(DETAIL_INDENT, detailSegs.toArray(new TextSegment[0])));

            if (flight.circling()) {
                String time = String.format("%.0fs", flight.circleTicks() / 20.0);
                lines.add(multiColor(DETAIL_INDENT,
                        seg("@ ", YELLOW_COLOR),
                        seg(Lang.t("CIRCLING", "ORBITE"), YELLOW_COLOR),
                        seg(" " + time, TEXT_COLOR)
                ));
            }
        } else {
            lines.add(multiColor(0,
                    seg(">> ", DIM_COLOR),
                    seg(Lang.t("Flight", "Vol"), DIM_COLOR),
                    seg("  OFF", DIM_COLOR)
            ));
        }

        // ====== TERRAIN (conditional) ======
        BaseFinderViewModel.TerrainVm terrain = vm.terrain();
        if (terrain != null && player.present()) {
            int predicted = terrain.predictedMaxHeight();
            int terrainColor = TEXT_COLOR;
            if (player.y() < predicted + 30) terrainColor = YELLOW_COLOR;
            if (player.y() < predicted + 10) terrainColor = RED_COLOR;
            lines.add(multiColor(DETAIL_INDENT,
                    seg(Lang.t("Terrain:", "Terrain:"), LABEL_COLOR),
                    seg(" ~" + predicted + "m", terrainColor),
                    seg("  " + terrain.source(), DIM_COLOR)
            ));
        }

        // ====== SCAN SECTION ======
        lines.add(separator());

        BaseFinderViewModel.ScanVm scan = vm.scan();
        int baseColor = scan.basesFound() > 0 ? GREEN_COLOR : TEXT_COLOR;

        lines.add(multiColor(0,
                seg(">> ", SECTION_COLOR),
                seg("Scan", SECTION_COLOR),
                seg("  ", DIM_COLOR),
                seg(formatNumber(scan.scannedCount()), VALUE_COLOR),
                seg(" chunks", LABEL_COLOR)
        ));

        lines.add(multiColor(DETAIL_INDENT,
                seg(Lang.t("Bases:", "Bases:"), LABEL_COLOR),
                seg(" " + scan.basesFound(), baseColor)
        ));

        // ====== WAYPOINT PROGRESS BAR ======
        BaseFinderViewModel.NavigationVm nav = vm.navigation();
        if (nav.hasCurrentTarget()) {
            int wpIndex = nav.waypointIndex();
            int wpTotal = nav.waypointTotal();
            double pct = nav.progressPercent();

            int barWidth = 20;
            int filled = computeFilledSegments(pct, barWidth, wpIndex, wpTotal);
            StringBuilder barFilled = new StringBuilder();
            StringBuilder barEmpty = new StringBuilder();
            for (int i = 0; i < barWidth; i++) {
                if (i < filled) {
                    barFilled.append("|");
                } else {
                    barEmpty.append(".");
                }
            }

            String pctStr = formatPercentage(pct);

            List<TextSegment> barSegs = new ArrayList<>();
            barSegs.add(seg("[", BAR_BRACKET_COLOR));
            if (barFilled.length() > 0) {
                barSegs.add(seg(barFilled.toString(), BAR_FILLED_COLOR));
            }
            if (barEmpty.length() > 0) {
                barSegs.add(seg(barEmpty.toString(), BAR_EMPTY_COLOR));
            }
            barSegs.add(seg("]", BAR_BRACKET_COLOR));
            barSegs.add(seg(" " + pctStr, pct > 75 ? GREEN_COLOR : (pct > 30 ? YELLOW_COLOR : TEXT_COLOR)));
            lines.add(multiColor(DETAIL_INDENT, barSegs.toArray(new TextSegment[0])));

            lines.add(multiColor(DETAIL_INDENT,
                    seg("WP:", LABEL_COLOR),
                    seg(" " + (wpIndex + 1), VALUE_COLOR),
                    seg("/" + formatNumber(wpTotal), LABEL_COLOR)
            ));
        }

        // ====== TRAIL ======
        BaseFinderViewModel.TrailVm trail = vm.trail();
        if (trail != null) {
            lines.add(multiColor(DETAIL_INDENT,
                    seg(Lang.t("Trail:", "Piste:"), LABEL_COLOR),
                    seg(" " + trail.trailType(), CYAN_COLOR),
                    seg(" (" + trail.trailLength() + ")", TEXT_COLOR)
            ));
        }

        if (scan.deferredCount() > 0) {
            lines.add(multiColor(DETAIL_INDENT,
                    seg(Lang.t("Deferred:", "Differes:"), LABEL_COLOR),
                    seg(" " + scan.deferredCount(), DIM_COLOR)
            ));
        }

        // ====== SURVIVAL SECTION ======
        lines.add(separator());

        BaseFinderViewModel.SurvivalVm survival = vm.survival();
        BaseFinderViewModel.LagVm lagVm = vm.lag();
        double tps = lagVm.estimatedTps();

        int hp = player.present() ? player.health() : 0;
        int hpColor = hp > 14 ? GREEN_COLOR : (hp > 6 ? YELLOW_COLOR : RED_COLOR);
        int tpsColor = tps > 17 ? GREEN_COLOR : (tps > 12 ? YELLOW_COLOR : RED_COLOR);

        lines.add(multiColor(0,
                seg(">> ", SECTION_COLOR),
                seg(Lang.t("Survival", "Survie"), SECTION_COLOR),
                seg("  HP:", LABEL_COLOR),
                seg(" " + hp, hpColor),
                seg("  TPS:", LABEL_COLOR),
                seg(" " + String.format("%.1f", tps), tpsColor)
        ));

        List<TextSegment> survSegs = new ArrayList<>();
        survSegs.add(seg(Lang.t("Totems:", "Totems:"), LABEL_COLOR));
        int totemCount = survival.totemCount();
        int totemColor = totemCount <= 1 ? RED_COLOR : (totemCount <= 3 ? YELLOW_COLOR : TEXT_COLOR);
        survSegs.add(seg(" " + totemCount, totemColor));

        long uptime = survival.uptimeSeconds();
        if (uptime > 0) {
            long hours = uptime / 3600;
            long minutes = (uptime % 3600) / 60;
            String uptimeStr = hours > 0
                    ? hours + "h" + String.format("%02d", minutes) + "m"
                    : minutes + "m";
            survSegs.add(seg("  Up:", LABEL_COLOR));
            survSegs.add(seg(" " + uptimeStr, TEXT_COLOR));
        }
        lines.add(multiColor(DETAIL_INDENT, survSegs.toArray(new TextSegment[0])));

        // ====== WARNING FLAGS ======
        List<String> warnings = new ArrayList<>();
        if (lagVm.severelyLagging()) warnings.add(Lang.t("! LAG", "! LAG"));
        if (survival.emergencyLanding()) warnings.add(Lang.t("! HP LOW", "! PV BAS"));
        if (survival.resupplying()) warnings.add(Lang.t("! RESUPPLY", "! REAPPRO"));
        if (flight.flying() && flight.unloadedChunksAhead()) {
            warnings.add(Lang.t("! UNLOADED", "! NON CHARGE"));
        }

        if (!warnings.isEmpty()) {
            lines.add(text(String.join("  ", warnings), RED_COLOR, DETAIL_INDENT));
        }

        // ====== DISTANCE (footer) ======
        double dist = nav.totalDistanceTraveled();
        if (dist > 0) {
            String distStr = dist > 1_000_000
                    ? String.format("%.1fM", dist / 1_000_000)
                    : dist > 1000
                            ? String.format("%.1fk", dist / 1000)
                            : String.format("%.0f", dist);
            lines.add(multiColor(DETAIL_INDENT,
                    seg(Lang.t("Traveled:", "Parcouru:"), DIM_COLOR),
                    seg(" " + distStr, LABEL_COLOR)
            ));
        }

        return lines;
    }

    // ===== PROGRESS BAR HELPERS =====

    private int computeFilledSegments(double pct, int barWidth, int wpIndex, int wpTotal) {
        if (wpTotal <= 0) return 0;
        double ratio = (double) wpIndex / wpTotal;
        int filled = (int) (ratio * barWidth);
        if (wpIndex > 0 && filled == 0) {
            filled = 1;
        }
        return Math.max(0, Math.min(barWidth, filled));
    }

    private String formatPercentage(double pct) {
        if (pct <= 0) return "0%";
        if (pct < 0.01) return "<0.01%";
        if (pct < 0.1) return String.format("%.3f%%", pct);
        if (pct < 1.0) return String.format("%.2f%%", pct);
        if (pct < 10.0) return String.format("%.2f%%", pct);
        return String.format("%.1f%%", pct);
    }

    private String formatNumber(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 10_000) return String.format("%.1fk", n / 1_000.0);
        return String.format("%,d", n);
    }

    private String formatStateName(String enumName) {
        if (enumName == null) return "?";
        String name = enumName.replace("WAYPOINT", "WP")
                              .replace("SAFE_DESCENDING", "SAFE DESC");
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            if (part.length() <= 3) {
                sb.append(part);
            } else {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
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
            case "CIRCLING", "REFUELING" -> ORANGE_COLOR;
            case "IDLE" -> DIM_COLOR;
            default -> TEXT_COLOR;
        };
    }

    // ===== BUILDER HELPERS =====

    private static HudLine text(String text, int color) {
        return new HudLine(text, color, 0);
    }

    private static HudLine text(String text, int color, double indent) {
        return new HudLine(text, color, indent);
    }

    private static HudLine separator() {
        return new HudLine(LineType.SEPARATOR);
    }

    private static HudLine multiColor(double indent, TextSegment... segments) {
        return new HudLine(indent, segments);
    }

    private static TextSegment seg(String text, int color) {
        return new TextSegment(text, color);
    }

    // ===== INNER CLASSES =====

    private enum LineType {
        TEXT,
        SEPARATOR,
        SPACER
    }

    private static class TextSegment {
        final String text;
        final int color;

        TextSegment(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private static class HudLine {
        final String text;
        final int color;
        final double indent;
        final LineType type;
        final TextSegment[] segments;

        HudLine(String text, int color, double indent) {
            this.text = text;
            this.color = color;
            this.indent = indent;
            this.type = LineType.TEXT;
            this.segments = null;
        }

        HudLine(double indent, TextSegment... segments) {
            this.text = "";
            this.color = 0;
            this.indent = indent;
            this.type = LineType.TEXT;
            this.segments = segments;
        }

        HudLine(LineType type) {
            this.text = "";
            this.color = 0;
            this.indent = 0;
            this.type = type;
            this.segments = null;
        }
    }
}
