package com.basefinder.adapter.io.telemetry;

import com.basefinder.domain.event.BotTick;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spins up a tiny in-process HTTP server and verifies that HttpJsonLineSink
 * actually POSTs NDJSON to /v1/ingest. Uses jdk.httpserver (already on the
 * Java module path), so no extra dependency.
 */
class HttpJsonLineSinkTest {

    private HttpServer server;
    private HttpJsonLineSink sink;
    private final List<String> receivedBodies = new ArrayList<>();
    private CountDownLatch latch;

    @AfterEach
    void tearDown() {
        if (sink != null) sink.close();
        if (server != null) server.stop(0);
    }

    private int startServer(int expectedRequests, AtomicInteger statusCode) throws IOException {
        latch = new CountDownLatch(expectedRequests);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ingest", (HttpExchange ex) -> {
            try (InputStream body = ex.getRequestBody()) {
                String line = new String(body.readAllBytes(), StandardCharsets.UTF_8).trim();
                synchronized (receivedBodies) {
                    receivedBodies.add(line);
                }
            }
            int code = statusCode.get();
            ex.sendResponseHeaders(code, 0);
            ex.close();
            latch.countDown();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void postsEachEventAsNdjson() throws Exception {
        int port = startServer(2, new AtomicInteger(204));
        sink = new HttpJsonLineSink("http://127.0.0.1:" + port);

        sink.publish(tick(1));
        sink.publish(tick(2));

        assertTrue(latch.await(3, TimeUnit.SECONDS), "server should receive both POSTs");
        synchronized (receivedBodies) {
            assertEquals(2, receivedBodies.size());
            assertTrue(receivedBodies.get(0).contains("\"seq\":1"));
            assertTrue(receivedBodies.get(1).contains("\"seq\":2"));
        }
        // Allow worker to record the success counter
        Thread.sleep(50);
        assertEquals(2L, sink.sentCount());
        assertEquals(0L, sink.failedCount());
    }

    @Test
    void backendErrorsAreCounted() throws Exception {
        int port = startServer(1, new AtomicInteger(500));
        sink = new HttpJsonLineSink("http://127.0.0.1:" + port);

        sink.publish(tick(1));
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(0L, sink.sentCount());
        assertEquals(1L, sink.failedCount());
    }

    @Test
    void unreachableBackendDoesNotCrash() throws Exception {
        // Use a port that's almost certainly free. Worker will fail to connect.
        sink = new HttpJsonLineSink("http://127.0.0.1:1");
        sink.publish(tick(1));

        // Give worker time to attempt + fail
        Thread.sleep(300);
        assertEquals(0L, sink.sentCount());
        assertTrue(sink.failedCount() >= 1L, "expected at least 1 failure");
    }

    private static BotTick tick(long seq) {
        return new BotTick(seq, seq * 1000L, 0, 64, 0, "overworld", 20, 20.0, 100, 0, false, "IDLE", 0, 0);
    }
}
