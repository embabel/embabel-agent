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
package com.embabel.agent.typesafe;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.question.Noul;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class TypeSafeClientFactoryTest {
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    private static final String RESPONSE =
            """
            {"answers":{"ok":{"type":"noul","noul":0.8}}}
            """;

    @Test
    void configuredProxyBasePathIsUsedForRequests() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://proxy.example/typesafe/v1/systemone"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var defaults = TypeSafeClientOptions.defaults();
        var options =
                new TypeSafeClientOptions(
                        URI.create("http://proxy.example/typesafe"),
                        defaults.connectTimeout(),
                        defaults.readTimeout(),
                        defaults.maxResponseBytes());
        var client = new TypeSafeClientFactory(options, () -> "test-key", builder).build();
        assertThat(client.systemOne("state", Map.of("ok", Noul.of("ok?"))).noulValue("ok"))
                .isEqualTo(0.8);
        server.verify();
    }

    @Test
    void optionsDiagnosticsDoNotExposeEndpointContents() {
        var defaults = TypeSafeClientOptions.defaults();
        var options =
                new TypeSafeClientOptions(
                        URI.create("https://example.test/private-path"),
                        defaults.connectTimeout(),
                        defaults.readTimeout(),
                        defaults.maxResponseBytes());

        assertThat(options.toString())
                .doesNotContain("private-path", "example.test");
    }

    @Test
    void buildsIndependentModelClientsFromOneProviderConfiguration() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        for (var model : new String[] {"first-model", "second-model", "first-model"}) {
            server.expect(requestTo(SYSTEM_ONE_URI))
                    .andExpect(header("Authorization", "Bearer test-key"))
                    .andExpect(jsonPath("$.model").value(model))
                    .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        }
        var factory =
                new TypeSafeClientFactory(
                        TypeSafeClientOptions.defaults(), () -> "test-key", builder);
        var first = factory.build("first-model");
        var second = factory.build("second-model");
        var questions = Map.of("ok", Noul.of("ok?"));
        first.systemOne("state", questions);
        second.systemOne("state", questions);
        first.systemOne("state", questions);
        server.verify();
    }

    @Test
    void buildingClientsValidatesModelsWithoutResolvingCredentials() {
        var resolutions = new AtomicInteger();
        var factory =
                new TypeSafeClientFactory(
                        () -> {
                            resolutions.incrementAndGet();
                            return "test-key";
                        });
        factory.build();
        factory.build("another-model");
        assertThatThrownBy(() -> factory.build(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.build(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(resolutions).hasValue(0);
    }

    @Test
    void nativeNoul() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(header("Authorization", "Bearer rotating-key"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));
        var client =
                new TypeSafeClientFactory(
                                TypeSafeClientOptions.defaults(), () -> "rotating-key", builder)
                        .build();
        assertThat(client.systemOne("state", Map.of("ok", Noul.of("ok?"))).noulValue("ok"))
                .isEqualTo(0.8);
        server.verify();
    }
}
