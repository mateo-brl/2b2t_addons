package com.basefinder.command;

import com.basefinder.modules.PortalHunterModule;
import com.basefinder.util.Lang;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.command.annotations.CommandExecutor;

/**
 * Command interface for PortalHunter.
 * Usage: *portalhunter <subcommand>
 */
public class PortalHunterCommand extends Command {

    public PortalHunterCommand() {
        super("portalhunter", "Control PortalHunter module");
    }

    @CommandExecutor
    private String base() {
        return Lang.t(
                "Commands: status, zone, sweep, clear, help",
                "Commandes : status, zone, sweep, clear, help");
    }

    @CommandExecutor(subCommand = "help")
    private String help() {
        if (Lang.isFrench()) {
            return """
                    === PortalHunter ===
                    *portalhunter status              - État actuel
                    *portalhunter zone                - Afficher la zone
                    *portalhunter zone <minX>,<maxX>,<minZ>,<maxZ> - Définir la zone Nether
                    *portalhunter sweep <rayon>       - Définir le rayon de sweep Overworld
                    *portalhunter clear               - Effacer les portails visités""";
        }
        return """
                === PortalHunter ===
                *portalhunter status              - Current status
                *portalhunter zone                - Show zone
                *portalhunter zone <minX>,<maxX>,<minZ>,<maxZ> - Set Nether zone
                *portalhunter sweep <radius>      - Set Overworld sweep radius
                *portalhunter clear               - Clear visited portals""";
    }

    @CommandExecutor(subCommand = "status")
    private String status() {
        PortalHunterModule module = getModule();
        if (module == null) return moduleNotFound();
        if (!module.isToggled()) return Lang.t("PortalHunter is OFF", "PortalHunter est DÉSACTIVÉ");

        int[] zone = module.getZoneBounds();
        return String.format(Lang.t(
                "State: %s | Portals: %d | Bases: %d | Zone: %d/%d | Queue: %d | Visited: %d\nZone: X[%d to %d] Z[%d to %d] | Sweep: %d",
                "État : %s | Portails : %d | Bases : %d | Zone : %d/%d | File : %d | Visités : %d\nZone : X[%d à %d] Z[%d à %d] | Sweep : %d"),
                module.getHunterState().name(),
                module.getPortalsVisited(), module.getBasesFound(),
                module.getZoneProgress(), module.getZoneTotal(),
                module.getQueueSize(), module.getVisitedPortalsCount(),
                zone[0], zone[1], zone[2], zone[3],
                module.getSweepRadius());
    }

    @CommandExecutor(subCommand = "zone")
    @CommandExecutor.Argument({"coords"})
    private String zone(String coords) {
        PortalHunterModule module = getModule();
        if (module == null) return moduleNotFound();

        if (coords == null || coords.isEmpty()) {
            int[] bounds = module.getZoneBounds();
            return String.format(Lang.t(
                    "Current zone: X[%d to %d] Z[%d to %d]\nUsage: *portalhunter zone <minX>,<maxX>,<minZ>,<maxZ>",
                    "Zone actuelle : X[%d à %d] Z[%d à %d]\nUsage : *portalhunter zone <minX>,<maxX>,<minZ>,<maxZ>"),
                    bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        try {
            String[] parts = coords.split(",");
            if (parts.length != 4) {
                return Lang.t(
                        "Usage: *portalhunter zone 328000,330000,170000,172000",
                        "Usage : *portalhunter zone 328000,330000,170000,172000");
            }
            int minX = Integer.parseInt(parts[0].trim());
            int maxX = Integer.parseInt(parts[1].trim());
            int minZ = Integer.parseInt(parts[2].trim());
            int maxZ = Integer.parseInt(parts[3].trim());
            module.setZoneBounds(minX, maxX, minZ, maxZ);
            return String.format(Lang.t(
                    "Zone set: X[%d to %d] Z[%d to %d]",
                    "Zone définie : X[%d à %d] Z[%d à %d]"),
                    minX, maxX, minZ, maxZ);
        } catch (NumberFormatException e) {
            return Lang.t(
                    "Invalid numbers! Example: *portalhunter zone 328000,330000,170000,172000",
                    "Nombres invalides ! Exemple : *portalhunter zone 328000,330000,170000,172000");
        }
    }

    @CommandExecutor(subCommand = "sweep")
    @CommandExecutor.Argument({"radius"})
    private String sweep(String radius) {
        PortalHunterModule module = getModule();
        if (module == null) return moduleNotFound();

        if (radius == null || radius.isEmpty()) {
            return Lang.t(
                    "Sweep radius: " + module.getSweepRadius() + " blocks\nUsage: *portalhunter sweep <radius>",
                    "Rayon sweep : " + module.getSweepRadius() + " blocs\nUsage : *portalhunter sweep <rayon>");
        }

        try {
            int r = Integer.parseInt(radius.trim());
            if (r < 100 || r > 3000) {
                return Lang.t("Radius must be between 100 and 3000!", "Le rayon doit être entre 100 et 3000 !");
            }
            module.setSweepRadius(r);
            return Lang.t("Sweep radius set to " + r + " blocks.", "Rayon sweep défini à " + r + " blocs.");
        } catch (NumberFormatException e) {
            return Lang.t("Invalid number!", "Nombre invalide !");
        }
    }

    @CommandExecutor(subCommand = "clear")
    private String clear() {
        PortalHunterModule module = getModule();
        if (module == null) return moduleNotFound();

        module.clearVisitedPortals();
        return Lang.t(
                "Visited portals cleared. All portals will be rescanned.",
                "Portails visités effacés. Tous les portails seront re-scannés.");
    }

    private PortalHunterModule getModule() {
        IModule module = RusherHackAPI.getModuleManager().getFeature("PortalHunter").orElse(null);
        if (module instanceof PortalHunterModule ph) return ph;
        return null;
    }

    private String moduleNotFound() {
        return Lang.t("PortalHunter module not found!", "Module PortalHunter introuvable !");
    }
}
