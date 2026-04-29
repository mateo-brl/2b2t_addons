package com.basefinder.adapter.io.telemetry;

import com.basefinder.domain.event.BaseFound;
import com.basefinder.domain.event.BotTick;
import com.basefinder.domain.scan.BaseType;
import com.basefinder.domain.world.ChunkId;
import com.basefinder.domain.world.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NdjsonFileSinkTest {

    @Test
    void publish_writesOneLinePerEvent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("telemetry.ndjson");
        try (NdjsonFileSink sink = new NdjsonFileSink(file)) {
            sink.publish(new BotTick(0, 1, 100, 20, 20.0, 0, 0, false, "IDLE", 0, 0));
            sink.publish(new BotTick(1, 2, 101, 19, 19.8, 50, 0, false, "IDLE", 0, 0));
            sink.publish(new BaseFound(2, 3,
                    new ChunkId(7, 7, Dimension.OVERWORLD),
                    BaseType.STASH, 50.0, 112, 64, 112));
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"bot_tick\""));
        assertTrue(lines.get(0).contains("\"seq\":0"));
        assertTrue(lines.get(2).contains("\"type\":\"base_found\""));
        assertTrue(lines.get(2).contains("\"base_type\":\"STASH\""));
    }

    @Test
    void appendsExistingFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("telemetry.ndjson");
        Files.writeString(file, "PRIOR\n");

        try (NdjsonFileSink sink = new NdjsonFileSink(file)) {
            sink.publish(new BotTick(0, 1, 100, 20, 20.0, 0, 0, false, "IDLE", 0, 0));
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertEquals("PRIOR", lines.get(0));
        assertTrue(lines.get(1).contains("\"type\":\"bot_tick\""));
    }

    @Test
    void createsParentDirectoryLazily(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested").resolve("deeper").resolve("telemetry.ndjson");
        assertTrue(!Files.exists(file.getParent()));

        try (NdjsonFileSink sink = new NdjsonFileSink(file)) {
            sink.publish(new BotTick(0, 1, 0, 0, 0, 0, 0, false, "IDLE", 0, 0));
        }

        assertTrue(Files.exists(file));
    }
}
