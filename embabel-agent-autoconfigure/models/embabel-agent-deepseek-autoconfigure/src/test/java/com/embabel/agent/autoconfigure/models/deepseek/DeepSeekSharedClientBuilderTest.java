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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies that {@code DeepSeekModelsConfig} builds its HTTP client from the shared platform
 * {@code aiModelRestClientBuilder} bean (like every other provider), not from its own
 * {@code RestClient.builder()}.
 *
 * <p>The shared bean registered here is bound to a {@link MockRestServiceServer}: if the config
 * consumes it, the request is served by the mock, otherwise it never arrives. Red before the refactor,
 * green after. No socket, no port, no API key.
 *
 * <p>Which client the provider ends up with is the whole of this fix. Left to classpath detection it
 * lands on Apache HttpClient, which advertises a content coding it cannot decode, and every call fails
 * against the real API.
 */
class DeepSeekSharedClientBuilderTest {

    private static final String BASE_URL = "https://api.deepseek.test";

    /** A valid chat completion for {@code deepseek-v4-pro}. */
    private static final String OK_RESPONSE = """
            {"id":"cmpl-test","created":1700000000,"model":"deepseek-v4-pro","object":"chat.completion",\
            "usage":{"prompt_tokens":1,"total_tokens":2,"completion_tokens":1},\
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}""";

    @Test
    void usesTheSharedPlatformRestClientBuilder() {
        var sharedBuilder = RestClient.builder();
        var mockServer = MockRestServiceServer.bindTo(sharedBuilder).build();
        mockServer.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentDeepSeekAutoConfiguration.class))
                .withBean("aiModelRestClientBuilder", RestClient.Builder.class, () -> sharedBuilder)
                // Both spellings: the configuration reads the environment variables first, and one of
                // them is set on any machine that talks to the real API. Set here they cannot leak in.
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

                    assertThatCode(() -> assertThat(model.getChatModel().call("hello")).isEqualTo("OK"))
                            .as("the mock bound to the shared builder must serve the call; anything else means "
                                    + "the provider built its own client and went to the network")
                            .doesNotThrowAnyException();
                    mockServer.verify();
                });
    }
}
