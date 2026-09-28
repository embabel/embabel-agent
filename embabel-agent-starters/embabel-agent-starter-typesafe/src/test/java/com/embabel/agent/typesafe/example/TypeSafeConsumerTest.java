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

import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TypeSafeConsumerTest {
    @Test
    void springConsumerUsesProviderNeutralDecisionContracts() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(
                        withSuccess(
                                """
                                {"model":"jev-latest","answers":{"classification":{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"support":0.2},"confidence":0.7}}}
                                """,
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                .andRespond(
                        withSuccess(
                                """
                                {"model":"jev-latest","answers":{"proposition":{"type":"noul","noul":0.8}}}
                                """,
                                MediaType.APPLICATION_JSON));

        new ApplicationContextRunner()
                .withUserConfiguration(ConsumerConfiguration.class)
                .withBean(RestClient.Builder.class, () -> builder)
                .withPropertyValues(
                        "TYPESAFE_API_KEY=",
                        "embabel.agent.platform.models.typesafe.api-key=test-key")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var triage = context.getBean(SupportTriage.class);
                            assertThat(triage.classify("My payment failed"))
                                    .isInstanceOfSatisfying(
                                            ClassificationResult.Selected.class,
                                            selected ->
                                                    assertThat(selected.getCategoryId())
                                                            .isEqualTo("billing"));
                            assertThat(triage.isUrgent("My payment has failed for three days"))
                                    .isInstanceOfSatisfying(
                                            PropositionResult.Answered.class,
                                            answered -> assertThat(answered.getAnswer()).isTrue());
                        });
        server.verify();
    }

    // tag::typesafe-consumer[]
    public record SupportTriage(DecisionService decisions) {
        public ClassificationResult classify(String message) {
            return decisions.classify(
                    message,
                    ClassificationSpec.builder()
                            .asking("Which team should handle this request?")
                            .category("billing", "Payments, invoices and refunds")
                            .category("support", "Product use and technical support")
                            .build());
        }

        public PropositionResult isUrgent(String message) {
            return decisions.assess(
                    new PropositionRequest(message, "This request needs urgent attention"));
        }
    }
    // end::typesafe-consumer[]

    @Configuration(proxyBeanMethods = false)
    // The platform auto-configuration is on this module's test classpath for the assembled examples;
    // this consumer uses TypeSafe on its own.
    @EnableAutoConfiguration(excludeName = "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration")
    static class ConsumerConfiguration {
        @Bean
        SupportTriage supportTriage(DecisionService decisions) {
            return new SupportTriage(decisions);
        }
    }
}
