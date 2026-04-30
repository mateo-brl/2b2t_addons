package com.basefinder.logger;

import com.basefinder.adapter.io.screenshots.ScreenshotUploader;
import com.basefinder.application.telemetry.EmitBaseFoundUseCase;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import com.basefinder.util.BaseRecord;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.util.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.rusherhack.client.api.utils.ChatUtils;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logs found bases to file and chat.
 * Alerts include clickable [GOTO] and [COPY] buttons for Baritone integration.
 * Auto-screenshot feature captures the screen when a base is detected.
 */
public class BaseLogger {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("BaseLogger");
    private static final DateTimeFormatter SCREENSHOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final List<BaseRecord> records = Collections.synchronizedList(new ArrayList<>());
    private Path logFile;
    /** Parent directory passed to {@link Screenshot#grab} ; the API appends "screenshots/" itself. */
    private Path screenshotParentDir;
    private Path screenshotDir;
    private boolean logToChat = true;
    private boolean logToFile = true;
    private boolean autoScreenshot = false;
    private final DiscordNotifier discordNotifier;
    private final EmitBaseFoundUseCase emitBaseFound;
    private ScreenshotUploader screenshotUploader; // optional, set by registry
    private Dimension currentDimension = Dimension.OVERWORLD;

    public BaseLogger(DiscordNotifier discordNotifier, EmitBaseFoundUseCase emitBaseFound) {
        this.discordNotifier = discordNotifier;
        this.emitBaseFound = emitBaseFound;
        try {
            Path minecraftDir = Minecraft.getInstance().gameDirectory.toPath();
            Path pluginDir = minecraftDir.resolve("rusherhack").resolve("basefinder");
            Files.createDirectories(pluginDir);
            logFile = pluginDir.resolve("bases.log");

            // Screenshot.grab(gameDir, ...) writes to "gameDir/screenshots/" — so we
            // pass pluginDir as the "gameDir" and MC creates the subfolder
            // "<gameDir>/rusherhack/basefinder/screenshots/" itself.
            screenshotParentDir = pluginDir;
            screenshotDir = pluginDir.resolve("screenshots");
            Files.createDirectories(screenshotDir);
        } catch (IOException e) {
            logFile = Path.of("basefinder_bases.log");
        }
    }

    private static final int MAX_RECORDS = 10000;

