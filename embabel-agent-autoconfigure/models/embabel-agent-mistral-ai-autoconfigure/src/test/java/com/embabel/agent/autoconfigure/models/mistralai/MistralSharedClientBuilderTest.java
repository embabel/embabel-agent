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
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@code MistralAiModelsConfig} builds its HTTP client from the shared platform
 * {@code aiModelRestClientBuilder} bean (like every other provider), not from its own
 * {@code RestClient.builder()}.
 *
 * <p>Registers a sentinel builder with a tiny 300ms timeout and points Mistral at a server replying
 * after 3s: if the config consumes the shared bean the call aborts fast; if it ignores it and builds its
 * own client, the call waits and returns. Red before the refactor, green after.
 */
class MistralSharedClientBuilderTest {

    private static final Duration SERVER_DELAY = Duration.ofSeconds(3);
    private static final Duration SHARED_BUILDER_TIMEOUT = Duration.ofMillis(300);

    /** A shared platform builder whose reactor-netty client aborts any reply slower than 300ms. */
    private static RestClient.Builder shortTimeoutSharedBuilder() {
        var httpClient = HttpClient.create().responseTimeout(SHARED_BUILDER_TIMEOUT);
        return RestClient.builder().requestFactory(new ReactorClientHttpRequestFactory(httpClient));
    }

    @Test
    void usesTheSharedPlatformRestClientBuilder() throws IOException {
        try (var server = StubMistralServer.replyingAfter(SERVER_DELAY, StubMistralServer.OK_RESPONSE)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentMistralAiAutoConfiguration.class))
                    .withBean("aiModelRestClientBuilder", RestClient.Builder.class,
                            MistralSharedClientBuilderTest::shortTimeoutSharedBuilder)
                    .withPropertyValues(
                            "embabel.agent.platform.models.mistralai.api-key=test-key",
                            "embabel.agent.platform.models.mistralai.base-url=" + server.baseUrl(),
                            "embabel.agent.platform.models.mistralai.max-attempts=1"
                    )
                    .run(context -> {
                        var model = context.getBeansOfType(SpringAiLlmService.class).values().stream()
                                .filter(s -> "mistral-small-2603".equals(s.getName()))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("mistral-small-2603 model not registered"));

                        var start = System.nanoTime();
                        assertThatThrownBy(() -> model.getChatModel().call("hello"))
                                .as("the shared builder's 300ms timeout must abort the 3s reply")
                                .hasStackTraceContaining("ReadTimeout");
                        var elapsed = Duration.ofNanos(System.nanoTime() - start);

                        assertThat(elapsed)
                                .as("aborted via the injected shared builder, not after the full 3s server delay")
                                .isLessThan(Duration.ofSeconds(2));
                    });
        }
    }
}
