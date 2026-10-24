package com.itzixi.apitest.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public final class SseTestClient {

    private SseTestClient() {
    }

    public static Connection connect(String baseUrl, String userId, String token) throws IOException {
        String encodedUserId = URLEncoder.encode(userId, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/sse/connect?userId=" + encodedUserId);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(0);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.connect();

        int statusCode = connection.getResponseCode();
        if (statusCode != 200) {
            throw new IOException("SSE connect failed, status=" + statusCode);
        }

        return new Connection(connection);
    }

    public record SseEvent(String id, String event, String data) {
    }

    public static final class Connection implements AutoCloseable {

        private final HttpURLConnection connection;
        private final BufferedReader reader;
        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean streamEnded = new AtomicBoolean(false);
        private final AtomicReference<Throwable> readerError = new AtomicReference<>();
        private final Thread readerThread;

        private Connection(HttpURLConnection connection) throws IOException {
            this.connection = connection;
            this.reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            );
            this.readerThread = new Thread(this::readLoop, "sse-test-client-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        public String contentType() {
            return connection.getHeaderField("Content-Type");
        }

        public List<SseEvent> snapshot() {
            return List.copyOf(events);
        }

        public List<SseEvent> awaitEvents(Predicate<List<SseEvent>> predicate, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() <= deadline) {
                rethrowReaderError();
                List<SseEvent> snapshot = snapshot();
                if (predicate.test(snapshot)) {
                    return snapshot;
                }
                sleepBriefly();
            }

            rethrowReaderError();
            return snapshot();
        }

        public void awaitClosed(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() <= deadline) {
                rethrowReaderError();
                if (streamEnded.get()) {
                    return;
                }
                sleepBriefly();
            }
            throw new IllegalStateException("Timed out waiting for SSE connection to close");
        }

        private void readLoop() {
            String id = null;
            String event = null;
            StringBuilder data = null;

            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (id != null || event != null || data != null) {
                            events.add(new SseEvent(id, event, data == null ? "" : data.toString()));
                            id = null;
                            event = null;
                            data = null;
                        }
                        continue;
                    }

                    if (line.startsWith(":")) {
                        continue;
                    }

                    int separator = line.indexOf(':');
                    String field = separator < 0 ? line : line.substring(0, separator);
                    String value = separator < 0 ? "" : line.substring(separator + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }

                    switch (field) {
                        case "id" -> id = value;
                        case "event" -> event = value;
                        case "data" -> {
                            if (data == null) {
                                data = new StringBuilder();
                            } else {
                                data.append('\n');
                            }
                            data.append(value);
                        }
                        default -> {
                        }
                    }
                }

                if (id != null || event != null || data != null) {
                    events.add(new SseEvent(id, event, data == null ? "" : data.toString()));
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    readerError.set(e);
                }
            } finally {
                streamEnded.set(true);
                try {
                    reader.close();
                } catch (IOException ignore) {
                }
                connection.disconnect();
            }
        }

        private void rethrowReaderError() {
            Throwable error = readerError.get();
            if (error != null) {
                throw new IllegalStateException("SSE reader failed", error);
            }
        }

        private void sleepBriefly() {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for SSE events", e);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Thread closerThread = new Thread(() -> {
                connection.disconnect();
                try {
                    reader.close();
                } catch (IOException ignore) {
                }
            }, "sse-test-client-closer");
            closerThread.setDaemon(true);
            closerThread.start();
        }
    }
}
