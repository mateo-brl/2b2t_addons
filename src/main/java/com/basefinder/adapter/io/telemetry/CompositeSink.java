package com.basefinder.adapter.io.telemetry;

import com.basefinder.application.telemetry.TelemetrySink;
import com.basefinder.domain.event.BotEvent;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fan-out vers plusieurs sinks. Une exception dans un sink est avalée et
 * loguée — les autres sinks restent appelés (contrat publish-never-throws).
 */
public final class CompositeSink implements TelemetrySink {

    private static final Logger LOGGER = LoggerFactory.getLogger("BaseFinder.Telemetry");

    private final List<TelemetrySink> sinks;

    public CompositeSink(List<TelemetrySink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void publish(BotEvent event) {
        for (TelemetrySink sink : sinks) {
            try {
                sink.publish(event);
            } catch (RuntimeException e) {
                LOGGER.warn("Sink {} threw on publish: {}", sink.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        for (TelemetrySink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                LOGGER.warn("Sink {} threw on close: {}", sink.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
