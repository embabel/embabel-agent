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

import com.embabel.agent.decision.DecisionModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Modifier;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionConfigurationArchitectureTest {
    @Test
    void configurationIsNotAnotherModelProductOrLlmRegistration() {
        assertThat(DecisionModel.class.getModifiers()).matches(Modifier::isFinal);
        assertThat(com.embabel.agent.spi.LlmService.class.isAssignableFrom(DecisionModel.class)).isFalse();
        assertThat(ChatModel.class.isAssignableFrom(DecisionModel.class)).isFalse();
        assertThat(AgentDecisionAutoConfiguration.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .isEmpty();
    }

    @Test
    void noRuntimeHintsOrReflectiveWireBindingAreIntroduced() {
        String methods = java.util.Arrays.toString(AgentDecisionAutoConfiguration.class.getDeclaredMethods());
        assertThat(methods).doesNotContain("readValue", "treeToValue", "convertValue", "RuntimeHintsRegistrar");
    }

    @Test
    void publicFactoriesRemainExactlyTheFourNamedProductsReturningTheFinalFacade() throws Exception {
        Set<Class<?>> products = Set.of(
                com.embabel.agent.decision.typesafe.TypeSafeDecisionModel.class,
                com.embabel.agent.decision.llm.PromptedDecisionModel.class,
                com.embabel.agent.decision.NoDecisionModel.class,
                com.embabel.agent.decision.StubDecisionModel.class);
        assertThat(products).hasSize(4);
        assertThat(products).allSatisfy(product -> assertThat(java.util.Arrays.stream(product.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> method.getName().equals("create")))
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(DecisionModel.class)));
        assertThat(java.util.Arrays.toString(AgentDecisionAutoConfiguration.class.getDeclaredMethods()))
                .doesNotContain("ProviderInitialization", "ModelProvider", "ChatModel", "LlmService");
    }
}
