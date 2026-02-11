package com.basefinder.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.utils.ChatUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Exports found bases as waypoints for popular minimap mods.
 *
 * Supported formats:
 * - Xaero's Minimap: waypoints stored in .minecraft/XaeroWaypoints/
 * - VoxelMap: waypoints stored in .minecraft/voxelmap/
 */
public class WaypointExporter {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("WaypointExporter");

    /**
     * Export waypoints in Xaero's Minimap format.
     * Format: waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination
     *
     * Colors: 0=black, 1=dark_blue, 2=dark_green, 3=dark_aqua, 4=dark_red, 5=dark_purple,
     *         6=gold, 7=gray, 8=dark_gray, 9=blue, 10=green, 11=aqua, 12=red, 13=light_purple,
     *         14=yellow, 15=white
     */
    public static void exportXaero(List<BaseRecord> records, String serverName) {
        if (records.isEmpty()) {
            ChatUtils.print("[BaseHunter] " + Lang.t("No bases to export.", "Aucune base à exporter."));
            return;
        }

        try {
            Path mcDir = Minecraft.getInstance().gameDirectory.toPath();
            Path xaeroDir = mcDir.resolve("XaeroWaypoints");
            Path serverDir = xaeroDir.resolve(serverName != null ? serverName : "2b2t.org");
            Path dimDir = serverDir.resolve("dim%0"); // Overworld
            Files.createDirectories(dimDir);

            Path waypointFile = dimDir.resolve("basefinder.txt");
            StringBuilder sb = new StringBuilder();

            // Xaero header
            sb.append("#\n");
            sb.append("#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination\n");
            sb.append("#\n");

            int count = 0;
            for (BaseRecord record : records) {
                count++;
                BlockPos pos = record.getPosition();

                // Color based on type
                int color = switch (record.getType()) {
                    case STORAGE -> 12;      // Red
                    case CONSTRUCTION -> 14;  // Yellow
                    case MAP_ART -> 13;       // Light purple
                    case TRAIL -> 11;         // Aqua
                    default -> 7;             // Gray
                };

                // Initials from type
                String initials = switch (record.getType()) {
                    case STORAGE -> "ST";
                    case CONSTRUCTION -> "BA";
                    case MAP_ART -> "MA";
                    case TRAIL -> "TR";
                    default -> "??";
                };

                String name = record.getType().getDisplayName() + "_" + count
                        + "_" + String.format("%.0f", record.getScore());

                sb.append(String.format("waypoint:%s:%s:%d:%d:%d:%d:false:0:Internal-basefinder-waypoints:false:0:0:false\n",
                        name, initials,
                        pos.getX(), pos.getY(), pos.getZ(),
                        color));
            }

            Files.writeString(waypointFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ChatUtils.print("[BaseHunter] " + Lang.t(
                    "Exported " + count + " waypoints to Xaero's Minimap: " + waypointFile,
                    count + " waypoints exportés vers Xaero's Minimap : " + waypointFile));

        } catch (IOException e) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Xaero export failed: ", "Export Xaero échoué : ") + e.getMessage());
            LOGGER.error("[WaypointExporter] Xaero export error: {}", e.getMessage());
        }
    }

    /**
     * Export waypoints in VoxelMap format.
     * Format: name:x,z,y:enabled:red:green:blue:suffix:world:dimensions
     *
     * VoxelMap stores waypoints in .minecraft/voxelmap/cache/2b2t.org(overworld)/
     */
    public static void exportVoxelMap(List<BaseRecord> records, String serverName) {
        if (records.isEmpty()) {
            ChatUtils.print("[BaseHunter] " + Lang.t("No bases to export.", "Aucune base à exporter."));
            return;
        }

        try {
            Path mcDir = Minecraft.getInstance().gameDirectory.toPath();
            Path pluginDir = mcDir.resolve("rusherhack").resolve("basefinder");
            Files.createDirectories(pluginDir);

            Path waypointFile = pluginDir.resolve("voxelmap_waypoints.txt");
            StringBuilder sb = new StringBuilder();

            // VoxelMap-compatible format
            sb.append("# VoxelMap waypoints exported by BaseFinder\n");
            sb.append("# Import these into VoxelMap manually\n");
            sb.append("# Format: name,x,z,y,enabled,red,green,blue,suffix,world,dimensions\n\n");

            int count = 0;
            for (BaseRecord record : records) {
                count++;
                BlockPos pos = record.getPosition();

                // RGB color based on type
                float r, g, b;
                switch (record.getType()) {
                    case STORAGE -> { r = 1.0f; g = 0.2f; b = 0.2f; }     // Red
                    case CONSTRUCTION -> { r = 1.0f; g = 1.0f; b = 0.2f; } // Yellow
                    case MAP_ART -> { r = 0.8f; g = 0.3f; b = 0.8f; }     // Purple
                    case TRAIL -> { r = 0.2f; g = 0.8f; b = 0.8f; }       // Aqua
                    default -> { r = 0.7f; g = 0.7f; b = 0.7f; }          // Gray
                }

                String name = record.getType().getDisplayName() + "_" + count
                        + " (" + String.format("%.0f", record.getScore()) + ")";

                sb.append(String.format("name:%s,x:%d,z:%d,y:%d,enabled:true,red:%.1f,green:%.1f,blue:%.1f,suffix:,world:,dimensions:overworld#\n",
                        name,
                        pos.getX(), pos.getZ(), pos.getY(),
                        r, g, b));
            }

            Files.writeString(waypointFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            ChatUtils.print("[BaseHunter] " + Lang.t(
                    "Exported " + count + " waypoints (VoxelMap format): " + waypointFile,
                    count + " waypoints exportés (format VoxelMap) : " + waypointFile));

        } catch (IOException e) {
            ChatUtils.print("[BaseHunter] " + Lang.t("VoxelMap export failed: ", "Export VoxelMap échoué : ") + e.getMessage());
            LOGGER.error("[WaypointExporter] VoxelMap export error: {}", e.getMessage());
        }
    }

    /**
     * Export in both formats at once.
     */
    public static void exportAll(List<BaseRecord> records, String serverName) {
        exportXaero(records, serverName);
        exportVoxelMap(records, serverName);
    }
}
