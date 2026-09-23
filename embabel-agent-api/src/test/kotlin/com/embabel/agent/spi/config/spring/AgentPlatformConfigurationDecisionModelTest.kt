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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.decision.CallFailure
import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.DecisionProvider
import com.embabel.agent.decision.DecisionSafeCode
import com.embabel.agent.decision.RawDecisionOutcome
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.ModelProvider
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

class AgentPlatformConfigurationDecisionModelTest {

    private val runner = ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=decision-model-cycle-test")
        .withUserConfiguration(CircularDecisionConfiguration::class.java)

    @Test
    fun `a decision model depending on the model provider fails loudly instead of being omitted`() {
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasStackTraceContaining("decisionModel")
                .hasStackTraceContaining("currently in creation")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("decision-model-cycle-test")
    class CircularDecisionConfiguration {

        @Bean
        fun properties() = ConfigurableModelProviderProperties(defaultLlm = "test-llm")

        @Bean
        fun llm(): LlmService<*> =
            SpringAiLlmService("test-llm", "test", mockk<ChatModel>(), DefaultOptionsConverter)

        @Bean(name = ["modelProvider"])
        fun modelProvider(
            applicationContext: ApplicationContext,
            properties: ConfigurableModelProviderProperties,
        ): ModelProvider = AgentPlatformConfiguration().modelProvider(
            applicationContext = applicationContext,
            properties = properties,
            providerInitialization = emptyList(),
            decisionModelInitialization = emptyList(),
        )

        @Bean
        fun decisionModel(modelProvider: ModelProvider): DecisionModel =
            DecisionModel(DecisionProvider {
                modelProvider.listModels()
                RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
            }).named("dependent", "test")
    }
}
