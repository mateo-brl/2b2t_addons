package com.basefinder.adapter.io.telemetry;

import com.basefinder.application.telemetry.TelemetrySink;
import com.basefinder.domain.event.BotEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TelemetrySink HTTP : POST chaque event au backend en NDJSON via une queue
 * non-bloquante consommée par un thread daemon.
 *
 * Contrat :
 * - {@link #publish} ne bloque jamais : queue bornée + politique drop oldest.
 *   Le tick MC reste protégé même si le backend est down ou lent.
 * - Échecs HTTP logués en {@code WARN} mais ne crashent pas (cf. contrat
 *   {@link TelemetrySink}).
 *
 * Format wire : {@code POST /v1/ingest} avec body {@code application/x-ndjson},
 * une ligne JSON par event (même format que {@link NdjsonFileSink}). Pour le
 * MVP : 1 event/POST, batching à venir si latence pose problème.
 *
 * Audit/05 §5 étape 7 ("WebSocketSink" — mais HTTP suffit pour le MVP, on passera
 * en streaming WebSocket / SSE quand le dashboard temps-réel arrivera).
 */
public final class HttpJsonLineSink implements TelemetrySink {

    private static final Logger LOGGER = LoggerFactory.getLogger("BaseFinder.HttpSink");

    private static final int QUEUE_CAPACITY = 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final URI ingestUri;
    private final HttpClient httpClient;
    private final BlockingQueue<String> queue;
    private final Thread worker;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running = true;

    public HttpJsonLineSink(String backendBaseUrl) {
        this.ingestUri = URI.create(stripTrailingSlash(backendBaseUrl) + "/v1/ingest");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.worker = new Thread(this::runLoop, "BaseFinder-HttpSink");
        this.worker.setDaemon(true);
        this.worker.start();
        LOGGER.info("HTTP telemetry sink started → {}", ingestUri);
    }

    @Override
    public void publish(BotEvent event) {
        String line;
        try {
            line = EventSerializer.toNdjsonLine(event);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to serialize event (type={}, seq={}): {}",
                    event.type(), event.seq(), e.getMessage());
            return;
        }

        // Drop-newest policy on overflow: simpler than drop-oldest and never blocks.
        if (!queue.offer(line)) {
            long total = dropped.incrementAndGet();
            if (total % 100 == 1) {
                LOGGER.warn("HTTP sink queue full — dropped {} events so far", total);
            }
        }
    }

    private void runLoop() {
        while (running) {
            String line;
            try {
                line = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            sendOne(line);
        }
    }

    private void sendOne(String ndjsonLine) {
        HttpRequest request = HttpRequest.newBuilder(ingestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(ndjsonLine + "\n"))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                long total = sent.incrementAndGet();
                if (total == 1 || total % 500 == 0) {
                    LOGGER.info("HTTP sink: {} events sent OK (last status {})", total, status);
                }
            } else {
                long total = failed.incrementAndGet();
                if (total % 50 == 1) {
                    LOGGER.warn("HTTP sink: backend returned {} ({} failures so far)", status, total);
                }
            }
        } catch (Exception e) {
            long total = failed.incrementAndGet();
            if (total % 50 == 1) {
                LOGGER.warn("HTTP sink request failed ({} so far): {}", total, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }

    public long sentCount() { return sent.get(); }
    public long failedCount() { return failed.get(); }
    public long droppedCount() { return dropped.get(); }
    public int queuedCount() { return queue.size(); }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
