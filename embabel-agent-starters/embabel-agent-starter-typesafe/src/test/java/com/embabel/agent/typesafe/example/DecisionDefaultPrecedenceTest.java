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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.embabel.agent.autoconfigure.models.typesafe.AgentTypeSafeAutoConfiguration;
import com.embabel.agent.autoconfigure.platform.DecisionServiceRegistryAutoConfiguration;
import com.embabel.agent.autoconfigure.platform.LlmDecisionDefaultCandidateAutoConfiguration;
import com.embabel.agent.autoconfigure.platform.LlmDecisionDefaultCandidateRegistrar;
import com.embabel.agent.autoconfigure.platform.LlmDecisionServicesAutoConfiguration;
import com.embabel.agent.core.internal.LlmOperations;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.ModelProvider;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * A configured TypeSafe service stays the family default, even with the prompted default service
 * turned on; the prompted one stands down.
 */
class DecisionDefaultPrecedenceTest {

    private final ModelProvider modelProvider = mock(ModelProvider.class);

    DecisionDefaultPrecedenceTest() {
        when(modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE))
                .thenAnswer(call -> new SpringAiLlmService("gpt-test", "TestProvider", mock(ChatModel.class)));
    }

    @Test
    void typeSafeStaysTheDefaultWhenItsCredentialIsConfigured() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AgentTypeSafeAutoConfiguration.class,
                        LlmDecisionServicesAutoConfiguration.class,
                        LlmDecisionDefaultCandidateAutoConfiguration.class,
                        DecisionServiceRegistryAutoConfiguration.class))
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(LlmOperations.class, () -> mock(LlmOperations.class))
                .withBean(ModelProvider.class, () -> modelProvider)
                .withPropertyValues(
                        "TYPESAFE_API_KEY=",
                        "embabel.agent.platform.models.typesafe.api-key=test-key",
                        "embabel.agent.platform.decisions.llm.default-candidate=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                    assertThat(context).hasSingleBean(DecisionServiceRegistry.DefaultCandidate.class);
                    assertThat(context.getBean(DecisionServiceRegistry.class).decisions().defaultService())
                            .isSameAs(context.getBean("typeSafeDecisionService"));
                });
    }
}
