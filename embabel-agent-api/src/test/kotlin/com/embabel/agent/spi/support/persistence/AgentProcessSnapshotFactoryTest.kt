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
import com.embabel.agent.core.support.DslWaitingAgent
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.InternalAgentStateApi
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(InternalAgentStateApi::class)
class AgentProcessSnapshotFactoryTest {

    private val snapshotFactory = AgentProcessSnapshotFactory(
        InMemoryBlackboardSnapshotter(
            BlackboardEntrySerializerResolver(
                serializers = emptyList(),
                fallback = JacksonBlackboardEntrySerializer(EmbabelObjectMapperHolder.createDefault().get()),
            )
        )
    )

    @Test
    fun `snapshots waiting process with pending awaitable`() {
        val process = waitingProcess()

        val snapshot = snapshotFactory.snapshot(process, version = 7)

        val awaitable = process.blackboard.last(ConfirmationRequest::class.java)
        assertEquals("p1", snapshot.processId)
        assertEquals("Waiter", snapshot.agentName)
        assertEquals(process.javaClass.name, snapshot.processImplementationClassName)
        assertEquals(AgentProcessStatusCode.WAITING, snapshot.status)
        assertEquals(7, snapshot.version)
        assertEquals(awaitable?.id, snapshot.pendingAwaitableId)
        assertTrue(snapshot.blackboard.entries.any { it.value.typeName == ConfirmationRequest::class.java.name })
    }

    @Test
    fun `carries parent process id`() {
        val process = waitingProcess(id = "child", parentId = "parent")

        val snapshot = snapshotFactory.snapshot(process, version = 1)

        assertEquals("parent", snapshot.parentId)
    }

    @Test
    fun `snapshots completed process without pending awaitable`() {
        val process = waitingProcess(id = "completed").apply {
            replaceRuntimeState(
                status = AgentProcessStatusCode.COMPLETED,
                history = emptyList(),
            )
        }

        val snapshot = snapshotFactory.snapshot(process, version = 1)

        assertEquals("completed", snapshot.processId)
        assertEquals(AgentProcessStatusCode.COMPLETED, snapshot.status)
        assertEquals(null, snapshot.pendingAwaitableId)
    }

    @Test
    fun `rejects process that is not a checkpoint boundary`() {
        val process = process(id = "not-checkpointable")

        assertThrows(IllegalArgumentException::class.java) {
            snapshotFactory.snapshot(process, version = 1)
        }
    }

    @Test
    fun `rejects waiting process without awaitable`() {
        val process = process(id = "malformed").apply {
            makeWaiting()
        }

        assertThrows(AgentProcessPersistenceException::class.java) {
            snapshotFactory.snapshot(process, version = 1)
        }
    }

    private fun waitingProcess(
        id: String = "p1",
        parentId: String? = null,
    ): SimpleAgentProcess =
        process(id = id, parentId = parentId).also {
            assertEquals(AgentProcessStatusCode.WAITING, it.run().status)
        }

    private fun process(
        id: String,
        parentId: String? = null,
    ): TestableAgentProcess {
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("Rod")
        return TestableAgentProcess(
            id = id,
            parentId = parentId,
            agent = DslWaitingAgent,
            processOptions = ProcessOptions(),
            blackboard = blackboard,
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        )
    }

    private class TestableAgentProcess(
        id: String,
        parentId: String?,
        agent: com.embabel.agent.core.Agent,
        processOptions: ProcessOptions,
        blackboard: InMemoryBlackboard,
        platformServices: com.embabel.agent.api.common.PlatformServices,
        plannerFactory: com.embabel.agent.spi.PlannerFactory,
    ) : SimpleAgentProcess(
        id = id,
        parentId = parentId,
        agent = agent,
        processOptions = processOptions,
        blackboard = blackboard,
        platformServices = platformServices,
        plannerFactory = plannerFactory,
    ) {

        fun makeWaiting() {
            setStatus(AgentProcessStatusCode.WAITING)
        }
    }
}
