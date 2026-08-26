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

import com.embabel.agent.autoconfigure.models.mistralai.StubMistralServer.Reply;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real Mistral autoconfiguration against a scripted local server to check what the Spring
 * Framework 7 retry template actually replays. A rate limit must be retried; a malformed request must
 * not. Counting requests on the server is what separates the two — the caller sees a failure either way.
 *
 * <p>{@code ...IT} because it opens a socket and waits out a real backoff.
 */
class MistralAiRetryIT {

    private static final String MODEL = "mistral-small-2603";

    private ApplicationContextRunner runnerAgainst(StubMistralServer server) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
                .withPropertyValues(
                        "embabel.agent.platform.models.mistralai.api-key=test-key",
                        "embabel.agent.platform.models.mistralai.base-url=" + server.baseUrl(),
                        "embabel.agent.platform.models.mistralai.max-attempts=3",
                        // Keep the backoff short enough that the test does not idle.
                        "embabel.agent.platform.models.mistralai.backoff-millis=50",
                        "embabel.agent.platform.models.mistralai.backoff-multiplier=2",
                        "embabel.agent.platform.models.mistralai.backoff-max-interval=200"
                );
    }

    private SpringAiLlmService modelIn(AssertableApplicationContext context) {
        return context.getBeansOfType(SpringAiLlmService.class).values().stream()
                .filter(s -> MODEL.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(MODEL + " not registered"));
    }

    @Test
    void aRateLimitIsRetriedAndTheSecondAttemptSucceeds() throws IOException {
        try (var server = StubMistralServer.replyingInSequence(
                new Reply(429, StubMistralServer.RATE_LIMITED_RESPONSE),
                new Reply(200, StubMistralServer.OK_RESPONSE))) {
            runnerAgainst(server).run(context -> {
                var response = modelIn(context).getChatModel().call("hello");

                assertThat(response).isEqualTo("OK");
                assertThat(server.requestCount())
                        .as("the 429 was replayed once, and the retry succeeded")
                        .isEqualTo(2);
            });
        }
    }

    @Test
    void aMalformedRequestIsNotRetried() throws IOException {
        // The server would answer the second request with 200. Reaching it at all means the template
        // replayed a request that can never succeed.
        try (var server = StubMistralServer.replyingInSequence(
                new Reply(400, StubMistralServer.BAD_REQUEST_RESPONSE),
                new Reply(200, StubMistralServer.OK_RESPONSE))) {
            runnerAgainst(server).run(context -> {
                var model = modelIn(context);

                assertThatThrownBy(() -> model.getChatModel().call("hello"))
                        .hasMessageContaining("Invalid model");
                assertThat(server.requestCount())
                        .as("a 400 is deterministic, so it must fail on the first attempt")
                        .isEqualTo(1);
            });
        }
    }
}
