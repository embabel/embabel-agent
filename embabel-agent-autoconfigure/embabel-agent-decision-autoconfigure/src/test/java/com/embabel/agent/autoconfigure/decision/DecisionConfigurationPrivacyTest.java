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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionConfigurationPrivacyTest {
    @Test
    void propertyObjectsAndValidationFailuresDoNotRenderSecrets() {
        assertThat(new DecisionProperties().toString()).doesNotContain("apiKey", "model", "bean");
        assertThat(new DecisionProperties.Typesafe().toString()).isEqualTo("DecisionProperties.Typesafe[redacted]");

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentDecisionAutoConfiguration.class))
                .withPropertyValues(
                        "embabel.agent.decision.enabled=true",
                        "embabel.agent.decision.provider=typesafe",
                        "embabel.agent.decision.typesafe.model=CONFIG_SECRET_SENTINEL",
                        "embabel.agent.decision.typesafe.base-url=https://user:CONFIG_SECRET_SENTINEL@example.org")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).hasRootCauseMessage("Invalid configuration: embabel.agent.decision.typesafe.base-url");
                    assertThat(stack(failure)).doesNotContain("CONFIG_SECRET_SENTINEL");
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
