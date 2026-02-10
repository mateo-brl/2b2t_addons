package com.basefinder.logger;

import com.basefinder.util.BaseRecord;
import net.minecraft.client.Minecraft;
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

    private final List<BaseRecord> records = Collections.synchronizedList(new ArrayList<>());
    private Path logFile;
    private boolean logToChat = true;
    private boolean logToFile = true;

    public BaseLogger() {
        try {
            Path minecraftDir = Minecraft.getInstance().gameDirectory.toPath();
            Path pluginDir = minecraftDir.resolve("rusherhack").resolve("basefinder");
            Files.createDirectories(pluginDir);
            logFile = pluginDir.resolve("bases.log");
        } catch (IOException e) {
            logFile = Path.of("basefinder_bases.log");
        }
    }

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
            // Silent fail
        }
    }

    public void exportAll(String filename) {
        try {
            Path exportFile = logFile.getParent().resolve(filename);
            StringBuilder sb = new StringBuilder();
            sb.append("=== BaseFinder Export ===\n");
            sb.append("Total bases found: ").append(records.size()).append("\n\n");

            synchronized (records) {
                for (BaseRecord record : records) {
                    sb.append(record.toLogLine()).append("\n");
                }
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
