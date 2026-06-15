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
package com.embabel.agent.core.support

import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessCreationEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.ConfirmationResponse
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lifecycle contract backing the `embabel.agent.active` metric.
 *
 * The metrics listener counts a process as live on [AgentProcessCreationEvent] (+1) and only
 * removes it on terminal events (-1). That accounting is only correct if a process that parks in
 * WAITING and later resumes does so on the **same instance without re-emitting**
 * [AgentProcessCreationEvent] — the normal human-in-the-loop case. This test pins that contract at
 * the platform level so a future change that re-emitted creation on resume (which would silently
 * double-count the gauge) fails here rather than in production dashboards.
 */
class AgentProcessResumeEventContractTest {

    @Test
    fun `resuming a WAITING process does not re-emit AgentProcessCreationEvent`() {
        val ese = EventSavingAgenticEventListener()
        val platform = IntegrationTestUtils.dummyAgentPlatform(listener = ese)

        // Creation emits AgentProcessCreationEvent exactly once.
        val process = platform.createAgentProcess(
            DslWaitingAgent,
            ProcessOptions(),
            mapOf(IoBinding.DEFAULT_BINDING to UserInput("Rod")),
        )

        // First turn parks the process in WAITING (confirmation requested).
        assertEquals(AgentProcessStatusCode.WAITING, process.run().status)

        // Answer the confirmation and resume on the SAME instance — the human-in-the-loop case.
        val awaitable = process.lastResult() as ConfirmationRequest<*>
        awaitable.onResponse(ConfirmationResponse(awaitableId = awaitable.id, accepted = true), process)
        assertEquals(AgentProcessStatusCode.COMPLETED, process.run().status)

        val creationEvents = ese.processEvents.filterIsInstance<AgentProcessCreationEvent>()
        assertEquals(
            1, creationEvents.size,
            "AgentProcessCreationEvent must be emitted exactly once (at creation), never on resume",
        )
        assertTrue(
            ese.processEvents.any { it is AgentProcessWaitingEvent },
            "Process should have parked in WAITING (emitting AgentProcessWaitingEvent)",
        )
        assertTrue(
            ese.processEvents.any { it is AgentProcessCompletedEvent },
            "Resume should have driven the process to completion (AgentProcessCompletedEvent)",
        )
    }
}
