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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.DslWaitingAgent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.InternalAgentStateApi
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.persistence.AgentProcessCheckpointPolicy
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(InternalAgentStateApi::class)
class PersistentAgentProcessRepositoryTest {

    private val objectMapper = EmbabelObjectMapperHolder.createDefault().get()
    private val blackboardSnapshotter = InMemoryBlackboardSnapshotter(
        BlackboardEntrySerializerResolver(
            serializers = emptyList(),
            fallback = JacksonBlackboardEntrySerializer(objectMapper),
        )
    )
    private val snapshotFactory = AgentProcessSnapshotFactory(blackboardSnapshotter)
    private val snapshotSerializer = JacksonAgentProcessStateSerializer(objectMapper)
    private val snapshotRestorer = AgentProcessSnapshotRestorer(blackboardSnapshotter)

    @Test
    fun `checkpoints waiting process on save`() {
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val repository = repository(snapshotStore = snapshotStore)
        val process = waitingProcess("p1")

        repository.save(process)

        val snapshot = snapshotStore.findByProcessId("p1")
        assertNotNull(snapshot)
        assertEquals(1, snapshot?.version)
        assertEquals(AgentProcessStatusCode.WAITING, snapshot?.status)
    }

    @Test
    fun `wait policy does not checkpoint non waiting process`() {
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val repository = repository(
            snapshotStore = snapshotStore,
            checkpointPolicy = WaitForCheckpointPolicy,
        )
        val process = newProcess("p1")

        repository.save(process)

        assertNull(snapshotStore.findByProcessId("p1"))
    }

    @Test
    fun `checkpoints completed process on save`() {
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val repository = repository(snapshotStore = snapshotStore)
        val process = newProcess("p1")
        process.replaceRuntimeState(
            status = AgentProcessStatusCode.COMPLETED,
            history = emptyList(),
        )

        repository.save(process)

        val snapshot = snapshotStore.findByProcessId("p1")
        assertNotNull(snapshot)
        assertEquals(1, snapshot?.version)
        assertEquals(AgentProcessStatusCode.COMPLETED, snapshot?.status)
    }

    @Test
    fun `advances snapshot version on update`() {
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val repository = repository(snapshotStore = snapshotStore)
        val process = waitingProcess("p1")

        repository.save(process)
        repository.update(process)

        assertEquals(2, snapshotStore.findByProcessId("p1")?.version)
    }

    @Test
    fun `restores process from snapshot when runtime repository misses`() {
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val originalRepository = repository(
            runtimeRepository = InMemoryAgentProcessRepository(),
            snapshotStore = snapshotStore,
        )
        val process = waitingProcess("p1")
        originalRepository.save(process)

        val restoringRepository = repository(
            runtimeRepository = InMemoryAgentProcessRepository(),
            snapshotStore = snapshotStore,
        )

        val restored = restoringRepository.findById("p1")

        assertNotNull(restored)
        assertEquals("p1", restored?.id)
        assertEquals(AgentProcessStatusCode.WAITING, restored?.status)
        assertEquals(process.history, restored?.history)
    }

    private fun repository(
        runtimeRepository: AgentProcessRepository = InMemoryAgentProcessRepository(),
        snapshotStore: InMemoryAgentProcessSnapshotStore = InMemoryAgentProcessSnapshotStore(),
        checkpointPolicy: AgentProcessCheckpointPolicy = LifecycleCheckpointPolicy,
    ): PersistentAgentProcessRepository =
        PersistentAgentProcessRepository(
            runtimeRepository = runtimeRepository,
            snapshotStore = snapshotStore,
            checkpointPolicy = checkpointPolicy,
            snapshotFactory = snapshotFactory,
            snapshotSerializer = snapshotSerializer,
            snapshotRestorer = snapshotRestorer,
            agents = { listOf(DslWaitingAgent) },
            platformServices = { dummyPlatformServices() },
        )

    private fun waitingProcess(id: String): SimpleAgentProcess =
        newProcess(id).also {
            assertEquals(AgentProcessStatusCode.WAITING, it.run().status)
        }

    private fun newProcess(id: String): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return SimpleAgentProcess(
            id = id,
            parentId = null,
            agent = DslWaitingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        )
    }
}
