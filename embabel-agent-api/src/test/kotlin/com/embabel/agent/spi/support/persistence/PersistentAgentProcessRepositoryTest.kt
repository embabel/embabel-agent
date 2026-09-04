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
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.support.DslWaitingAgent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.InternalAgentStateApi
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.persistence.AgentProcessCheckpointPolicy
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import com.embabel.agent.spi.persistence.StoredSnapshotMetadata
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

        val snapshot = snapshotStore.findLatestByProcessId("p1")
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

        assertNull(snapshotStore.findLatestByProcessId("p1"))
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

        val snapshot = snapshotStore.findLatestByProcessId("p1")
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

        assertEquals(2, snapshotStore.findLatestByProcessId("p1")?.version)
    }

    @Test
    fun `snapshot save failure on initial save does not register process in runtime repository`() {
        val runtimeRepository = InMemoryAgentProcessRepository()
        val repository = PersistentAgentProcessRepository(
            runtimeRepository = runtimeRepository,
            snapshotStore = FailingAgentProcessSnapshotStore(),
            checkpointPolicy = LifecycleCheckpointPolicy,
            snapshotFactory = snapshotFactory,
            snapshotSerializer = snapshotSerializer,
            snapshotRestorer = snapshotRestorer,
            agents = { listOf(DslWaitingAgent) },
            platformServices = { dummyPlatformServices() },
        )

        assertThrows<AgentProcessPersistenceException> { repository.save(waitingProcess("p1")) }

        assertNull(runtimeRepository.findById("p1"), "process must not be registered when checkpoint fails")
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

    @Test
    fun `delete removes both the snapshot and the runtime entry`() {
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val runtimeRepository = InMemoryAgentProcessRepository()
        val repository = repository(runtimeRepository = runtimeRepository, snapshotStore = snapshotStore)
        val process = waitingProcess("p1")
        repository.save(process)

        repository.delete(process)

        assertNull(snapshotStore.findLatestByProcessId("p1"))
        assertNull(runtimeRepository.findById("p1"))
    }

    @Test
    fun `delete removes the snapshot before the runtime entry`() {
        // Removing the runtime entry first leaves a durable snapshot behind when the
        // snapshot delete then fails, and findById restores from it: a deleted process
        // comes back to life. Snapshot-first mirrors doSave, which checkpoints before
        // registering, so a failure can never leave durable state the runtime denies.
        val snapshotStore = InMemoryAgentProcessSnapshotStore()
        val repository = repository(
            runtimeRepository = FailingDeleteAgentProcessRepository(),
            snapshotStore = snapshotStore,
        )
        val process = waitingProcess("p1")
        repository.save(process)

        assertThrows(IllegalStateException::class.java) { repository.delete(process) }

        assertNull(
            snapshotStore.findLatestByProcessId("p1"),
            "the snapshot must already be gone when the runtime delete fails",
        )
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

    /**
     * Runtime repository that fails only on delete, so the ordering of the two
     * deletes is observable.
     */
    private class FailingDeleteAgentProcessRepository : AgentProcessRepository {
        private val delegate = InMemoryAgentProcessRepository()
        override fun findById(id: String): AgentProcess? = delegate.findById(id)
        override fun findByParentId(parentId: String): List<AgentProcess> = delegate.findByParentId(parentId)
        override fun save(agentProcess: AgentProcess): AgentProcess = delegate.save(agentProcess)
        override fun update(agentProcess: AgentProcess) = delegate.update(agentProcess)
        override fun delete(agentProcess: AgentProcess): Unit =
            throw IllegalStateException("runtime repository unavailable")
    }

    private class FailingAgentProcessSnapshotStore : AgentProcessSnapshotStore {
        override fun save(snapshot: SerializedAgentProcessSnapshot, expectedVersion: Long?): StoredSnapshotMetadata =
            throw AgentProcessPersistenceException("store unavailable")
        override fun findLatestByProcessId(processId: String): SerializedAgentProcessSnapshot? = null
        override fun findByParentId(parentId: String): List<SerializedAgentProcessSnapshot> = emptyList()
        override fun delete(processId: String) {}
    }

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
