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
package com.embabel.agent.autoconfigure.platform.cache

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.waitFor
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.config.spring.AgentPlatformConfiguration
import com.embabel.agent.spi.config.spring.AgentProcessPersistenceProperties
import com.embabel.agent.spi.config.spring.ProcessRepositoryProperties
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.function.Supplier

/**
 * End-to-end cover for cache auto-configuration: a Spring `CacheManager` plus one
 * property is all an application declares, and agent processes become durable.
 *
 * Each application context stands in for a pod. Two contexts sharing one
 * `CacheManager` instance model two pods reaching the same backend; a context with
 * its own `CacheManager` is the control, proving restore travels through the cache.
 *
 * The platform's repository is built with the production `agentProcessRepository`
 * wiring method over the snapshot store the auto-configuration registered. The
 * agent platform itself is mocked because only restore consults it.
 */
class CacheAutoConfigurationPersistenceIntegrationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AgentCacheProviderAutoConfiguration::class.java,
                CacheSnapshotStoreAutoConfiguration::class.java,
            )
        )

    private val durable = runner.withPropertyValues(
        "embabel.agent.platform.cache.provider=spring-cache",
        // The Spring adapter cannot guarantee compare-and-set; these contexts model
        // a single writer per process.
        "embabel.agent.platform.cache.require-atomic-compare-and-set=false",
    )

    @Test
    fun `a process parked on one pod resumes on another`() {
        val sharedBackend = ConcurrentMapCacheManager()
        val original = waitingProcess("p1")

        durable.withCacheManager(sharedBackend).run { podA ->
            assertThat(podA).hasNotFailed()
            platformRepository(podA).save(original)
        }

        durable.withCacheManager(sharedBackend).run { podB ->
            val restored = platformRepository(podB).findById("p1")

            assertThat(restored).isNotNull()
            assertThat(restored!!.status).isEqualTo(AgentProcessStatusCode.WAITING)
            assertThat(restored.history).isEqualTo(original.history)
        }
    }

    @Test
    fun `a pod with a different backend cannot resume the process`() {
        durable.withCacheManager(ConcurrentMapCacheManager()).run { podA ->
            platformRepository(podA).save(waitingProcess("p1"))
        }

        durable.withCacheManager(ConcurrentMapCacheManager()).run { isolated ->
            assertThat(platformRepository(isolated).findById("p1")).isNull()
        }
    }

    @Test
    fun `a cache manager without the provider property leaves processes in memory`() {
        val sharedBackend = ConcurrentMapCacheManager()

        runner.withCacheManager(sharedBackend).run { podA ->
            assertThat(podA).doesNotHaveBean(AgentProcessSnapshotStore::class.java)
            platformRepository(podA).save(waitingProcess("p1"))
        }

        runner.withCacheManager(sharedBackend).run { podB ->
            assertThat(platformRepository(podB).findById("p1")).isNull()
        }
    }

    private fun ApplicationContextRunner.withCacheManager(cacheManager: CacheManager): ApplicationContextRunner =
        withBean(CacheManager::class.java, Supplier { cacheManager })

    /**
     * The repository exactly as the platform builds it, over whatever snapshot store
     * the context holds. Each call has a fresh runtime repository, as a new pod would.
     */
    private fun platformRepository(context: AssertableApplicationContext): AgentProcessRepository =
        AgentPlatformConfiguration().agentProcessRepository(
            processRepositoryProperties = ProcessRepositoryProperties(),
            persistenceProperties = AgentProcessPersistenceProperties(),
            snapshotStore = context.getBeanProvider(AgentProcessSnapshotStore::class.java),
            blackboardEntrySerializers = context.getBeanProvider(BlackboardEntrySerializer::class.java),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            agentPlatform = agentPlatform(),
        )

    private fun agentPlatform(): ObjectProvider<AgentPlatform> {
        val platform = mockk<AgentPlatform>(relaxed = true)
        every { platform.agents() } returns listOf(ConfirmingAgent)
        every { platform.platformServices } returns dummyPlatformServices()
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton("agentPlatform", platform)
        return beanFactory.getBeanProvider(AgentPlatform::class.java)
    }

    private fun waitingProcess(id: String): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return SimpleAgentProcess(
            id = id,
            parentId = null,
            agent = ConfirmingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        ).also {
            assertThat(it.run().status).isEqualTo(AgentProcessStatusCode.WAITING)
        }
    }

    data class Suspect(val name: String)

    data class Verdict(val name: String)

    companion object {

        /**
         * Parks on a confirmation: the human-in-the-loop shape this feature exists for.
         */
        private val ConfirmingAgent: Agent =
            agent("CacheAutoConfigurationWaiter", description = "Waits for confirmation") {

                transformation<UserInput, Suspect>(name = "await-confirmation") {
                    waitFor(ConfirmationRequest(Suspect(name = "Rod"), "Is this the dude?"))
                }

                transformation<Suspect, Verdict>(name = "decide") {
                    Verdict(name = it.input.name)
                }

                goal(name = "done", description = "done", satisfiedBy = Verdict::class)
            }
    }
}
