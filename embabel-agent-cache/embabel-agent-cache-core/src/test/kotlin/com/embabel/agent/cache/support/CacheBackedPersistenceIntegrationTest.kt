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
package com.embabel.agent.cache.support

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.cache.AgentCacheRegion
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CacheRegions
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.waitFor
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.config.spring.AgentPlatformConfiguration
import com.embabel.agent.spi.config.spring.AgentProcessPersistenceProperties
import com.embabel.agent.spi.config.spring.ProcessRepositoryProperties
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.domain.Frog
import com.embabel.agent.test.domain.MagicVictim
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager

/**
 * End-to-end cover for the seam between the cache module and platform
 * persistence wiring.
 *
 * The unit tests either side of that seam use test doubles: the snapshot store
 * tests drive a region directly, and the platform wiring tests supply a
 * hand-built store. Nothing asserted that a real cache-backed store, registered
 * the way an application registers one, actually yields durable processes.
 *
 * This test uses the production wiring method
 * ([AgentPlatformConfiguration.agentProcessRepository]) with a real provider,
 * region and store. Only [AgentPlatform] is mocked, because it is a collaborator
 * of restore rather than anything under test here.
 */
class CacheBackedPersistenceIntegrationTest {

    private val objectMapperHolder = EmbabelObjectMapperHolder.createDefault()

    /**
     * Stands in for the shared cache infrastructure: two repositories built over
     * the same [CacheManager] represent two nodes reaching the same backend.
     */
    private val cacheManager: CacheManager = ConcurrentMapCacheManager()
    private val provider = SpringCacheAgentCacheProvider(cacheManager)

    private fun region(): AgentCacheRegion =
        provider.getRegion(CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS))

    private fun store(): AgentProcessSnapshotStore =
        CacheBackedAgentProcessSnapshotStore(region())

    @Test
    fun `checkpoints a waiting process into the cache region`() {
        val repository = persistentRepository(store())

        repository.save(waitingProcess("p1"))

        // Assert against the region, not the store, so the payload is proven to
        // have reached the cache rather than stopping at the store abstraction.
        val cached = region().get("p1")
        assertNotNull(cached)
        assertEquals(1L, cached?.version)
        assertTrue(cached!!.payload.isNotEmpty())
        assertEquals(
            AgentProcessStatusCode.WAITING.name,
            cached.metadata["embabel.snapshot.status"],
        )
    }

    @Test
    fun `another node resumes a waiting process from the shared cache`() {
        // The Kubernetes case #1965 describes: the pod owning the process is
        // scaled away while a human is still deciding.
        val original = waitingProcess("p1")
        persistentRepository(store()).save(original)

        // A fresh repository: its runtime store is empty, as on a different pod.
        val survivingNode = persistentRepository(store())
        val restored = survivingNode.findById("p1")

        assertNotNull(restored)
        assertEquals("p1", restored?.id)
        assertEquals(AgentProcessStatusCode.WAITING, restored?.status)
        assertEquals(original.history, restored?.history)
    }

    @Test
    fun `a lost process is unrecoverable without the shared cache`() {
        // Control for the test above: proves restore came from the cache and not
        // from anything the repositories share in memory.
        persistentRepository(store()).save(waitingProcess("p1"))

        val isolatedProvider = SpringCacheAgentCacheProvider(ConcurrentMapCacheManager())
        val isolatedStore = CacheBackedAgentProcessSnapshotStore(
            isolatedProvider.getRegion(CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS))
        )

        assertNull(persistentRepository(isolatedStore).findById("p1"))
    }

    @Test
    fun `nothing is cached when persistence is disabled`() {
        val repository = persistentRepository(
            store(),
            persistenceProperties = AgentProcessPersistenceProperties(enabled = false),
        )

        repository.save(waitingProcess("p1"))

        assertNull(region().get("p1"), "disabled persistence must not write to the cache")
    }

    @Test
    fun `runs in memory when no snapshot store is registered`() {
        val repository = AgentPlatformConfiguration().agentProcessRepository(
            processRepositoryProperties = ProcessRepositoryProperties(),
            persistenceProperties = AgentProcessPersistenceProperties(),
            snapshotStore = emptyProvider(),
            blackboardEntrySerializers = emptyProvider(),
            embabelObjectMapperHolder = objectMapperHolder,
            agentPlatform = agentPlatformProvider(),
        )

        repository.save(waitingProcess("p1"))

        assertNull(region().get("p1"))
        assertNotNull(repository.findById("p1"))
    }

    @Test
    fun `deleting a process clears it from the cache`() {
        val repository = persistentRepository(store())
        val process = waitingProcess("p1")
        repository.save(process)

        repository.delete(process)

        assertNull(region().get("p1"))
    }

    /**
     * Build the repository exactly as the platform does, over the given store.
     */
    private fun persistentRepository(
        snapshotStore: AgentProcessSnapshotStore,
        persistenceProperties: AgentProcessPersistenceProperties = AgentProcessPersistenceProperties(),
    ): AgentProcessRepository =
        AgentPlatformConfiguration().agentProcessRepository(
            processRepositoryProperties = ProcessRepositoryProperties(),
            persistenceProperties = persistenceProperties,
            snapshotStore = providerOf(AgentProcessSnapshotStore::class.java, snapshotStore),
            blackboardEntrySerializers = emptyProvider(),
            embabelObjectMapperHolder = objectMapperHolder,
            agentPlatform = agentPlatformProvider(),
        )

    private fun <T : Any> providerOf(
        type: Class<T>,
        vararg beans: T,
    ): ObjectProvider<T> {
        val beanFactory = DefaultListableBeanFactory()
        beans.forEachIndexed { index, bean -> beanFactory.registerSingleton("bean$index", bean) }
        return beanFactory.getBeanProvider(type)
    }

    private inline fun <reified T : Any> emptyProvider(): ObjectProvider<T> =
        DefaultListableBeanFactory().getBeanProvider(T::class.java)

    private fun agentPlatformProvider(): ObjectProvider<AgentPlatform> {
        val platform = mockk<AgentPlatform>(relaxed = true)
        every { platform.agents() } returns listOf(WaitingAgent)
        every { platform.platformServices } returns dummyPlatformServices()
        return providerOf(AgentPlatform::class.java, platform)
    }

    private fun waitingProcess(id: String) =
        newProcess(id).also {
            assertEquals(AgentProcessStatusCode.WAITING, it.run().status)
        }

    private fun newProcess(id: String): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return SimpleAgentProcess(
            id = id,
            parentId = null,
            agent = WaitingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        )
    }

    companion object {

        /**
         * Parks on a confirmation, the shape of HITL flow this feature exists for.
         */
        private val WaitingAgent: Agent =
            agent("CacheWaiter", description = "Waits for confirmation") {

                transformation<UserInput, MagicVictim>(name = "await-confirmation") {
                    waitFor(
                        ConfirmationRequest(
                            MagicVictim(name = "Rod"),
                            "Is this the dude?",
                        )
                    )
                }

                transformation<MagicVictim, Frog>(name = "to-frog") {
                    Frog(name = it.input.name)
                }

                goal(name = "done", description = "done", satisfiedBy = Frog::class)
            }
    }
}
