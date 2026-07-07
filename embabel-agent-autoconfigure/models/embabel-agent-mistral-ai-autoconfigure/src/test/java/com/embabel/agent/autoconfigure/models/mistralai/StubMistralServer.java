/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.autoconfigure.models.mistralai;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local HTTP server that returns a canned Mistral chat-completion body, optionally after a delay.
 * Shared fixture for the hermetic Mistral tests so each one doesn't re-implement server setup/teardown.
 *
 * <p>Handlers run on a dedicated executor so {@link #close()} can interrupt an in-flight delayed reply.
 * Use with try-with-resources: {@code try (var server = StubMistralServer.replyingWith(BODY)) { ... }}.
 */
final class StubMistralServer implements AutoCloseable {

    /** A valid, plain-string chat completion (no reasoning content) for {@code mistral-small-2603}. */
    static final String OK_RESPONSE = """
            {"id":"cmpl-test","created":1700000000,"model":"mistral-small-2603","object":"chat.completion",\
            "usage":{"prompt_tokens":1,"total_tokens":2,"completion_tokens":1},\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}""";

    private final HttpServer server;
    private final ExecutorService executor;
    private final int port;

    private StubMistralServer(String responseBody, Duration delay) throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                if (!delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
                var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException e) {
                exchange.close();
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    /** Starts a server that replies immediately with {@code responseBody}. */
    static StubMistralServer replyingWith(String responseBody) throws IOException {
        return new StubMistralServer(responseBody, Duration.ZERO);
    }

    /** Starts a server that replies with {@code responseBody} only after {@code delay}. */
    static StubMistralServer replyingAfter(Duration delay, String responseBody) throws IOException {
        return new StubMistralServer(responseBody, delay);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
