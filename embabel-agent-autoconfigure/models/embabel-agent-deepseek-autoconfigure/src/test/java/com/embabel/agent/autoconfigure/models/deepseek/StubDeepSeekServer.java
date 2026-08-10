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
package com.embabel.agent.autoconfigure.models.deepseek;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local HTTP server returning a canned DeepSeek chat-completion body, on an ephemeral loopback port.
 *
 * <p>Use with try-with-resources: {@code try (var server = StubDeepSeekServer.replyingWith(BODY)) { ... }}.
 * Handlers run on a dedicated executor, which {@link #close()} shuts down along with the server.
 */
final class StubDeepSeekServer implements AutoCloseable {

    /** A valid chat completion for {@code deepseek-v4-pro}. */
    static final String OK_RESPONSE = """
            {"id":"cmpl-test","created":1700000000,"model":"deepseek-v4-pro","object":"chat.completion",\
            "usage":{"prompt_tokens":1,"total_tokens":2,"completion_tokens":1},\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}""";

    private final HttpServer server;
    private final ExecutorService executor;
    private final int port;

    private StubDeepSeekServer(String responseBody) throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    /** Starts a server replying with {@code responseBody}. */
    static StubDeepSeekServer replyingWith(String responseBody) throws IOException {
        return new StubDeepSeekServer(responseBody);
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
