package com.basefinder.persistence;

import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.utils.ChatUtils;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persists scan state to disk for crash recovery and session resumption.
 *
 * Saves:
 * - Found bases (positions, types, scores)
 * - Current waypoint index
 * - Total distance traveled
 * - Scanned chunk count
 * - Uptime statistics
 */
public class StateManager {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("StateManager");
    private Path stateDir;
    private Path stateFile;
    private Path chunksFile;
    private int saveIntervalSeconds = 300; // Save every 5 minutes
    private long lastSaveTime = 0;
    private int tickCounter = 0;

    public StateManager() {
        try {
            Path mcDir = Minecraft.getInstance().gameDirectory.toPath();
            stateDir = mcDir.resolve("rusherhack").resolve("basefinder").resolve("state");
            Files.createDirectories(stateDir);
            stateFile = stateDir.resolve("session.dat");
            chunksFile = stateDir.resolve("scanned_chunks.dat");
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to create state directory: {}", e.getMessage());
        }
    }

    /**
     * Called every tick to check if we should auto-save.
     */
    public void tick() {
        tickCounter++;
        if (tickCounter % 20 != 0) return; // Check every second

        long now = System.currentTimeMillis();
        if (now - lastSaveTime >= saveIntervalSeconds * 1000L) {
            // Auto-save will be triggered by the module
            lastSaveTime = now;
        }
    }

    public boolean shouldAutoSave() {
        long now = System.currentTimeMillis();
        return now - lastSaveTime >= saveIntervalSeconds * 1000L;
    }

    /**
     * Save the current session state.
     */
    public void saveState(List<BaseRecord> bases, int waypointIndex, double distanceTraveled,
                          int chunksScanned, String searchMode, int centerX, int centerZ) {
        if (stateFile == null) {
            LOGGER.warn("[StateManager] Cannot save: state directory not initialized");
            return;
        }
        try {
            Properties props = new Properties();
            props.setProperty("waypointIndex", String.valueOf(waypointIndex));
            props.setProperty("distanceTraveled", String.valueOf(distanceTraveled));
            props.setProperty("chunksScanned", String.valueOf(chunksScanned));
            props.setProperty("searchMode", searchMode);
            props.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));
            props.setProperty("centerX", String.valueOf(centerX));
            props.setProperty("centerZ", String.valueOf(centerZ));
            props.setProperty("baseCount", String.valueOf(bases.size()));

            // Save bases
            StringBuilder basesStr = new StringBuilder();
            for (int i = 0; i < bases.size(); i++) {
                BaseRecord base = bases.get(i);
                basesStr.append(String.format("%d,%d,%d,%s,%.1f,%d,%d,%d,%s\n",
                        base.getPosition().getX(), base.getPosition().getY(), base.getPosition().getZ(),
                        base.getType().name(), base.getScore(),
                        base.getPlayerBlockCount(), base.getStorageCount(), base.getShulkerCount(),
                        base.getNotes() != null ? base.getNotes() : ""));
            }
            props.setProperty("bases", basesStr.toString());

            try (OutputStream out = Files.newOutputStream(stateFile)) {
                props.store(out, "BaseFinder Session State");
            }

            LOGGER.info("[StateManager] State saved: {} bases, WP {}, {} chunks",
                    bases.size(), waypointIndex, chunksScanned);
            lastSaveTime = System.currentTimeMillis();

        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to save state: {}", e.getMessage());
        }
    }

    /**
     * Load the previous session state.
     * Returns a SessionData object with the loaded state, or null if no saved state.
     */
    public SessionData loadState() {
        if (stateFile == null || !Files.exists(stateFile)) return null;

        try {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(stateFile)) {
                props.load(in);
            }

            SessionData data = new SessionData();
            data.waypointIndex = Integer.parseInt(props.getProperty("waypointIndex", "0"));
            data.distanceTraveled = Double.parseDouble(props.getProperty("distanceTraveled", "0"));
            data.chunksScanned = Integer.parseInt(props.getProperty("chunksScanned", "0"));
            data.searchMode = props.getProperty("searchMode", "SPIRAL");
            data.centerX = Integer.parseInt(props.getProperty("centerX", "0"));
            data.centerZ = Integer.parseInt(props.getProperty("centerZ", "0"));

            // Load bases
            String basesStr = props.getProperty("bases", "");
            if (!basesStr.isEmpty()) {
                for (String line : basesStr.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    try {
                        String[] parts = line.split(",", 9);
                        if (parts.length >= 8) {
                            BlockPos pos = new BlockPos(
                                    Integer.parseInt(parts[0]),
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]));
                            BaseType type = BaseType.valueOf(parts[3]);
                            double score = Double.parseDouble(parts[4]);
                            int blocks = Integer.parseInt(parts[5]);
                            int storage = Integer.parseInt(parts[6]);
                            int shulkers = Integer.parseInt(parts[7]);
                            BaseRecord record = new BaseRecord(pos, type, score, blocks, storage, shulkers);
                            if (parts.length > 8 && !parts[8].isEmpty()) {
                                record.setNotes(parts[8]);
                            }
                            data.bases.add(record);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[StateManager] Skipping malformed base record: {}", line);
                    }
                }
            }

            long savedTime = Long.parseLong(props.getProperty("timestamp", "0"));
            long ageMinutes = (System.currentTimeMillis() - savedTime) / 60000;

            ChatUtils.print("[StateManager] " + Lang.t(
                    "Loaded state: " + data.bases.size() + " bases, WP " + data.waypointIndex + " (" + ageMinutes + " min ago)",
                    "État chargé : " + data.bases.size() + " bases, WP " + data.waypointIndex + " (il y a " + ageMinutes + " min)"));

            return data;

        } catch (Exception e) {
            LOGGER.error("[StateManager] Failed to load state: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Delete saved state (fresh start).
     */
    public void clearState() {
        if (stateFile == null) return;
        try {
            Files.deleteIfExists(stateFile);
            if (chunksFile != null) Files.deleteIfExists(chunksFile);
            ChatUtils.print("[StateManager] " + Lang.t("State cleared.", "État effacé."));
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to clear state: {}", e.getMessage());
        }
    }

    public void setSaveInterval(int seconds) { this.saveIntervalSeconds = seconds; }

    /**
     * Data class for loaded session state.
     */
    public static class SessionData {
        public int waypointIndex = 0;
        public double distanceTraveled = 0;
        public int chunksScanned = 0;
        public String searchMode = "SPIRAL";
        public int centerX = 0;
        public int centerZ = 0;
        public List<BaseRecord> bases = new ArrayList<>();
    }
}
