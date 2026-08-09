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
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code DeepSeekModelsConfig} builds its HTTP client from the shared platform
 * {@code aiModelRestClientBuilder} bean (like every other provider), not from its own
 * {@code RestClient.builder()}.
 *
 * <p>Registers a sentinel builder carrying an interceptor and points DeepSeek at a local stub: if the
 * config consumes the shared bean the interceptor sees the request, otherwise it never fires. Red
 * before the refactor, green after.
 *
 * <p>Which client the provider ends up with is the whole of this fix. Left to classpath detection it
 * lands on Apache HttpClient, which advertises a content coding it cannot decode, and every call fails
 * against the real API. Worth catching here rather than in production, and this needs no API key.
 *
 * <p>The sibling Mistral test proves the same property with a short timeout on the sentinel. That does
 * not transpose: this provider passes no {@code RetryTemplate} to the model, so Spring AI's default one
 * would retry the timeout with backoff and the test would take minutes. An interceptor states the
 * property directly and returns in milliseconds.
 */
class DeepSeekSharedClientBuilderTest {

    @Test
    void usesTheSharedPlatformRestClientBuilder() throws IOException {
        var requestsThroughSharedBuilder = new AtomicInteger();

        try (var server = StubDeepSeekServer.replyingAfter(Duration.ZERO, StubDeepSeekServer.OK_RESPONSE)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentDeepSeekAutoConfiguration.class))
                    .withBean("aiModelRestClientBuilder", RestClient.Builder.class,
                            () -> RestClient.builder().requestInterceptor((request, body, execution) -> {
                                requestsThroughSharedBuilder.incrementAndGet();
                                return execution.execute(request, body);
                            }))
                    // Both spellings: the configuration reads the environment variables first, and one of
                    // them is set on any machine that talks to the real API. Set here they cannot leak in.
                    .withPropertyValues(
                            "DEEPSEEK_API_KEY=test-key",
                            "DEEPSEEK_BASE_URL=" + server.baseUrl(),
                            "embabel.agent.platform.models.deepseek.api-key=test-key",
                            "embabel.agent.platform.models.deepseek.base-url=" + server.baseUrl(),
                            "embabel.agent.platform.models.deepseek.max-attempts=1"
                    )
                    .run(context -> {
                        var model = context.getBeansOfType(SpringAiLlmService.class).values().stream()
                                .filter(s -> "deepseek-v4-pro".equals(s.getName()))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("deepseek-v4-pro model not registered"));

                        var answer = model.getChatModel().call("hello");

                        assertThat(answer)
                                .as("the stub's canned completion must come back")
                                .isEqualTo("OK");
                        assertThat(requestsThroughSharedBuilder)
                                .as("the call must go through the injected shared builder, not one the provider built")
                                .hasValue(1);
                    });
        }
    }
}
