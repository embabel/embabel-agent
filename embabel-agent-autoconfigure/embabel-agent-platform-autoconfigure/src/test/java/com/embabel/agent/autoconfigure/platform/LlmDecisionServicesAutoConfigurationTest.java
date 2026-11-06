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

import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmDecisionServicesAutoConfigurationTest {

    private final DecisionService triage = mock(DecisionService.class);

    private final LlmDecisionServiceFactory factory = mock(LlmDecisionServiceFactory.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LlmDecisionServicesAutoConfiguration.class, DecisionServiceRegistryAutoConfiguration.class))
            .withBean(LlmDecisionServiceFactory.class, () -> factory);

    LlmDecisionServicesAutoConfigurationTest() {
        when(factory.modelNames()).thenReturn(List.of("small-model"));
        when(factory.decisionServiceNamed("small-model")).thenReturn(triage);
    }

    @Test
    void chatModelsAreNotRegisteredAsDecisionServiceBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DecisionService.class)).isEmpty();
            verify(factory, never()).decisionServiceNamed("small-model");
        });
    }

    @Test
    void aDecisionDefaultCanNameAChatModel() {
        runner.withPropertyValues("embabel.models.decision.default=small-model")
                .run(context -> {
                    var registry = context.getBean(DecisionServiceRegistry.class);
                    assertThat(registry.decisions().defaultService()).isSameAs(triage);
                    assertThat(registry.classifications().named("small-model")).isSameAs(triage);
                });
    }

    @Test
    void unknownRoleModelFailsStartup() {
        runner.withPropertyValues("embabel.models.decision.roles.review=missing")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("'missing'")
                        .hasMessageContaining("small-model"));
    }
}
