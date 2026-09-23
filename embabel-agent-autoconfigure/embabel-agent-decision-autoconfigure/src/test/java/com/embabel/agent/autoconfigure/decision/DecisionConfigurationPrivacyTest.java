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
package com.embabel.agent.autoconfigure.decision;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DecisionConfigurationPrivacyTest {
    private static final String SENTINEL = "CONFIG_SECRET_SENTINEL";

    @Test
    void propertyObjectsAndValidationFailuresDoNotRenderSecrets(CapturedOutput output) {
        assertThat(new DecisionProperties().toString()).doesNotContain("apiKey", "model", "bean");
        assertThat(new DecisionProperties.Typesafe().toString()).isEqualTo("DecisionProperties.Typesafe[redacted]");

        AtomicReference<ConditionEvaluationReport> capturedReport = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentDecisionAutoConfiguration.class))
                .withInitializer(context -> capturedReport.set(
                        ConditionEvaluationReport.get(context.getBeanFactory())))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=typesafe",
                        "embabel.agent.decision.typesafe.model=" + SENTINEL,
                        "embabel.agent.decision.typesafe.base-url=https://user:" + SENTINEL + "@example.org")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).hasRootCauseMessage("Invalid configuration: embabel.agent.decision.typesafe.base-url");
                    assertThat(stack(failure))
                            .contains("Invalid configuration: embabel.agent.decision.typesafe.base-url")
                            .doesNotContain(SENTINEL);

                    String renderedReport = capturedReport.get().getConditionAndOutcomesBySource().entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining("\n"));
                    assertThat(renderedReport)
                            .contains(AgentDecisionAutoConfiguration.class.getName())
                            .doesNotContain(SENTINEL);
                    assertThat(output.getAll())
                            .contains("Invalid configuration: embabel.agent.decision.typesafe.base-url")
                            .doesNotContain(SENTINEL);
                });
    }

    private static String stack(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
