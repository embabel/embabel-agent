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
package com.embabel.agent.typesafe.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

class TypeSafeConsumerTest {
    @Test
    void plainSpringConsumerUsesAllThreeNativePrimitives() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers": {
                                  "urgent": {"type": "noul", "noul": 0.8},
                                  "team": {"type": "choice", "choice": "billing", "probabilities": {"billing": 0.8, "support": 0.2}, "confidence": 0.8},
                                  "priority": {"type": "score", "score": 0.7, "legend": {"0": "low", "1": "high"}, "probabilities": {"0": 0.3, "1": 0.7}, "confidence": 0.7}
                                }}
                                """,
                                MediaType.APPLICATION_JSON));
        new ApplicationContextRunner()
                .withUserConfiguration(ConsumerConfiguration.class)
                .withBean(RestClient.Builder.class, () -> builder)
                .withPropertyValues(
                        "embabel.agent.platform.models.typesafe.enabled=true",
                        "embabel.agent.platform.models.typesafe.api-key=test-key")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var response =
                                    context.getBean(TriageSignals.class)
                                            .evaluate("My payment failed");
                            assertThat(response.noulValue("urgent")).isEqualTo(0.8);
                            assertThat(response.choiceValue("team")).isEqualTo("billing");
                            assertThat(response.scoreValue("priority")).isEqualTo(0.7);
                        });
        server.verify();
    }

    // tag::typesafe-consumer[]
    public record TriageSignals(TypeSafeClient client) {
        public SystemOneResponse evaluate(String message) {
            return client.systemOne(
                    Map.of("message", message),
                    Map.of(
                            "urgent", Noul.of("Does the message require urgent attention?"),
                            "team",
                                    Choice.of(
                                            "Which team should handle the message?",
                                            "billing",
                                            "support"),
                            "priority", Score.of("How high is the priority?", "low", "high")));
        }
    }

    // end::typesafe-consumer[]

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerConfiguration {
        @Bean
        TriageSignals triageSignals(TypeSafeClient client) {
            return new TriageSignals(client);
        }
    }
}
