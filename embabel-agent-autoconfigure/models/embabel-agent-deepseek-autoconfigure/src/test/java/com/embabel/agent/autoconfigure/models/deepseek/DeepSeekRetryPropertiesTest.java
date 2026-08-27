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

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Verifies that {@code DeepSeekModelsConfig} honours the retry properties it declares.
 *
 * <p>{@code DeepSeekProperties} implements {@code RetryProperties} and binds
 * {@code embabel.agent.platform.models.deepseek.max-attempts}, so setting it to 1 must produce
 * exactly one HTTP attempt. It did not: the chat model was built without a retry template, so
 * Spring AI fell back to {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} — eleven attempts, 2s initial
 * backoff, ×5 multiplier, capped at three minutes — and every retry property on this provider was
 * silently dead configuration.
 *
 * <p>The transport fails with an {@link IOException}, which {@code RestClient} wraps in a
 * {@code ResourceAccessException} — one of the two exception types that default template retried,
 * and the same one an unreachable host produced against the real API.
 */
class DeepSeekRetryPropertiesTest {

    private static final String BASE_URL = "https://api.deepseek.test";

    @Test
    void honoursConfiguredMaxAttempts() {
        var sharedBuilder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(sharedBuilder).build();
        var attempts = new AtomicInteger();

        mockServer.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + "/chat/completions"))
                .andRespond(request -> {
                    if (attempts.incrementAndGet() > 1) {
                        // Abort here rather than let the run stand: an AssertionError is retried by
                        // neither policy, so the call returns at once instead of walking the default
                        // template's remaining backoffs — 10s, 50s, then three minutes apiece.
                        throw new AssertionError("a second request was issued despite max-attempts=1");
                    }
                    throw new IOException("connection reset by peer");
                });

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentDeepSeekAutoConfiguration.class))
                .withBean("aiModelRestClientBuilder", RestClient.Builder.class, () -> sharedBuilder)
                .withPropertyValues(
                        "DEEPSEEK_API_KEY=test-key",
                        "DEEPSEEK_BASE_URL=" + BASE_URL,
                        "embabel.agent.platform.models.deepseek.api-key=test-key",
                        "embabel.agent.platform.models.deepseek.base-url=" + BASE_URL,
                        "embabel.agent.platform.models.deepseek.max-attempts=1"
                )
                .run(context -> {
                    var model = context.getBeansOfType(SpringAiLlmService.class).values().stream()
                            .filter(s -> "deepseek-v4-pro".equals(s.getName()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("deepseek-v4-pro model not registered"));

                    assertThatThrownBy(() -> model.getChatModel().call("hello"))
                            .as("the transport always fails, so the call cannot succeed")
                            .isInstanceOf(Throwable.class);

                    assertThat(attempts)
                            .as("max-attempts=1 means a single try and no retry")
                            .hasValue(1);
                });
    }
}
