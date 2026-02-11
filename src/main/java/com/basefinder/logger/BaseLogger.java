package com.basefinder.logger;

import com.basefinder.util.BaseRecord;
import com.basefinder.util.BaseType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.rusherhack.client.api.utils.ChatUtils;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logs found bases to file and chat.
 * Alerts include clickable [GOTO] and [COPY] buttons for Baritone integration.
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
            sendClickableAlert(record);
        }

        if (logToFile) {
            writeToFile(record);
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

        MutableComponent message = Component.literal("[BaseHunter] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(record.getType().getDisplayName())
                        .withStyle(typeColor, ChatFormatting.BOLD))
                .append(Component.literal(String.format(" @ %d, %d ", x, z))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.format("(%.0f) ", record.getScore()))
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[GOTO]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withBold(true)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.SUGGEST_COMMAND,
                                        "#goto " + x + " " + z))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to navigate with Baritone\n")
                                                .append(Component.literal("#goto " + x + " " + z)
                                                        .withStyle(ChatFormatting.GRAY))))))
                .append(Component.literal(" "))
                .append(Component.literal("[COPY]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(
                                        ClickEvent.Action.COPY_TO_CLIPBOARD,
                                        x + " " + z))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Copy coordinates to clipboard")))));

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
            sb.append("Total bases found: ").append(records.size()).append("\n\n");

            synchronized (records) {
                for (BaseRecord record : records) {
                    sb.append(record.toLogLine()).append("\n");
                }
            }

            Files.writeString(exportFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            ChatUtils.print("[BaseHunter] Exported " + records.size() + " bases to " + exportFile);
        } catch (IOException e) {
            ChatUtils.print("[BaseHunter] Failed to export: " + e.getMessage());
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
