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
package com.embabel.agent.spi.persistence

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.persistence.BlackboardEntryDeserializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializationContext
import com.embabel.agent.core.persistence.BlackboardEntrySerializer
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.agent.core.support.DslWaitingAgent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.spi.support.persistence.InMemoryAgentProcessSnapshotStore
import com.embabel.agent.spi.support.persistence.WaitForCheckpointPolicy
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType

/**
 * Proves that a persistent repository can be assembled and used through public
 * API only.
 *
 * Deliberately references no `internal` type and takes no
 * [com.embabel.agent.core.support.InternalAgentStateApi] opt-in, so it exercises
 * exactly what an application or a third-party snapshot store module can reach.
 * Kotlin `internal` is module-wide, so the compiler would permit more here; the
 * restriction is the point of the test.
 */
class AgentProcessPersistenceTest {

    private val objectMapper = EmbabelObjectMapperHolder.createDefault().get()

    @Test
    fun `checkpoints a waiting process`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = persistentRepository(snapshotStore = store)

        repository.save(waitingProcess("p1"))

        val snapshot = store.findByProcessId("p1")
        assertNotNull(snapshot)
        assertEquals(AgentProcessStatusCode.WAITING, snapshot?.status)
        assertEquals(1L, snapshot?.version)
    }

    @Test
    fun `restores a process after the runtime repository is lost`() {
        // The Kubernetes autoscaling case: the pod holding the process is gone,
        // and a different node must resume it from durable state alone.
        val store = InMemoryAgentProcessSnapshotStore()
        val original = waitingProcess("p1")
        persistentRepository(snapshotStore = store).save(original)

        val onAnotherNode = persistentRepository(
            runtimeRepository = InMemoryAgentProcessRepository(),
            snapshotStore = store,
        )
        val restored = onAnotherNode.findById("p1")

        assertNotNull(restored)
        assertEquals("p1", restored?.id)
        assertEquals(AgentProcessStatusCode.WAITING, restored?.status)
        assertEquals(original.history, restored?.history)
    }

    @Test
    fun `repopulates the runtime repository on restore`() {
        val store = InMemoryAgentProcessSnapshotStore()
        persistentRepository(snapshotStore = store).save(waitingProcess("p1"))

        val runtime = InMemoryAgentProcessRepository()
        persistentRepository(runtimeRepository = runtime, snapshotStore = store).findById("p1")

        assertNotNull(runtime.findById("p1"), "restore should warm the runtime repository")
    }

    @Test
    fun `defaults to checkpointing finished processes as well as waiting ones`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = persistentRepository(snapshotStore = store)
        val process = waitingProcess("p1")

        repository.save(process)
        repository.update(process)

        assertEquals(2L, store.findByProcessId("p1")?.version, "update should advance the version")
    }

    @Test
    fun `honours a supplied checkpoint policy`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val repository = persistentRepository(
            snapshotStore = store,
            checkpointPolicy = WaitForCheckpointPolicy,
        )

        repository.save(newProcess("p1"))

        assertNull(store.findByProcessId("p1"), "a NOT_STARTED process is not a wait checkpoint")
    }

    @Test
    fun `applies a custom blackboard entry serializer`() {
        val serializer = RecordingSerializer()
        val repository = persistentRepository(blackboardEntrySerializers = listOf(serializer))

        repository.save(waitingProcess("p1"))

        assertTrue(serializer.used, "a registered serializer should take precedence over the Jackson fallback")
    }

    @Test
    fun `deletes from both the runtime repository and the snapshot store`() {
        val store = InMemoryAgentProcessSnapshotStore()
        val runtime = InMemoryAgentProcessRepository()
        val repository = persistentRepository(runtimeRepository = runtime, snapshotStore = store)
        val process = waitingProcess("p1")
        repository.save(process)

        repository.delete(process)

        assertNull(store.findByProcessId("p1"))
        assertNull(runtime.findById("p1"))
    }

    private fun persistentRepository(
        runtimeRepository: AgentProcessRepository = InMemoryAgentProcessRepository(),
        snapshotStore: AgentProcessSnapshotStore = InMemoryAgentProcessSnapshotStore(),
        checkpointPolicy: AgentProcessCheckpointPolicy? = null,
        blackboardEntrySerializers: List<BlackboardEntrySerializer> = emptyList(),
    ): AgentProcessRepository =
        if (checkpointPolicy == null) {
            AgentProcessPersistence.persistentRepository(
                runtimeRepository = runtimeRepository,
                snapshotStore = snapshotStore,
                objectMapper = objectMapper,
                agents = { listOf(DslWaitingAgent) },
                platformServices = { dummyPlatformServices() },
                blackboardEntrySerializers = blackboardEntrySerializers,
            )
        } else {
            AgentProcessPersistence.persistentRepository(
                runtimeRepository = runtimeRepository,
                snapshotStore = snapshotStore,
                objectMapper = objectMapper,
                agents = { listOf(DslWaitingAgent) },
                platformServices = { dummyPlatformServices() },
                checkpointPolicy = checkpointPolicy,
                blackboardEntrySerializers = blackboardEntrySerializers,
            )
        }

    private fun waitingProcess(id: String): AgentProcess =
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

    private class RecordingSerializer : BlackboardEntrySerializer {

        var used: Boolean = false

        override fun supportsSerialization(value: Any): Boolean = value is UserInput

        override fun serialize(
            value: Any,
            context: BlackboardEntrySerializationContext,
        ): SerializedBlackboardValue {
            used = true
            return SerializedBlackboardValue(
                typeName = value.javaClass.name,
                contentType = MediaType.TEXT_PLAIN_VALUE,
                payload = value.toString().toByteArray(),
            )
        }

        override fun deserialize(
            value: SerializedBlackboardValue,
            context: BlackboardEntryDeserializationContext,
        ): Any = value.payload.toString(Charsets.UTF_8)
    }
}
