package com.basefinder.persistence;

import com.basefinder.util.BaseRecord;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.rusherhack.client.api.utils.ChatUtils;

import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    /**
     * Executor dédié aux saves : single-thread, daemon, file d'attente illimitée.
     * Audit/03 §4 : les saves sur game thread causaient des stalls 40-500 ms.
     */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BaseFinder-StateSave");
        t.setDaemon(true);
        return t;
    });

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
     * Save scanned chunk positions to binary file, off the game thread.
     * Le caller doit fournir un snapshot {@code long[]} (packed x|z) pour éviter
     * toute mutation concurrente pendant l'écriture. Format : int count,
     * puis count×long. ~8 octets/chunk vs ~16 avec l'ancien format pair-int.
     */
    public void saveScannedChunks(long[] snapshot) {
        if (chunksFile == null || snapshot == null || snapshot.length == 0) return;
        final Path target = chunksFile;
        final long[] data = snapshot;
        saveExecutor.submit(() -> writeScannedChunksTo(target, data));
    }

    private static void writeScannedChunksTo(Path target, long[] data) {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target)))) {
            dos.writeInt(data.length);
            for (long packed : data) {
                dos.writeLong(packed);
            }
            LOGGER.info("[StateManager] Saved {} scanned chunks to disk (async)", data.length);
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to save scanned chunks: {}", e.getMessage());
        }
    }

    /**
     * Load scanned chunk positions from binary file.
     * Détecte et migre l'ancien format {@code (int x, int z)} vers le nouveau
     * {@code long packed} en lisant la taille fichier (transparent pour l'utilisateur).
     */
    public long[] loadScannedChunks() {
        if (chunksFile == null || !Files.exists(chunksFile)) return new long[0];
        try {
            long fileSize = Files.size(chunksFile);
            try (DataInputStream dis = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(chunksFile)))) {
                int count = dis.readInt();
                if (count <= 0) return new long[0];
                long[] result = new long[count];
                long expectedNew = 4L + (long) count * 8L;
                if (fileSize == expectedNew) {
                    for (int i = 0; i < count; i++) {
                        result[i] = dis.readLong();
                    }
                } else {
                    // Ancien format : pairs (int x, int z) — migration transparente
                    for (int i = 0; i < count; i++) {
                        int x = dis.readInt();
                        int z = dis.readInt();
                        result[i] = (((long) x) << 32) | (z & 0xFFFFFFFFL);
                    }
                    LOGGER.info("[StateManager] Migrated {} chunks from legacy int-pair format", count);
                }
                LOGGER.info("[StateManager] Loaded {} scanned chunks from disk", count);
                return result;
            }
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to load scanned chunks: {}", e.getMessage());
            return new long[0];
        }
    }

    /**
     * Save the current session state.
     */
    public void saveState(List<BaseRecord> bases, int waypointIndex, double distanceTraveled,
                          int chunksScanned, String searchMode, int centerX, int centerZ,
                          long uptimeSeconds) {
        if (stateFile == null) {
            LOGGER.warn("[StateManager] Cannot save: state directory not initialized");
            return;
        }

        // Snapshot défensif : copie immuable des bases pour éviter mutation pendant l'écriture
        final List<BaseRecord> basesCopy = new ArrayList<>(bases);
        final Path target = stateFile;
        final int wp = waypointIndex;
        final double dist = distanceTraveled;
        final int chunks = chunksScanned;
        final String mode = searchMode;
        final int cx = centerX;
        final int cz = centerZ;
        final long upSec = uptimeSeconds;
        lastSaveTime = System.currentTimeMillis();

        saveExecutor.submit(() -> writeStateTo(target, basesCopy, wp, dist, chunks, mode, cx, cz, upSec));
    }

    private static void writeStateTo(Path target, List<BaseRecord> bases, int waypointIndex,
                                     double distanceTraveled, int chunksScanned, String searchMode,
                                     int centerX, int centerZ, long uptimeSeconds) {
        try {
            Properties props = new Properties();
            props.setProperty("waypointIndex", String.valueOf(waypointIndex));
            props.setProperty("distanceTraveled", String.valueOf(distanceTraveled));
            props.setProperty("chunksScanned", String.valueOf(chunksScanned));
            props.setProperty("searchMode", searchMode);
            props.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));
            props.setProperty("centerX", String.valueOf(centerX));
            props.setProperty("centerZ", String.valueOf(centerZ));
            props.setProperty("uptimeSeconds", String.valueOf(uptimeSeconds));
            props.setProperty("baseCount", String.valueOf(bases.size()));

            StringBuilder basesStr = new StringBuilder();
            for (BaseRecord base : bases) {
                basesStr.append(String.format("%d,%d,%d,%s,%.1f,%d,%d,%d,%s\n",
                        base.getPosition().getX(), base.getPosition().getY(), base.getPosition().getZ(),
                        base.getType().name(), base.getScore(),
                        base.getPlayerBlockCount(), base.getStorageCount(), base.getShulkerCount(),
                        base.getNotes() != null ? URLEncoder.encode(base.getNotes(), StandardCharsets.UTF_8) : ""));
            }
            props.setProperty("bases", basesStr.toString());

            try (OutputStream out = Files.newOutputStream(target)) {
                props.store(out, "BaseFinder Session State");
            }

            LOGGER.info("[StateManager] State saved (async): {} bases, WP {}, {} chunks",
                    bases.size(), waypointIndex, chunksScanned);
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to save state: {}", e.getMessage());
        }
    }

    /**
     * Drain et arrête le thread save. À appeler dans onUnload pour ne pas perdre
     * un dernier save pending. Bloque max 5 secondes.
     */
    public void shutdown() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[StateManager] Save executor did not terminate within 5s; forcing shutdown");
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
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
            data.uptimeSeconds = Long.parseLong(props.getProperty("uptimeSeconds", "0"));

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
                                record.setNotes(URLDecoder.decode(parts[8], StandardCharsets.UTF_8));
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

    public void saveDiscordWebhook(String url) {
        if (stateDir == null) return;
        try {
            Path webhookFile = stateDir.resolve("discord_webhook.txt");
            if (url == null || url.isEmpty()) {
                Files.deleteIfExists(webhookFile);
            } else {
                Files.writeString(webhookFile, url);
            }
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to save webhook: {}", e.getMessage());
        }
    }

    public String loadDiscordWebhook() {
        if (stateDir == null) return "";
        try {
            Path webhookFile = stateDir.resolve("discord_webhook.txt");
            if (Files.exists(webhookFile)) {
                return Files.readString(webhookFile).trim();
            }
        } catch (IOException e) {
            LOGGER.error("[StateManager] Failed to load webhook: {}", e.getMessage());
        }
        return "";
    }

    public String getWebhookFilePath() {
        return stateDir != null ? stateDir.resolve("discord_webhook.txt").toString() : "";
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
        public long uptimeSeconds = 0;
        public List<BaseRecord> bases = new ArrayList<>();
    }
}
