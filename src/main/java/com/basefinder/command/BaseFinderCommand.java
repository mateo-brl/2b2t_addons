package com.basefinder.command;

import com.basefinder.modules.BaseFinderModule;
import com.basefinder.util.BaseRecord;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
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
        return Lang.t("Commands: status, bases, goto, modes, export, pause, resume, skip, clear",
                       "Commandes : status, bases, goto, modes, export, pause, resume, skip, clear");
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

    @CommandExecutor(subCommand = "clear")
    private String clear() {
        BaseFinderModule module = getModule();
        if (module == null) return Lang.t("BaseHunter module not found!", "Module BaseHunter introuvable !");
        module.getBaseLogger().clear();
        module.getScanner().reset();
        return Lang.t("Cleared all data.", "Données effacées.");
    }

    private BaseFinderModule getModule() {
        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (module instanceof BaseFinderModule bf) return bf;
        return null;
    }
}
