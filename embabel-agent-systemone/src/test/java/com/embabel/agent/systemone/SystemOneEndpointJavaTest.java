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
package com.embabel.agent.systemone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

class SystemOneEndpointJavaTest {

    private static final SystemOneBinding RUN_PATH =
            new SystemOneBinding() {
                @Override
                public URI decisionUri(SystemOneEndpoint endpoint, String model) {
                    return endpoint.resolve("/run/" + model);
                }
            };

    @Test
    void withBindingKeepsEveryOtherValue() {
        var endpoint =
                new SystemOneEndpoint(
                        "Acme",
                        URI.create("https://decisions.acme.example"),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(4),
                        2048,
                        "x-request-id");

        var bound = endpoint.withBinding(RUN_PATH);

        assertThat(bound.getBinding()).isSameAs(RUN_PATH);
        assertThat(bound.getProvider()).isEqualTo("Acme");
        assertThat(bound.getBaseUri()).isEqualTo(URI.create("https://decisions.acme.example"));
        assertThat(bound.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(bound.getReadTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(bound.getMaxResponseBytes()).isEqualTo(2048);
        assertThat(bound.getRequestIdHeader()).isEqualTo("x-request-id");
        assertThat(endpoint.getBinding()).isSameAs(SystemOneBinding.STANDARD);
    }

    @Test
    void aFactoryUsesTheBindingGivenThroughTheWither() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://decisions.acme.example/run/acme-small"))
                .andRespond(
                        withSuccess(
                                """
                                {"model":"acme-small","answers":{"q1":{"type":"noul","noul":0.9}}}
                                """,
                                MediaType.APPLICATION_JSON));
        var endpoint =
                new SystemOneEndpoint("Acme", URI.create("https://decisions.acme.example"))
                        .withBinding(RUN_PATH);
        var urgent = Questions.named("urgent").proposition("Is this urgent?").build();

        var response =
                new SystemOneModelFactory(endpoint, "acme-small", () -> "key", builder)
                        .build()
                        .ask("I was charged twice for one invoice.", DecisionSpec.of(urgent));

        server.verify();
        assertThat(response.answer(urgent)).isInstanceOf(PropositionResult.Answered.class);
    }
}
