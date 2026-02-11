package com.basefinder.command;

import com.basefinder.modules.BaseFinderModule;
import com.basefinder.util.BaseRecord;
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
        super("basefinder", "Control the BaseFinder module");
    }

    @CommandExecutor
    private String base() {
        return "Commands: status, bases, goto, modes, export, pause, resume, skip, clear";
    }

    @CommandExecutor(subCommand = "status")
    private String status() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";
        if (!module.isToggled()) return "BaseHunter is OFF";

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
        if (module == null) return "BaseHunter module not found!";

        List<BaseRecord> records = module.getBaseLogger().getRecords();
        if (records.isEmpty()) return "No bases found yet.";

        StringBuilder sb = new StringBuilder("Found bases (" + records.size() + "):\n");
        int count = 0;
        for (BaseRecord record : records) {
            count++;
            sb.append("  #").append(count).append(" ").append(record.toShortString()).append("\n");
            if (count >= 20) {
                sb.append("  ... and ").append(records.size() - 20).append(" more");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Navigate to the most recently found base using Baritone.
     * Opens chat with #goto x z pre-filled - press Enter to navigate.
     */
    @CommandExecutor(subCommand = "goto")
    private String gotoBase() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";

        List<BaseRecord> records = module.getBaseLogger().getRecords();
        if (records.isEmpty()) return "No bases found yet.";

        BaseRecord last = records.get(records.size() - 1);
        BlockPos pos = last.getPosition();
        int x = pos.getX();
        int z = pos.getZ();

        // Open chat screen with Baritone command pre-filled
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.execute(() -> mc.setScreen(
                    new net.minecraft.client.gui.screens.ChatScreen("#goto " + x + " " + z)));
        }

        return "Press Enter to navigate to " + last.toShortString() + " via Baritone";
    }

    /**
     * Lists all available search modes with descriptions.
     */
    @CommandExecutor(subCommand = "modes")
    private String modes() {
        return """
                Available search modes (set in BaseHunter settings):
                  SPIRAL   - Spiral outward from your position (default)
                  HIGHWAYS - Follow all 8 highways (cardinal + diagonal)
                  RANDOM   - Random positions within min/max distance range
                  RING     - Circle at a set radius from your position""";
    }

    @CommandExecutor(subCommand = "export")
    @CommandExecutor.Argument({"filename"})
    private String export(String filename) {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";

        String file = (filename != null && !filename.isEmpty()) ? filename : "bases_export.txt";
        module.getBaseLogger().exportAll(file);
        return "Exporting to " + file;
    }

    @CommandExecutor(subCommand = "pause")
    private String pause() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";
        module.pause();
        return "BaseHunter paused.";
    }

    @CommandExecutor(subCommand = "resume")
    private String resume() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";
        module.resume();
        return "BaseHunter resumed.";
    }

    @CommandExecutor(subCommand = "skip")
    private String skip() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";
        module.skipWaypoint();
        return "Skipped to next waypoint.";
    }

    @CommandExecutor(subCommand = "clear")
    private String clear() {
        BaseFinderModule module = getModule();
        if (module == null) return "BaseHunter module not found!";
        module.getBaseLogger().clear();
        module.getScanner().reset();
        return "Cleared all data.";
    }

    private BaseFinderModule getModule() {
        IModule module = RusherHackAPI.getModuleManager().getFeature("BaseHunter").orElse(null);
        if (module instanceof BaseFinderModule bf) return bf;
        return null;
    }
}
