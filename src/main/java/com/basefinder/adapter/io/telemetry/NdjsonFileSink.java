package com.basefinder.adapter.io.telemetry;

import com.basefinder.application.telemetry.TelemetrySink;
import com.basefinder.domain.event.BotEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sink NDJSON local : écrit chaque event sur une ligne d'un fichier.
 *
 * Append-only, flush par event (cher, mais on est en MVP — l'audit prévoit
 * un async writer en étape 8). Ouvre le fichier paresseusement à la première
 * publication. Échec d'IO logué et avalé : la télémétrie ne doit jamais
 * crasher le bot (contrat du port).
 */
public final class NdjsonFileSink implements TelemetrySink {

    private static final Logger LOGGER = LoggerFactory.getLogger("BaseFinder.Telemetry");

    private final Path filePath;
    private BufferedWriter writer;
    private boolean openFailed;

    public NdjsonFileSink(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public synchronized void publish(BotEvent event) {
        if (openFailed) {
            return;
        }
        try {
            ensureWriterOpen();
            writer.write(EventSerializer.toNdjsonLine(event));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOGGER.warn("Telemetry NDJSON write failed (path={}, type={}, seq={}): {}",
                    filePath, event.type(), event.seq(), e.getMessage());
            // Don't keep retrying if open failed
            if (writer == null) {
                openFailed = true;
            }
        }
    }

    private void ensureWriterOpen() throws IOException {
        if (writer != null) {
            return;
        }
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                LOGGER.warn("Telemetry NDJSON close failed: {}", e.getMessage());
            } finally {
                writer = null;
            }
        }
    }

    public Path filePath() {
        return filePath;
    }
}
