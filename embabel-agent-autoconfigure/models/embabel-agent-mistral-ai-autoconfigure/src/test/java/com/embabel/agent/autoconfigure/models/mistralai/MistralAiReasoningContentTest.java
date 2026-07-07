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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic reproduction and fix verification for the Mistral reasoning-content bug.
 *
 * <p>{@code spring-ai-mistral-ai:1.1.7} assumes an assistant message's content is a plain string
 * ({@code ChatCompletionMessage.content()} throws {@code IllegalStateException: The content is not a
 * string!} otherwise). Reasoning models ({@code magistral-*}) return structured "thinking"/"text"
 * chunks, so every call throws.
 *
 * <p>Serves a canned magistral response (content is a chunk array) from a local server and asserts the
 * model returns the final text {@code "READY"}. Fails before the fix, passes once the client flattens
 * reasoning content to plain text before Spring AI parses it. The parser itself is unit-tested in
 * {@code MistralReasoningContentFlattenTest}; this test covers the end-to-end wiring.
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

    @Test
    void reasoningModelContentIsFlattenedToPlainText() throws IOException {
        try (var server = StubMistralServer.replyingWith(MAGISTRAL_RESPONSE)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
                    .withPropertyValues(
                            "embabel.agent.platform.models.mistralai.api-key=test-key",
                            "embabel.agent.platform.models.mistralai.base-url=" + server.baseUrl(),
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
}
