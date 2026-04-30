package com.basefinder.adapter.io.screenshots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uploader des PNG de screenshots vers {@code POST /v1/screenshots}
 * (multipart/form-data) sur le backend.
 *
 * Contrat :
 * - {@link #upload} ne bloque jamais : queue bornée à 64 (drop newest)
 *   + thread daemon worker. Le rendu MC n'est jamais retardé.
 * - Échecs HTTP logués en {@code WARN}, file local jamais supprimé,
 *   donc on peut re-uploader manuellement plus tard.
 * - Pas de retry interne pour le MVP — la file persistante locale et
 *   le filename canonique font office de fallback (on peut re-faire
 *   un POST plus tard si besoin).
 *
 * Multipart construit à la main (l'API standard {@code java.net.http}
 * ne fournit pas de helper) : trois form fields texte +
 * un file field. Boundary aléatoire, content-type
 * {@code multipart/form-data; boundary=...}.
 */
public final class ScreenshotUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger("BaseFinder.ScreenshotUploader");

    private static final int QUEUE_CAPACITY = 64;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final URI uploadUri;
    private final HttpClient httpClient;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running = true;

    public ScreenshotUploader(String backendBaseUrl) {
        if (backendBaseUrl == null || backendBaseUrl.isBlank()) {
            throw new IllegalArgumentException("backendBaseUrl must not be empty");
        }
        String trimmed = backendBaseUrl.endsWith("/")
                ? backendBaseUrl.substring(0, backendBaseUrl.length() - 1)
                : backendBaseUrl;
        this.uploadUri = URI.create(trimmed + "/v1/screenshots");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        Thread worker = new Thread(this::workerLoop, "BaseFinder-ScreenshotUploader");
        worker.setDaemon(true);
        worker.start();
    }

    public void upload(Path file, String baseKey, String angle, long takenAtMs) {
        if (!running) return;
        Pending p = new Pending(file, baseKey, angle, takenAtMs);
        if (!queue.offer(p)) {
            dropped.incrementAndGet();
            LOGGER.warn("Screenshot upload queue full ({}), dropping {}/{}", QUEUE_CAPACITY, baseKey, angle);
        }
    }

    public void shutdown() {
        running = false;
    }

    public long sentCount() { return sent.get(); }
    public long droppedCount() { return dropped.get(); }
    public long failedCount() { return failed.get(); }

    private void workerLoop() {
        while (running) {
            Pending p;
            try {
                p = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                postOne(p);
            } catch (Exception e) {
                failed.incrementAndGet();
                LOGGER.warn("Upload failed for {}/{}: {}", p.baseKey, p.angle, e.getMessage());
            }
        }
    }

    private void postOne(Pending p) throws IOException, InterruptedException {
        if (!Files.isRegularFile(p.file)) {
            failed.incrementAndGet();
            LOGGER.warn("Screenshot file missing, skipping upload: {}", p.file);
            return;
        }
        byte[] body = buildMultipartBody(p);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uploadUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        int s = res.statusCode();
        if (s >= 200 && s < 300) {
            sent.incrementAndGet();
            if (sent.get() % 10 == 0) {
                LOGGER.info("Uploaded {} screenshots so far ({} dropped, {} failed)",
                        sent.get(), dropped.get(), failed.get());
            }
        } else {
            failed.incrementAndGet();
            LOGGER.warn("Upload {} returned HTTP {}: {}", p.baseKey, s,
                    res.body().substring(0, Math.min(200, res.body().length())));
        }
    }

    // ===== multipart body construction =====

    private static final String BOUNDARY = "----basefinder-bot-" + Long.toHexString(System.nanoTime());
    private static final byte[] CRLF = "\r\n".getBytes();
    private static final byte[] DASHDASH = "--".getBytes();

    private static byte[] buildMultipartBody(Pending p) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeFormField(out, "base_key", p.baseKey);
        writeFormField(out, "angle", p.angle);
        writeFormField(out, "taken_at_ms", Long.toString(p.takenAtMs));
        writeFileField(out, p.file);
        out.write(DASHDASH);
        out.write(BOUNDARY.getBytes());
        out.write(DASHDASH);
        out.write(CRLF);
        return out.toByteArray();
    }

    private static void writeFormField(java.io.ByteArrayOutputStream out, String name, String value)
            throws IOException {
        out.write(DASHDASH);
        out.write(BOUNDARY.getBytes());
        out.write(CRLF);
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"").getBytes());
        out.write(CRLF);
        out.write(CRLF);
        out.write(value.getBytes());
        out.write(CRLF);
    }

    private static void writeFileField(java.io.ByteArrayOutputStream out, Path file)
            throws IOException {
        out.write(DASHDASH);
        out.write(BOUNDARY.getBytes());
        out.write(CRLF);
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + file.getFileName().toString() + "\"").getBytes());
        out.write(CRLF);
        out.write("Content-Type: image/png".getBytes());
        out.write(CRLF);
        out.write(CRLF);
        try (InputStream is = Files.newInputStream(file)) {
            is.transferTo(out);
        }
        out.write(CRLF);
    }

    private record Pending(Path file, String baseKey, String angle, long takenAtMs) {}
}
