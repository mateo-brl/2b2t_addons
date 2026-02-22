package com.basefinder.logger;

import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
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
    private Path screenshotDir;
    private boolean logToChat = true;
    private boolean logToFile = true;
    private boolean autoScreenshot = false;

    public BaseLogger() {
        try {
            Path minecraftDir = Minecraft.getInstance().gameDirectory.toPath();
            Path pluginDir = minecraftDir.resolve("rusherhack").resolve("basefinder");
            Files.createDirectories(pluginDir);
            logFile = pluginDir.resolve("bases.log");

            // Screenshot directory
            screenshotDir = pluginDir.resolve("screenshots");
            Files.createDirectories(screenshotDir);
        } catch (IOException e) {
            logFile = Path.of("basefinder_bases.log");
        }
    }

    private static final int MAX_RECORDS = 10000;

    public void logBase(BaseRecord record) {
        // Prevent unbounded memory growth on long sessions
        if (records.size() >= MAX_RECORDS) {
            synchronized (records) {
                if (records.size() >= MAX_RECORDS) {
                    records.subList(0, 1000).clear(); // Remove oldest 1000
                    LOGGER.info("[BaseLogger] Purged oldest 1000 records (limit: {})", MAX_RECORDS);
                }
            }
        }
        records.add(record);

        if (logToChat) {
            sendClickableAlert(record);
        }

        if (logToFile) {
            writeToFile(record);
        }

        if (autoScreenshot) {
            takeScreenshot(record);
        }
    }

    /**
     * Takes a screenshot with base coordinates in the filename.
     * Format: base_TYPE_X_Z_TIMESTAMP.png
     */
    public void takeScreenshot(BaseRecord record) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            BlockPos pos = record.getPosition();
            String timestamp = LocalDateTime.now().format(SCREENSHOT_FORMAT);
            String filename = String.format("base_%s_%d_%d_%s",
                    record.getType().name().toLowerCase(),
                    pos.getX(), pos.getZ(),
                    timestamp);

            // Use MC's screenshot API - takes screenshot on next frame render
            mc.execute(() -> {
                try {
                    Screenshot.grab(
                            mc.gameDirectory,
                            mc.getMainRenderTarget(),
                            (component) -> {
                                LOGGER.info("[BaseLogger] Screenshot saved: {}", component.getString());
                                ChatUtils.print("[BaseHunter] " + Lang.t("Screenshot saved!", "Capture d'écran sauvegardée !"));
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

    public void clear() {
        records.clear();
    }
}
