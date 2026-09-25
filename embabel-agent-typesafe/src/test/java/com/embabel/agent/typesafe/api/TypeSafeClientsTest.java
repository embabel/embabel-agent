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
package com.embabel.agent.typesafe.api;

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

class TypeSafeClientsTest {
    @Test
    void configuredProxyBasePathIsUsedForRequests() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://proxy.example/typesafe/v1/systemone"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"ok":{"type":"noul","noul":0.8}}}
                                """,
                                MediaType.APPLICATION_JSON));
        var defaults = TypeSafeClientOptions.defaults();
        var options =
                new TypeSafeClientOptions(
                        URI.create("http://proxy.example/typesafe"),
                        defaults.model(),
                        defaults.connectTimeout(),
                        defaults.readTimeout(),
                        defaults.maxResponseBytes());
        var client = TypeSafeClients.create(options, () -> "test-key", builder);
        assertThat(client.systemOne("state", Map.of("ok", Noul.of("ok?"))).noulValue("ok"))
                .isEqualTo(0.8);
        server.verify();
    }

    @Test
    void nativeNoul() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andExpect(header("Authorization", "Bearer rotating-key"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"ok":{"type":"noul","noul":0.8}}}
                                """,
                                MediaType.APPLICATION_JSON));
        var client =
                TypeSafeClients.create(
                        TypeSafeClientOptions.defaults(), () -> "rotating-key", builder);
        assertThat(client.systemOne("state", Map.of("ok", Noul.of("ok?"))).noulValue("ok"))
                .isEqualTo(0.8);
        server.verify();
    }
}
