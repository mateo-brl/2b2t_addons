package com.basefinder.command;

import com.basefinder.modules.BaseFinderModule;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.client.api.feature.hud.HudElement;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.core.command.annotations.CommandExecutor;

import java.util.List;

/**
 * Command interface for BaseFinder.
 * Usage: *basefinder <subcommand>
 */
public class BaseFinderCommand extends Command {

    public BaseFinderCommand() {
        super("basefinder", "Control BaseFinder module");
    }

    @CommandExecutor
    private String base() {
        return Lang.t("Commands: status, bases, goto, modes, zone, export, waypoints, pause, resume, skip, clear, resethud",
                       "Commandes : status, bases, goto, modes, zone, export, waypoints, pause, resume, skip, clear, resethud");
    }

    @CommandExecutor(subCommand = "status")
    private String status() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");
        if (!module.isToggled()) return Lang.t("BaseHunter is OFF", "BaseHunter est DÉSACTIVÉ");

        return String.format(
                "State: %s | Chunks: %d | Bases: %d | WP: %d/%d | Dist: %.0f",
                module.getState().name(),
                module.getScanner().getScannedCount(),
                module.getBaseLogger().getCount(),
                module.getNavigation().getCurrentWaypointIndex() + 1,
                module.getNavigation().getWaypointCount(),
                module.getNavigation().getTotalDistanceTraveled()
        );
    }

    @CommandExecutor(subCommand = "bases")
    private String bases() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");

        List<BaseRecord> records = module.getBaseLogger().getRecords();
        if (records.isEmpty()) return Lang.t("No bases found yet.", "Aucune base trouvée.");

        StringBuilder sb = new StringBuilder(Lang.t("Found bases (", "Bases trouvées (") + records.size() + "):\n");
        int count = 0;
        for (BaseRecord record : records) {
            count++;
            sb.append("  #").append(count).append(" ").append(record.toShortString()).append("\n");
            if (count >= 20) {
                sb.append("  ... ").append(Lang.t("and ", "et ")).append(records.size() - 20).append(Lang.t(" more", " de plus"));
                break;
            }
        }
        return sb.toString();
    }

    @CommandExecutor(subCommand = "goto")
    private String gotoBase() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");

        List<BaseRecord> records = module.getBaseLogger().getRecords();
        if (records.isEmpty()) return Lang.t("No bases found yet.", "Aucune base trouvée.");

        BaseRecord last = records.get(records.size() - 1);
        BlockPos pos = last.getPosition();
        int x = pos.getX();
        int z = pos.getZ();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.execute(() -> mc.setScreen(
                    new net.minecraft.client.gui.screens.ChatScreen("#goto " + x + " " + z)));
        }

        return Lang.t("Press Enter to navigate to ", "Appuyez sur Entrée pour naviguer vers ") + last.toShortString() + " via Baritone";
    }

    @CommandExecutor(subCommand = "modes")
    private String modes() {
        if (Lang.isFrench()) {
            return """
                    Modes de recherche disponibles (dans les paramètres BaseHunter) :
                      SPIRAL   - Spirale depuis votre position (défaut)
                      HIGHWAYS - Suivre les 8 autoroutes (cardinales + diagonales)
                      RANDOM   - Positions aléatoires dans une plage de distance
                      RING     - Cercle à un rayon fixe depuis votre position""";
        } else {
            return """
                    Available search modes (set in BaseHunter settings):
                      SPIRAL   - Spiral outward from your position (default)
                      HIGHWAYS - Follow all 8 highways (cardinal + diagonal)
                      RANDOM   - Random positions within min/max distance range
                      RING     - Circle at a set radius from your position""";
        }
    }

    @CommandExecutor(subCommand = "export")
    @CommandExecutor.Argument({"filename"})
    private String export(String filename) {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");

        String file = (filename != null && !filename.isEmpty()) ? filename : "bases_export.txt";
        module.getBaseLogger().exportAll(file);
        return Lang.t("Exporting to ", "Export vers ") + file;
    }

    @CommandExecutor(subCommand = "pause")
    private String pause() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");
        module.pause();
        return Lang.t("BaseHunter paused.", "BaseHunter en pause.");
    }

    @CommandExecutor(subCommand = "resume")
    private String resume() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");
        module.resume();
        return Lang.t("BaseHunter resumed.", "BaseHunter repris.");
    }

    @CommandExecutor(subCommand = "skip")
    private String skip() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");
        module.skipWaypoint();
        return Lang.t("Skipped to next waypoint.", "Sauté au waypoint suivant.");
    }

    @CommandExecutor(subCommand = "waypoints")
    @CommandExecutor.Argument({"format"})
    private String waypoints(String format) {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");

        String fmt = (format != null && !format.isEmpty()) ? format : "all";
        module.exportWaypoints(fmt);
        return Lang.t("Exporting waypoints (format: " + fmt + ")", "Export waypoints (format : " + fmt + ")");
    }

    @CommandExecutor(subCommand = "zone")
    @CommandExecutor.Argument({"coords"})
    private String zone(String coords) {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");

        if (coords == null || coords.isEmpty()) {
            int[] bounds = module.getZoneBounds();
            return Lang.t("Current zone: X[%d to %d] Z[%d to %d]\nUsage: *basefinder zone <minX>,<maxX>,<minZ>,<maxZ>",
                          "Zone actuelle : X[%d à %d] Z[%d à %d]\nUsage : *basefinder zone <minX>,<maxX>,<minZ>,<maxZ>")
                    .formatted(bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        try {
            String[] parts = coords.split(",");
            if (parts.length != 4) {
                return Lang.t("Usage: *basefinder zone 10000,500000,10000,500000",
                              "Usage : *basefinder zone 10000,500000,10000,500000");
            }
            int x1 = Integer.parseInt(parts[0].trim());
            int x2 = Integer.parseInt(parts[1].trim());
            int z1 = Integer.parseInt(parts[2].trim());
            int z2 = Integer.parseInt(parts[3].trim());
            module.setZoneBounds(x1, x2, z1, z2);
            return Lang.t("Zone set: X[%d to %d] Z[%d to %d]",
                          "Zone définie : X[%d à %d] Z[%d à %d]")
                    .formatted(x1, x2, z1, z2);
        } catch (NumberFormatException e) {
            return Lang.t("Invalid numbers! Usage: *basefinder zone 10000,500000,10000,500000",
                          "Nombres invalides ! Usage : *basefinder zone 10000,500000,10000,500000");
        }
    }

    @CommandExecutor(subCommand = "clear")
    private String clear() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");
        module.getBaseLogger().clear();
        module.getScanner().reset();
        return Lang.t("Cleared all data.", "Données effacées.");
    }

    @CommandExecutor(subCommand = "resethud")
    private String resethud() {
        HudElement hud = (HudElement) RusherHackAPI.getHudManager().getFeature("BaseFinderHud").orElse(null);
        if (hud == null) return Lang.t("HUD element not found!", "Élément HUD introuvable !");

        hud.setX(5);
        hud.setY(5);
        return Lang.t("HUD position reset to top-left (5, 5).", "Position du HUD réinitialisée en haut à gauche (5, 5).");
    }

    private BaseFinderModule getModule() {
        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (module instanceof BaseFinderModule bf) return bf;
        return null;
    }
}
