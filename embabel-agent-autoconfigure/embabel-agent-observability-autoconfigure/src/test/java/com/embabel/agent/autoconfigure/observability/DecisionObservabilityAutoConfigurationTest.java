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
package com.embabel.agent.autoconfigure.observability;

import com.embabel.agent.decision.DecisionCompletion;
import com.embabel.agent.decision.DecisionInstrumentation;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionObservation;
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.DecisionTelemetryEvent;
import com.embabel.agent.decision.NoDecisionModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionObservabilityAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void userModelKeepsIdentityAndReceivesTheDefaultAdapterWithoutRegistries() {
        DecisionModel userModel = NoDecisionModel.create().named("user", "custom");
        runner.withBean("user", DecisionModel.class, () -> userModel)
                .withPropertyValues(
                        "embabel.agent.platform.observability.tracing-enabled=false",
                        "embabel.agent.platform.observability.metrics-enabled=false")
                .run(context -> {
                    assertThat(context.getBean("user", DecisionModel.class)).isSameAs(userModel);
                    assertThat(context).hasSingleBean(DecisionInstrumentation.class)
                            .doesNotHaveBean(ObservationRegistry.class)
                            .doesNotHaveBean(MeterRegistry.class);
                    assertThat(userModel.ask(request())).isInstanceOf(DecisionOutcome.Failure.class);
                });
    }

    @Test
    void userInstrumentationBeanIsUsedWithoutCreatingTheDefaultAdapter() {
        var starts = new AtomicInteger();
        DecisionInstrumentation userInstrumentation = countingInstrumentation(starts);
        DecisionModel userModel = NoDecisionModel.create().named("user", "custom");
        runner.withBean(DecisionInstrumentation.class, () -> userInstrumentation)
                .withBean("user", DecisionModel.class, () -> userModel)
                .run(context -> {
                    assertThat(context).hasSingleBean(DecisionInstrumentation.class);
                    assertThat(context.getBean(DecisionInstrumentation.class)).isSameAs(userInstrumentation);
                    userModel.ask(request());
                    assertThat(starts).hasValue(1);
                });
    }

    @Test
    void explicitModelInstrumentationWinsOverThePostProcessorDefault() {
        var explicitStarts = new AtomicInteger();
        var defaultStarts = new AtomicInteger();
        DecisionModel userModel = NoDecisionModel.create()
                .named("user", "custom")
                .withInstrumentation(countingInstrumentation(explicitStarts));
        runner.withBean(DecisionInstrumentation.class, () -> countingInstrumentation(defaultStarts))
                .withBean("user", DecisionModel.class, () -> userModel)
                .run(context -> {
                    assertThat(context.getBean("user", DecisionModel.class)).isSameAs(userModel);
                    userModel.ask(request());
                    assertThat(explicitStarts).hasValue(1);
                    assertThat(defaultStarts).hasValue(0);
                });
    }

    @Test
    void explicitNoopInstrumentationAlsoWinsOverThePostProcessorDefault() {
        var defaultStarts = new AtomicInteger();
        DecisionModel userModel = NoDecisionModel.create()
                .named("user", "custom")
                .withInstrumentation(DecisionInstrumentation.noop());
        runner.withBean(DecisionInstrumentation.class, () -> countingInstrumentation(defaultStarts))
                .withBean("user", DecisionModel.class, () -> userModel)
                .run(context -> {
                    assertThat(context.getBean("user", DecisionModel.class)).isSameAs(userModel);
                    userModel.ask(request());
                    assertThat(defaultStarts).hasValue(0);
                });
    }

    @Test
    void defaultAdapterStaysLazyWhenThereAreNoDecisionModels() {
        runner.run(context -> {
            assertThat(context.containsBeanDefinition("decisionInstrumentation")).isTrue();
            assertThat(context.getBeanFactory().containsSingleton("decisionInstrumentation")).isFalse();
        });
    }

    private static DecisionRequest request() {
        var builder = DecisionRequest.builder();
        builder.yesNo("approved", "Approve?");
        return builder.build();
    }

    private static DecisionInstrumentation countingInstrumentation(AtomicInteger starts) {
        return context -> {
            starts.incrementAndGet();
            return new DecisionObservation() {
                @Override
                public <T> Callable<T> wrap(Callable<T> work) {
                    return work;
                }

                @Override
                public void event(DecisionTelemetryEvent event) {
                }

                @Override
                public void complete(DecisionCompletion completion) {
                }

                @Override
                public void close() {
                }
            };
        };
    }
}
