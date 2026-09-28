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

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.DefaultAgentPlatform
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.RankingProperties
import com.embabel.agent.spi.support.SpringContextPlatformServices
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyProcessContext
import com.embabel.agent.test.unit.DummyAgent
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.DecisionServiceRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import java.util.function.Supplier

/**
 * Boots the platform configuration, which applications get from the platform auto-configuration,
 * and reaches a prompted service through the registry and through `Ai`. The auto-configuration also
 * scans in the decision configurations, so this test lists them.
 */
class DecisionServiceRegistryBootTest {

    private val llm = mockk<LlmService<*>> {
        every { name } returns "fake"
        every { provider } returns "FakeProvider"
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(
            AgentPlatformConfiguration::class.java,
            LlmDecisionServiceConfiguration::class.java,
            DecisionServiceRegistryConfiguration::class.java,
            ConfiguredReview::class.java,
        )
        .withBean("fake", LlmService::class.java, Supplier { llm })
        .withBean(LlmOperations::class.java, Supplier { mockk<LlmOperations>() })
        .withBean(RankingProperties::class.java, Supplier { RankingProperties() })
        .withBean(ContextRepositoryProperties::class.java, Supplier { ContextRepositoryProperties() })
        .withBean(Asyncer::class.java, Supplier { ExecutorAsyncer(Runnable::run) })
        .withBean(DefaultAgentPlatform::class.java)
        .withPropertyValues(
            "embabel.models.default-llm=fake",
        )

    @Test
    fun `the platform configuration builds the registry with the configured prompted service`() {
        runner.run { context ->
            context.startupFailure?.let { throw it }
            val registry = context.getBean("decisionServiceRegistry", DecisionServiceRegistry::class.java)
            assertTrue(registry.registrationNames().contains("llm-review"), registry.registrationNames().toString())
            assertSame(context.getBean("llm-review", DecisionService::class.java), registry.decisions().named("llm-review"))
        }
    }

    @Test
    fun `the booted platform's services return the registry bean`() {
        runner.run { context ->
            context.startupFailure?.let { throw it }
            val platformServices = context.getBean(AgentPlatform::class.java).platformServices
            assertTrue(platformServices is SpringContextPlatformServices)
            assertSame(context.getBean(DecisionServiceRegistry::class.java), platformServices.decisionServices())
        }
    }

    @Test
    fun `Ai in an operation on the booted platform resolves the default decision service`() {
        runner.run { context ->
            context.startupFailure?.let { throw it }
            val platformServices = context.getBean(AgentPlatform::class.java).platformServices
            val processContext = dummyProcessContext(DummyAgent).copy(platformServices = platformServices)
            val service = FakeOperationContext(processContext = processContext).ai().decisions().defaultService()
            assertEquals("fake", service.name)
            assertEquals("FakeProvider", service.provider)
            assertEquals(
                context.getBean("llm-review", DecisionService::class.java).capabilities(),
                service.capabilities(),
            )
        }
    }

    /** Stands in for a configured prompted service: the platform factory builds `llm-review`. */
    class ConfiguredReview {
        @Bean("llm-review")
        fun review(factory: LlmDecisionServiceFactory): DecisionService = factory.decisionService("fake")
    }
}
