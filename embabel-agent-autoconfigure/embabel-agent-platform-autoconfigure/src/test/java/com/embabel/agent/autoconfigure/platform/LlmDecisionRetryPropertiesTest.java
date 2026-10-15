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
package com.embabel.agent.autoconfigure.platform;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmDecisionRetryPropertiesTest {

    @Nested
    class Binding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(RetryPropertiesOnly.class);

        @Test
        void unsetSettingsTakeThePlatformDefaults() {
            runner.run(context -> {
                var retry = context.getBean(LlmDecisionRetryProperties.class);
                assertThat(retry.getMaxAttempts()).isEqualTo(5);
                assertThat(retry.getBackoffMillis()).isEqualTo(100L);
                assertThat(retry.getBackoffMultiplier()).isEqualTo(5.0);
                assertThat(retry.getBackoffMaxInterval()).isEqualTo(180000L);
                assertThat(retry.getPropertyPrefix()).isEqualTo("embabel.agent.platform.decisions.llm");
            });
        }

        @Test
        void settingsBindFromTheDecisionsPrefix() {
            runner.withPropertyValues(
                            "embabel.agent.platform.decisions.llm.max-attempts=2",
                            "embabel.agent.platform.decisions.llm.backoff-millis=7",
                            "embabel.agent.platform.decisions.llm.backoff-multiplier=1.5",
                            "embabel.agent.platform.decisions.llm.backoff-max-interval=40")
                    .run(context -> {
                        var retry = context.getBean(LlmDecisionRetryProperties.class);
                        assertThat(retry.getMaxAttempts()).isEqualTo(2);
                        assertThat(retry.getBackoffMillis()).isEqualTo(7L);
                        assertThat(retry.getBackoffMultiplier()).isEqualTo(1.5);
                        assertThat(retry.getBackoffMaxInterval()).isEqualTo(40L);
                    });
        }

        @Test
        void declaredServicesBesideTheSettingsDoNotFailBinding() {
            runner.withPropertyValues(
                            "embabel.agent.platform.decisions.llm.max-attempts=3",
                            "embabel.agent.platform.decisions.llm.services.triage.llm=small-model")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(LlmDecisionRetryProperties.class).getMaxAttempts()).isEqualTo(3);
                    });
        }

        @Test
        void anOutOfRangeSettingFailsStartupAndNamesThePrefix() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.max-attempts=0").run(context -> {
                var chain = failures(context);
                var bind = chain.stream().filter(BindException.class::isInstance).map(BindException.class::cast)
                        .findFirst().orElseThrow();
                assertThat(bind.getName()).hasToString("embabel.agent.platform.decisions.llm");
                var invalid = chain.stream().filter(IllegalArgumentException.class::isInstance)
                        .reduce((first, second) -> second).orElseThrow();
                assertThat(invalid.getMessage()).isEqualTo("max-attempts must be at least 1");
            });
        }
    }

    @Nested
    class Validation {

        @Test
        void maxAttemptsBelowOneIsRejected() {
            assertThatThrownBy(() -> new LlmDecisionRetryProperties(0, 100L, 5.0, 180000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("max-attempts must be at least 1");
        }

        @Test
        void backoffBelowOneMillisecondIsRejected() {
            assertThatThrownBy(() -> new LlmDecisionRetryProperties(5, 0L, 5.0, 180000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("backoff-millis must be at least 1");
        }

        @ParameterizedTest
        @ValueSource(doubles = {1.0, 0.5})
        void backoffMultiplierOfOneOrLessIsRejected(double multiplier) {
            assertThatThrownBy(() -> new LlmDecisionRetryProperties(5, 100L, multiplier, 180000L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("backoff-multiplier must be greater than 1");
        }

        @ParameterizedTest
        @ValueSource(longs = {100L, 99L})
        void maxIntervalNoLongerThanTheFirstWaitIsRejected(long maxInterval) {
            assertThatThrownBy(() -> new LlmDecisionRetryProperties(5, 100L, 5.0, maxInterval))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("backoff-max-interval must be greater than backoff-millis");
        }

        @Test
        void theSmallestValidSettingsBuildARetryTemplate() {
            var retry = new LlmDecisionRetryProperties(1, 1L, 1.01, 2L);
            assertThatCode(() -> retry.retryTemplate("decision-test")).doesNotThrowAnyException();
        }
    }

    private static List<Throwable> failures(AssertableApplicationContext context) {
        var chain = new ArrayList<Throwable>();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    @EnableConfigurationProperties(LlmDecisionRetryProperties.class)
    static class RetryPropertiesOnly {
    }
}
