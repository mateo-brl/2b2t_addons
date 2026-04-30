package com.basefinder.adapter.io.telemetry;

import com.basefinder.application.telemetry.TelemetrySink;
import com.basefinder.domain.event.BotEvent;
import com.basefinder.domain.event.BotTick;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CompositeSink fans out and survives a sink throwing.
 */
class CompositeSinkTest {

    private static final BotEvent EVT = new BotTick(
            1L, 0L, 0, 64, 0, "overworld", 20, 20.0, 100, 0, false, "IDLE", 0, 0);

    @Test
    void publishFansOutToAllSinks() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        CompositeSink sink = new CompositeSink(List.of(a, b));

        sink.publish(EVT);

        assertEquals(1, a.events.size());
        assertEquals(1, b.events.size());
    }

    @Test
    void publishContinuesAfterFirstSinkThrows() {
        Recorder good = new Recorder();
        TelemetrySink throwing = e -> { throw new RuntimeException("boom"); };
        CompositeSink sink = new CompositeSink(List.of(throwing, good));

        sink.publish(EVT);

        assertEquals(1, good.events.size(), "later sinks should still receive event");
    }

    @Test
    void closeCallsCloseOnAllSinks() {
        AutoCloseRecorder a = new AutoCloseRecorder();
        AutoCloseRecorder b = new AutoCloseRecorder();
        CompositeSink sink = new CompositeSink(List.of(a, b));

        sink.close();

        assertTrue(a.closed);
        assertTrue(b.closed);
    }

    private static class Recorder implements TelemetrySink {
        final List<BotEvent> events = new ArrayList<>();
        @Override public void publish(BotEvent event) { events.add(event); }
    }

    private static class AutoCloseRecorder implements TelemetrySink {
        boolean closed;
        @Override public void publish(BotEvent event) {}
        @Override public void close() { closed = true; }
    }
}
