package com.basefinder.logger;

import com.basefinder.util.BaseRecord;
import net.minecraft.client.Minecraft;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.utils.ChatUtils;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logs found bases to file and chat.
 */
public class BaseLogger {

    private final List<BaseRecord> records = new ArrayList<>();
    private Path logFile;
    private boolean logToChat = true;
    private boolean logToFile = true;

    public BaseLogger() {
        try {
            Path configDir = RusherHackAPI.getConfigPath();
            Path pluginDir = configDir.resolve("basefinder");
            Files.createDirectories(pluginDir);
            logFile = pluginDir.resolve("bases.log");
        } catch (IOException e) {
            // Fallback to working directory
            logFile = Path.of("basefinder_bases.log");
        }
    }

    /**
     * Log a newly found base.
     */
    public void logBase(BaseRecord record) {
        records.add(record);

        if (logToChat) {
            ChatUtils.print("[BaseFinder] " + record.toShortString());
        }

        if (logToFile) {
            writeToFile(record);
        }
    }

    private void writeToFile(BaseRecord record) {
        try {
            Files.writeString(logFile, record.toLogLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Silent fail, don't spam errors
        }
    }

    /**
     * Export all recorded bases to a file.
     */
    public void exportAll(String filename) {
        try {
            Path configDir = RusherHackAPI.getConfigPath();
            Path exportFile = configDir.resolve("basefinder").resolve(filename);
            StringBuilder sb = new StringBuilder();
            sb.append("=== BaseFinder Export ===\n");
            sb.append("Total bases found: ").append(records.size()).append("\n\n");

            for (BaseRecord record : records) {
                sb.append(record.toLogLine()).append("\n");
            }

            Files.writeString(exportFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            ChatUtils.print("[BaseFinder] Exported " + records.size() + " bases to " + exportFile);
        } catch (IOException e) {
            ChatUtils.print("[BaseFinder] Failed to export: " + e.getMessage());
        }
    }

    public List<BaseRecord> getRecords() { return Collections.unmodifiableList(records); }
    public int getCount() { return records.size(); }
    public void setLogToChat(boolean v) { this.logToChat = v; }
    public void setLogToFile(boolean v) { this.logToFile = v; }

    public void clear() {
        records.clear();
    }
}
