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
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.support.ConcurrentAgentProcess
import com.embabel.agent.core.support.DslWaitingAgent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentProcessSnapshotRestorerTest {

    private val objectMapper = EmbabelObjectMapperHolder.createDefault().get()
    private val blackboardSnapshotter = InMemoryBlackboardSnapshotter(
        BlackboardEntrySerializerResolver(
            serializers = emptyList(),
            fallback = JacksonBlackboardEntrySerializer(objectMapper),
        )
    )
    private val snapshotFactory = AgentProcessSnapshotFactory(blackboardSnapshotter)
    private val restorer = AgentProcessSnapshotRestorer(blackboardSnapshotter)

    @Test
    fun `restores simple process state and blackboard`() {
        val original = waitingProcess()
        val snapshot = snapshotFactory.snapshot(original, version = 1)

        val restored = restorer.restore(
            snapshot = snapshot,
            agents = listOf(DslWaitingAgent),
            platformServices = dummyPlatformServices(),
        )

        assertEquals(snapshot.processId, restored.id)
        assertEquals(snapshot.parentId, restored.parentId)
        assertEquals(DslWaitingAgent.name, restored.agent.name)
        assertEquals(AgentProcessStatusCode.WAITING, restored.status)
        assertEquals(snapshot.history, restored.history)
        assertTrue(restored.blackboard.objects.any { it is ConfirmationRequest<*> })
    }

    @Test
    fun `restores concurrent process implementation`() {
        val original = waitingConcurrentProcess()
        val snapshot = snapshotFactory.snapshot(original, version = 1)

        val restored = restorer.restore(
            snapshot = snapshot,
            agents = listOf(DslWaitingAgent),
            platformServices = dummyPlatformServices(),
        )

        assertTrue(restored is ConcurrentAgentProcess)
        assertEquals(snapshot.processImplementationClassName, restored.javaClass.name)
        assertEquals(AgentProcessStatusCode.WAITING, restored.status)
        assertEquals(snapshot.history, restored.history)
    }

    @Test
    fun `rejects unknown agent`() {
        val snapshot = snapshotFactory.snapshot(waitingProcess(), version = 1)

        assertThrows(AgentProcessPersistenceException::class.java) {
            restorer.restore(
                snapshot = snapshot.copy(agentName = "missing"),
                agents = emptyList(),
                platformServices = dummyPlatformServices(),
            )
        }
    }

    @Test
    fun `rejects unsupported process implementation`() {
        val snapshot = snapshotFactory.snapshot(waitingProcess(), version = 1)

        assertThrows(AgentProcessPersistenceException::class.java) {
            restorer.restore(
                snapshot = snapshot.copy(processImplementationClassName = "example.UnsupportedProcess"),
                agents = listOf(DslWaitingAgent),
                platformServices = dummyPlatformServices(),
            )
        }
    }

    private fun waitingProcess(): SimpleAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return SimpleAgentProcess(
            id = "p1",
            parentId = null,
            agent = DslWaitingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        ).also {
            assertEquals(AgentProcessStatusCode.WAITING, it.run().status)
        }
    }

    private fun waitingConcurrentProcess(): ConcurrentAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return ConcurrentAgentProcess(
            id = "p-concurrent",
            parentId = null,
            agent = DslWaitingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        ).also {
            assertEquals(AgentProcessStatusCode.WAITING, it.run().status)
        }
    }
}