    public void logBase(BaseRecord record) {
        // Prevent unbounded memory growth on long sessions
        synchronized (records) {
            if (records.size() >= MAX_RECORDS) {
                records.subList(0, 1000).clear(); // Remove oldest 1000
                LOGGER.info("[BaseLogger] Purged oldest 1000 records (limit: {})", MAX_RECORDS);
            }
            records.add(record);
        }

        if (logToChat) {
            sendClickableAlert(record);
        }

        if (logToFile) {
            writeToFile(record);
        }

        // Note : on NE prend PAS de screenshot ici. À la détection, le bot
        // est typiquement en pleine croisière à 200m d'altitude au-dessus
        // de la base — la capture est inutile (ciel + terrain à peine
        // rendu). Les screenshots sont prises pendant le flow d'approach
        // par BaseFinderModule.handleApproachingBase() (vue aérienne +
        // panoramique au sol + détail).

        discordNotifier.notifyBase(record);

        // Telemetry : émet un BaseFound vers le sink (NDJSON local en v1).
        if (emitBaseFound != null) {
            BlockPos pos = record.getPosition();
            ChunkId chunkId = new ChunkId(pos.getX() >> 4, pos.getZ() >> 4, currentDimension);
            emitBaseFound.emit(chunkId, record.getType(), record.getScore(),
                    pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * Indique au logger dans quelle dimension le bot scanne actuellement.
     * Utilisé pour annoter correctement les events {@code BaseFound}.
     */
    public void setCurrentDimension(Dimension dimension) {
        this.currentDimension = dimension;
    }

    /**
     * Clé d'idempotence canonique de la base, identique à
     * {@code BaseFound.idempotencyKey()} côté télémétrie ({@code :}
     * comme séparateur). Sert au backend pour lier la screenshot à
     * l'event base_found.
     */
    private String idempotencyKeyCanonical(BaseRecord record) {
        BlockPos pos = record.getPosition();
        return currentDimension.legacyName()
                + ":" + (pos.getX() >> 4)
                + ":" + (pos.getZ() >> 4)
                + ":" + record.getType().name();
    }

    /**
     * Variante FS-safe ({@code _} au lieu de {@code :}) pour les noms de
     * fichiers locaux. Windows et certains FS rejettent {@code :}.
     */
    private String idempotencyKeySafe(BaseRecord record) {
        return idempotencyKeyCanonical(record).replace(':', '_');
    }

    public void setScreenshotUploader(ScreenshotUploader uploader) {
        this.screenshotUploader = uploader;
    }

    /**
     * Backward-compat : ancienne signature single-shot, conservée pour ne
     * pas casser d'éventuels appelants externes. Délègue avec angle "auto".
     */
    public void takeScreenshot(BaseRecord record) {
        takeScreenshot(record, "auto");
    }

    /**
     * Capture une frame du jeu et l'écrit dans
     * {@code <gameDir>/rusherhack/basefinder/screenshots/<key>_<angle>.png}.
     *
     * - Le filename est canonique : la clé d'idempotence de la base
     *   permet au backend de lier la screenshot à l'event {@code base_found}.
     * - On utilise l'overload {@code Screenshot.grab(File, String, ...)}
     *   qui prend un nom personnalisé, contrairement à l'ancien code qui
     *   construisait le nom en string et l'ignorait totalement (le PNG
     *   atterrissait avec un nom timestamp dans {@code <gameDir>/screenshots/}).
     */
    public void takeScreenshot(BaseRecord record, String angle) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (screenshotParentDir == null) return;

            String safeAngle = angle == null || angle.isBlank() ? "auto" : angle;
            String filename = idempotencyKeySafe(record) + "_" + safeAngle + ".png";
            String canonicalKey = idempotencyKeyCanonical(record);
            long takenAtMs = System.currentTimeMillis();

            // Capture on the next render frame — MC writes the file from
            // the render thread. Once the file is on disk, we hand it to
            // the optional ScreenshotUploader for backend POST.
            mc.execute(() -> {
                try {
                    Screenshot.grab(
                            screenshotParentDir.toFile(),
                            filename,
                            mc.getMainRenderTarget(),
                            (component) -> {
                                LOGGER.info("[BaseLogger] Screenshot {} saved: {}", safeAngle, filename);
                                if (screenshotUploader != null && screenshotDir != null) {
                                    screenshotUploader.upload(
                                            screenshotDir.resolve(filename),
                                            canonicalKey,
                                            safeAngle,
                                            takenAtMs);
                                }
                            }
                    );
                } catch (Exception e) {
                    LOGGER.error("[BaseLogger] Failed to take screenshot: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            LOGGER.error("[BaseLogger] Error scheduling screenshot: {}", e.getMessage());
        }
    }

    /**
     * Sends a clickable chat alert with [GOTO] and [COPY] buttons.
     * [GOTO] fills the chat with #goto x z (Baritone command) - user presses Enter to navigate.
     * [COPY] copies the coordinates to clipboard.
     */
    private void sendClickableAlert(BaseRecord record) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        BlockPos pos = record.getPosition();
        int x = pos.getX();
        int z = pos.getZ();

        // Color based on base type
        ChatFormatting typeColor = switch (record.getType()) {
            case STORAGE -> ChatFormatting.RED;
            case CONSTRUCTION -> ChatFormatting.YELLOW;
            case MAP_ART -> ChatFormatting.LIGHT_PURPLE;
            case TRAIL -> ChatFormatting.AQUA;
            default -> ChatFormatting.WHITE;
        };

        // Score color based on strength
        double score = record.getScore();
        ChatFormatting scoreColor;
        if (score >= 100) {
            scoreColor = ChatFormatting.GREEN; // Very strong detection
        } else if (score >= 50) {
            scoreColor = ChatFormatting.YELLOW; // Good detection
        } else {
            scoreColor = ChatFormatting.GRAY; // Weak detection
        }

        // Build detail string (shulkers, blocks, signs)
        StringBuilder details = new StringBuilder();
        if (record.getShulkerCount() > 0) {
            details.append(record.getShulkerCount()).append(" shulkers ");
        }
        if (record.getPlayerBlockCount() > 0) {
            details.append(record.getPlayerBlockCount()).append(" blocs ");
        }
        if (record.getStorageCount() > 0) {
            details.append(record.getStorageCount()).append(" stockage ");
        }

        // Build freshness tag if available
        String notesTag = "";
        if (record.getNotes() != null && !record.getNotes().isEmpty()) {
            notesTag = " [" + record.getNotes() + "]";
        }

        MutableComponent message = Component.literal("[BaseHunter] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(record.getType().getDisplayName())
                        .withStyle(typeColor, ChatFormatting.BOLD))
                .append(Component.literal(String.format(" @ %d, %d ", x, z))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.format("Score: %.0f ", score))
                        .withStyle(scoreColor, ChatFormatting.BOLD));

        // Add detail counts
        if (!details.isEmpty()) {
            message.append(Component.literal("(" + details.toString().trim() + ") ")
                    .withStyle(ChatFormatting.GRAY));
        }

        // Add freshness/notes tag
        if (!notesTag.isEmpty()) {
            message.append(Component.literal(notesTag + " ")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        message.append(Component.literal(Lang.t("[GOTO]", "[ALLER]"))
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withBold(true)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.SUGGEST_COMMAND,
                                        "#goto " + x + " " + z))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal(Lang.t("Click to navigate with Baritone\n", "Cliquer pour naviguer avec Baritone\n"))
                                                .append(Component.literal("#goto " + x + " " + z)
                                                        .withStyle(ChatFormatting.GRAY))))))
                .append(Component.literal(" "))
                .append(Component.literal(Lang.t("[COPY]", "[COPIER]"))
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.COPY_TO_CLIPBOARD,
                                        x + " " + z))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal(Lang.t("Copy coordinates to clipboard", "Copier les coordonnées"))))));

        mc.player.displayClientMessage(message, false);
    }

    private void writeToFile(BaseRecord record) {
        try {
            Files.writeString(logFile, record.toLogLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Silent fail
        }
    }

    /**
     * Supprime une base de la liste en mémoire et ré-écrit
     * {@code bases.log} sans elle. La saving auto persiste ensuite via
     * {@link com.basefinder.persistence.StateManager} qui consomme
     * {@link #getRecords()} — donc {@code session.dat} reflètera la
     * suppression au prochain save (~5 minutes ou onDisable).
     *
     * @param idempotencyKey clé canonique de la base (cf. {@code BaseFound.idempotencyKey()}).
     * @return {@code true} si une base a été retirée, {@code false} sinon.
     */
    public boolean removeByKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return false;
        BaseRecord toRemove = null;
        synchronized (records) {
            for (BaseRecord r : records) {
                String key = currentDimension.legacyName()
                        + ":" + (r.getPosition().getX() >> 4)
                        + ":" + (r.getPosition().getZ() >> 4)
                        + ":" + r.getType().name();
                if (key.equals(idempotencyKey)) {
                    toRemove = r;
                    break;
                }
            }
            if (toRemove != null) records.remove(toRemove);
        }
        if (toRemove == null) return false;
        rewriteLogFile();
        LOGGER.info("[BaseLogger] Removed base by key: {}", idempotencyKey);
        return true;
    }

    /**
     * Vide {@code bases.log} et le ré-écrit avec les records restants.
     * Appelé après {@link #removeByKey} pour garder le file en cohérence
     * avec la liste en mémoire.
     */
    private void rewriteLogFile() {
        if (logFile == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (records) {
                for (BaseRecord r : records) {
                    sb.append(r.toLogLine()).append("\n");
                }
            }
            Files.writeString(logFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("[BaseLogger] Failed to rewrite log file after removal: {}", e.getMessage());
        }
    }

    public void exportAll(String filename) {
        try {
            Path exportFile = logFile.getParent().resolve(filename);
            StringBuilder sb = new StringBuilder();
            sb.append("=== BaseFinder Export ===\n");
            sb.append(Lang.t("Total bases found: ", "Bases trouvées : ")).append(records.size()).append("\n\n");

            synchronized (records) {
                for (BaseRecord record : records) {
                    sb.append(record.toLogLine()).append("\n");
                }
            }

            Files.writeString(exportFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            ChatUtils.print("[BaseHunter] " + Lang.t("Exported " + records.size() + " bases to ", records.size() + " bases exportées vers ") + exportFile);
        } catch (IOException e) {
            ChatUtils.print("[BaseHunter] " + Lang.t("Failed to export: ", "Échec de l'export : ") + e.getMessage());
        }
    }

    /**
     * Restore a base record from saved state without triggering chat/file/screenshot.
     */
    public void restoreRecord(BaseRecord record) {
        records.add(record);
    }

    public List<BaseRecord> getRecords() { return Collections.unmodifiableList(records); }
    public int getCount() { return records.size(); }
    public void setLogToChat(boolean v) { this.logToChat = v; }
    public void setLogToFile(boolean v) { this.logToFile = v; }
    public void setAutoScreenshot(boolean v) { this.autoScreenshot = v; }
    public void setDiscordWebhook(String url) { discordNotifier.setWebhookUrl(url); }
    public DiscordNotifier getDiscordNotifier() { return discordNotifier; }

    public void clear() {
        records.clear();
    }
}
