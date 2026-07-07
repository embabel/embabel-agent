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

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: Mistral must honour the platform read timeout
 * ({@code embabel.agent.platform.http-client.read-timeout}) instead of the ~10s
 * {@code ReactorClientHttpRequestFactory} default that aborts slow generations.
 *
 * <p>Drives the real config against a local server replying after ~12s with the read timeout set to 30s:
 * a correctly-wired client waits and returns the reply; the bug aborts at ~10s. {@code ...IT} because the
 * deliberate ~12s wait would otherwise slow every unit-test run.
 */
class MistralAiHttpClientTimeoutIT {

    /** A valid, plain-string chat completion (no reasoning content) so parsing is trivial. */
    private static final String RESPONSE = """
            {"id":"cmpl-test","created":1700000000,"model":"mistral-small-2603","object":"chat.completion",\
            "usage":{"prompt_tokens":1,"total_tokens":2,"completion_tokens":1},\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}""";

    private static final Duration SERVER_DELAY = Duration.ofSeconds(12);

    private HttpServer slowServer;
    private ExecutorService serverExecutor;
    private int port;

    /** Replies only after {@link #SERVER_DELAY}; a dedicated executor lets teardown interrupt the sleep. */
    @BeforeEach
    void startSlowServer() throws IOException {
        serverExecutor = Executors.newCachedThreadPool();
        slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.setExecutor(serverExecutor);
        slowServer.createContext("/", exchange -> {
            try {
                Thread.sleep(SERVER_DELAY.toMillis());
                var bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
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
        slowServer.start();
        port = slowServer.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (slowServer != null) {
            slowServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void mistralHonoursPlatformReadTimeoutForSlowResponses() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
                .withPropertyValues(
                        "embabel.agent.platform.models.mistralai.api-key=test-key",
                        "embabel.agent.platform.models.mistralai.base-url=http://127.0.0.1:" + port,
                        // Single attempt: no retries muddying the timing.
                        "embabel.agent.platform.models.mistralai.max-attempts=1",
                        // The platform read timeout the client must honour: comfortably above SERVER_DELAY.
                        "embabel.agent.platform.http-client.read-timeout=30s"
                )
                .run(context -> {
                    var model = context.getBeansOfType(SpringAiLlmService.class).values().stream()
                            .filter(s -> "mistral-small-2603".equals(s.getName()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("mistral-small-2603 model not registered"));

                    var start = System.nanoTime();
                    var response = model.getChatModel().call("hello");
                    var elapsed = Duration.ofNanos(System.nanoTime() - start);

                    // The slow response is awaited and returned — the ~10s default did not abort it.
                    assertThat(response).isEqualTo("OK");
                    assertThat(elapsed)
                            .as("the call waited for the ~12s reply, so the platform read timeout was honoured")
                            .isGreaterThan(Duration.ofSeconds(11));
                });
    }
}
