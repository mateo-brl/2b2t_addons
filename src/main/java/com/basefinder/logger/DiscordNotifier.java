package com.basefinder.logger;

import com.basefinder.util.BaseRecord;
import com.basefinder.util.Lang;
import net.minecraft.core.BlockPos;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends base detection notifications to a Discord channel via webhook.
 */
public class DiscordNotifier {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("DiscordNotifier");

    private String webhookUrl = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordNotifier");
        t.setDaemon(true);
        return t;
    });

    public void setWebhookUrl(String url) {
        this.webhookUrl = url != null ? url.trim() : "";
    }

    public boolean isEnabled() {
        return !webhookUrl.isEmpty();
    }

    /**
     * Send a base detection notification asynchronously.
     */
    public void notifyBase(BaseRecord record) {
        if (!isEnabled()) return;

        String json = buildPayload(record);
        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes());
                }

                int code = conn.getResponseCode();
                if (code == 429) {
                    // Rate limited - wait and retry once
                    Thread.sleep(2000);
                    HttpURLConnection retry = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                    retry.setRequestMethod("POST");
                    retry.setRequestProperty("Content-Type", "application/json");
                    retry.setDoOutput(true);
                    try (OutputStream os = retry.getOutputStream()) {
                        os.write(json.getBytes());
                    }
                    retry.getResponseCode();
                    retry.disconnect();
                } else if (code >= 400) {
                    LOGGER.warn("[DiscordNotifier] Webhook returned HTTP {}", code);
                }

                conn.disconnect();
            } catch (Exception e) {
                LOGGER.error("[DiscordNotifier] Failed to send: {}", e.getMessage());
            }
        });
    }

    private String buildPayload(BaseRecord record) {
        BlockPos pos = record.getPosition();
        String emoji = switch (record.getType()) {
            case STORAGE -> "\uD83D\uDD34";   // red circle
            case STASH -> "\uD83D\uDFE0";     // orange circle
            case CONSTRUCTION -> "\uD83D\uDFE1"; // yellow circle
            case MAP_ART -> "\uD83D\uDFE3";   // purple circle
            case TRAIL -> "\uD83D\uDD35";      // blue circle
            case FARM -> "\uD83D\uDFE2";       // green circle
            case PORTAL -> "\u26AB";           // black circle
            default -> "\u26AA";               // white circle
        };

        StringBuilder desc = new StringBuilder();
        desc.append(String.format("**Score:** %.0f", record.getScore()));
        if (record.getShulkerCount() > 0) {
            desc.append(String.format(" | **Shulkers:** %d", record.getShulkerCount()));
        }
        if (record.getStorageCount() > 0) {
            desc.append(String.format(" | **Storage:** %d", record.getStorageCount()));
        }
        if (record.getPlayerBlockCount() > 0) {
            desc.append(String.format(" | **Blocks:** %d", record.getPlayerBlockCount()));
        }
        if (record.getNotes() != null && !record.getNotes().isEmpty()) {
            desc.append(String.format("\n**Info:** %s", record.getNotes()));
        }

        // JSON escape
        String title = escapeJson(String.format("%s %s @ %d, %d",
                emoji, record.getType().getDisplayName(), pos.getX(), pos.getZ()));
        String description = escapeJson(desc.toString());

        return String.format("""
                {"embeds":[{"title":"%s","description":"%s","color":%d,"footer":{"text":"BaseFinder"}}]}""",
                title, description, getColor(record));
    }

    private int getColor(BaseRecord record) {
        return switch (record.getType()) {
            case STORAGE -> 0xFF0000;     // red
            case STASH -> 0xFF8800;       // orange
            case CONSTRUCTION -> 0xFFFF00; // yellow
            case MAP_ART -> 0xAA00FF;     // purple
            case TRAIL -> 0x00AAFF;       // blue
            case FARM -> 0x00FF00;        // green
            case PORTAL -> 0x333333;      // dark gray
            default -> 0xAAAAAA;          // gray
        };
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t");
    }

    /**
     * Send a critical alert notification asynchronously.
     * Used for health drops, missing elytra/fireworks, etc.
     */
    public void notifyAlert(String title, String description, int color) {
        if (!isEnabled()) return;

        String jsonTitle = escapeJson(title);
        String jsonDesc = escapeJson(description);
        String json = String.format("""
                {"embeds":[{"title":"%s","description":"%s","color":%d,"footer":{"text":"BaseFinder Alert"}}]}""",
                jsonTitle, jsonDesc, color);

        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes());
                }

                int code = conn.getResponseCode();
                if (code == 429) {
                    Thread.sleep(2000);
                    HttpURLConnection retry = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                    retry.setRequestMethod("POST");
                    retry.setRequestProperty("Content-Type", "application/json");
                    retry.setDoOutput(true);
                    try (OutputStream os = retry.getOutputStream()) {
                        os.write(json.getBytes());
                    }
                    retry.getResponseCode();
                    retry.disconnect();
                } else if (code >= 400) {
                    LOGGER.warn("[DiscordNotifier] Alert webhook returned HTTP {}", code);
                }

                conn.disconnect();
            } catch (Exception e) {
                LOGGER.error("[DiscordNotifier] Failed to send alert: {}", e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
