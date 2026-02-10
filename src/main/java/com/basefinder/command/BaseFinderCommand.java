package com.basefinder.command;

import com.basefinder.modules.BaseFinderModule;
import com.basefinder.util.BaseRecord;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.client.api.feature.module.IModule;
import org.rusherhack.core.command.annotations.CommandExecutor;

import java.util.List;
import java.util.Optional;

/**
 * Command interface for BaseFinder.
 * Usage: *basefinder <subcommand>
 */
public class BaseFinderCommand extends Command {

    public BaseFinderCommand() {
        super("basefinder", "Control the BaseFinder module");
    }

    @CommandExecutor
    private String base() {
        return "BaseFinder commands: status, bases, export, pause, resume, skip, clear";
    }

    @CommandExecutor(subCommand = "status")
    private String status() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseFinder module not found!";
        if (!module.isToggled()) return "BaseFinder is OFF";

        return String.format(
                "State: %s | Chunks scanned: %d | Bases found: %d | Waypoint: %d/%d | Distance: %.0f",
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
        if (module == null) return "BaseFinder module not found!";

        List<BaseRecord> records = module.getBaseLogger().getRecords();
        if (records.isEmpty()) return "No bases found yet.";

        StringBuilder sb = new StringBuilder("Found bases (" + records.size() + "):\n");
        int count = 0;
        for (BaseRecord record : records) {
            sb.append("  ").append(record.toShortString()).append("\n");
            count++;
            if (count >= 20) {
                sb.append("  ... and ").append(records.size() - 20).append(" more");
                break;
            }
        }
        return sb.toString();
    }

    @CommandExecutor(subCommand = "export")
    @CommandExecutor.Argument("string")
    private String export(Optional<String> filename) {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseFinder module not found!";

        String file = filename.orElse("bases_export.txt");
        module.getBaseLogger().exportAll(file);
        return "Exporting to " + file;
    }

    @CommandExecutor(subCommand = "pause")
    private String pause() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseFinder module not found!";
        module.pause();
        return "BaseFinder paused.";
    }

    @CommandExecutor(subCommand = "resume")
    private String resume() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseFinder module not found!";
        module.resume();
        return "BaseFinder resumed.";
    }

    @CommandExecutor(subCommand = "skip")
    private String skip() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseFinder module not found!";
        module.skipWaypoint();
        return "Skipped to next waypoint.";
    }

    @CommandExecutor(subCommand = "clear")
    private String clear() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseFinder module not found!";
        module.getBaseLogger().clear();
        module.getScanner().reset();
        return "Cleared all data.";
    }

    private BaseFinderModule getModule() {
        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseFinder").orElse(null);
        if (module instanceof BaseFinderModule bf) return bf;
        return null;
    }
}
