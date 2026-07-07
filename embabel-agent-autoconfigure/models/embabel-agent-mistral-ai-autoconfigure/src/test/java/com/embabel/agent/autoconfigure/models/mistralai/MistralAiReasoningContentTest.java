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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic reproduction and fix verification for the Mistral reasoning-content bug.
 *
 * <p>{@code spring-ai-mistral-ai:1.1.7} assumes an assistant message's content is a plain string
 * ({@code ChatCompletionMessage.content()} throws {@code IllegalStateException: The content is not a
 * string!} otherwise). Reasoning models ({@code magistral-*}) return structured "thinking"/"text"
 * chunks, so every call throws.
 *
 * <p>This test serves a canned magistral response (content is a chunk array) from a local server and
 * asserts the model returns the final text {@code "READY"}. It fails before the fix (with the
 * {@code IllegalStateException}) and passes once the Mistral client flattens reasoning content to plain
 * text before Spring AI parses it.
 */
class MistralAiReasoningContentTest {

    /** A real magistral-medium-2509 response body: content is a [thinking, text] chunk array. */
    private static final String MAGISTRAL_RESPONSE = """
            {"id":"cmpl-test","created":1700000000,"model":"magistral-medium-2509",\
            "usage":{"prompt_tokens":11,"total_tokens":48,"completion_tokens":37},\
            "object":"chat.completion",\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","tool_calls":null,\
            "content":[\
            {"type":"thinking","thinking":[{"type":"text","text":"The user wants exactly READY."}],"closed":true},\
            {"type":"text","text":"READY"}\
            ]}}]}""";

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var bytes = MAGISTRAL_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reasoningModelContentIsFlattenedToPlainText() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
                .withPropertyValues(
                        "embabel.agent.platform.models.mistralai.api-key=test-key",
                        "embabel.agent.platform.models.mistralai.base-url=http://127.0.0.1:" + port,
                        "embabel.agent.platform.models.mistralai.max-attempts=1"
                )
                .run(context -> {
                    var magistral = context.getBeansOfType(SpringAiLlmService.class).values().stream()
                            .filter(s -> "magistral-medium-2509".equals(s.getName()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("magistral-medium-2509 not registered"));

                    var response = magistral.getChatModel().call("Reply READY.");

                    assertThat(response).isEqualTo("READY");
                });
    }
}
