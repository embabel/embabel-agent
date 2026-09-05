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
import com.embabel.agent.cache.CacheRegionConfig
import com.embabel.agent.cache.CacheRegions
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.cache.Caching

/**
 * End-to-end test for the JCache-backed snapshot persistence seam.
 *
 * Mirrors [CacheBackedPersistenceIT] substituting [JCacheAgentCacheProvider] backed
 * by Ehcache 3 (resolved via the JCache [Caching] SPI) for the Spring adapter.
 *
 * A single [javax.cache.CacheManager] is shared across all tests via the companion
 * (Ehcache's default manager requires an absolute URI; per-test unique URIs are
 * rejected). The snapshot region is cleared in [setUp] before each test; the isolation test
 * uses a distinct region name to simulate a separate store.
 *
 * The additional concurrent-CAS test proves [JCacheAgentCacheRegion] delivers true
 * [com.embabel.agent.cache.CacheCapability.ATOMIC_COMPARE_AND_SET] — a guarantee
 * [SpringCacheAgentCacheProvider] cannot make across JVM boundaries.
 */
class JCacheAgentProcessPersistenceTest {

    private val objectMapperHolder = EmbabelObjectMapperHolder.createDefault()

    private val provider = JCacheAgentCacheProvider(sharedCacheManager)

    @BeforeEach
    fun setUp() {
        // Clear before each test so stale data from a prior run (or failed retry) never
        // causes a false "expected version null but stored version differs" on first insert.
        region().clear()
    }

    private fun region() =
        provider.getRegion(CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS))

    private fun store(): AgentProcessSnapshotStore =
        CacheBackedAgentProcessSnapshotStore(region())

    // --- Tests mirroring CacheBackedPersistenceIT ---

    @Test
    fun `checkpoints a waiting process into the jcache region`() {
        val repository = persistentRepository(store())

        repository.save(waitingProcess("p1"))

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
    fun `another node resumes a waiting process from the shared jcache`() {
        val original = waitingProcess("p1")
        persistentRepository(store()).save(original)

        val survivingNode = persistentRepository(store())
        val restored = survivingNode.findById("p1")

        assertNotNull(restored)
        assertEquals("p1", restored?.id)
        assertEquals(AgentProcessStatusCode.WAITING, restored?.status)
        assertEquals(original.history, restored?.history)
    }

    @Test
    fun `a lost process is unrecoverable without the shared jcache`() {
        persistentRepository(store()).save(waitingProcess("p1"))

        // A distinct region name acts as a separate store — simulates a node that
        // shares no cache backend with the one that wrote the snapshot.
        val isolatedStore = CacheBackedAgentProcessSnapshotStore(
            provider.getRegion(CacheRegionConfig("isolated-snapshots-${UUID.randomUUID()}"))
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
    fun `deleting a process clears it from the jcache region`() {
        val repository = persistentRepository(store())
        val process = waitingProcess("p1")
        repository.save(process)

        repository.delete(process)

        assertNull(region().get("p1"))
    }

    // --- JCache-specific: proves ATOMIC_COMPARE_AND_SET ---

    @Test
    fun `concurrent checkpoint conflict is rejected by jcache CAS`() {
        // Seed an initial snapshot at version 1 so both threads have something to CAS against.
        persistentRepository(store()).save(waitingProcess("p-cas"))

        val casRegion = region()
        val v1 = casRegion.get("p-cas") ?: error("Expected seeded snapshot at version 1")
        assertEquals(1L, v1.version)

        val v2 = v1.copy(version = 2L)
        val wins = AtomicInteger(0)
        val losses = AtomicInteger(0)
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        repeat(2) {
            executor.submit {
                ready.await()
                if (casRegion.replace("p-cas", 1L, v2)) wins.incrementAndGet()
                else losses.incrementAndGet()
            }
        }

        ready.countDown()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(1, wins.get(), "exactly one thread must win the CAS")
        assertEquals(1, losses.get(), "exactly one thread must lose the CAS")
        assertEquals(2L, casRegion.get("p-cas")?.version, "stored version must be 2")
        assertFalse(
            casRegion.replace("p-cas", 1L, v2),
            "stale version must be rejected after the race",
        )
    }

    // --- Helpers ---

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

    private fun <T : Any> providerOf(type: Class<T>, vararg beans: T): ObjectProvider<T> {
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

        // Shared across all test instances: Ehcache's getCacheManager() no-arg
        // uses the provider's default absolute URI, avoiding the relative-URI
        // rejection that per-test unique URIs trigger.
        val sharedCacheManager: javax.cache.CacheManager by lazy {
            Caching.getCachingProvider().cacheManager
        }

        private val WaitingAgent =
            agent("JCacheWaiter", description = "Waits for confirmation") {

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
