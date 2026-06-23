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

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.ProcessControl
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The termination event delivered from [AbstractAgentProcess.identifyEarlyTermination] must observe
 * a process whose status is already `TERMINATED`, consistent with every other lifecycle transition
 * (COMPLETED, FAILED, WAITING, PAUSED, STUCK, KILLED) where the status is set before the event is
 * published. A listener reacting to the event must never see a stale, contradictory status.
 */
class AbstractAgentProcessTerminationStatusOrderingTest {

    /** Captures the process status as observed by a listener at event-delivery time. */
    private class StatusCapturingListener : AgenticEventListener {
        var statusAtDelivery: AgentProcessStatusCode? = null
        override fun onProcessEvent(event: AgentProcessEvent) {
            if (event is EarlyTermination) {
                statusAtDelivery = event.agentProcess.status
            }
        }
    }

    /** Exposes the protected hooks needed to drive the termination path directly. */
    private class TestProcess(
        processOptions: ProcessOptions,
        platformServices: PlatformServices,
    ) : ConcurrentAgentProcess(
        id = "term-status-ordering",
        parentId = null,
        agent = SimpleTestAgent,
        processOptions = processOptions,
        blackboard = InMemoryBlackboard(),
        platformServices = platformServices,
        plannerFactory = DefaultPlannerFactory,
    ) {
        fun forceStatus(status: AgentProcessStatusCode) = setStatus(status)
        fun runIdentifyEarlyTermination(): EarlyTermination? = identifyEarlyTermination()
    }

    @Test
    fun `signal-driven termination event observes TERMINATED status`() {
        val listener = StatusCapturingListener()
        val process = TestProcess(ProcessOptions(), dummyPlatformServices(eventListener = listener))
        process.forceStatus(AgentProcessStatusCode.RUNNING)

        // AGENT-scope signal drives the signal-termination branch.
        process.terminateAgent("stop now")
        process.runIdentifyEarlyTermination()

        assertEquals(
            AgentProcessStatusCode.TERMINATED,
            listener.statusAtDelivery,
            "Listener must observe TERMINATED status when the signal-termination event is delivered",
        )
    }

    @Test
    fun `policy-driven termination event observes TERMINATED status`() {
        val listener = StatusCapturingListener()
        val alwaysTerminate = object : EarlyTerminationPolicy {
            override fun shouldTerminate(agentProcess: AgentProcess) =
                EarlyTermination(agentProcess, error = false, reason = "policy", policy = this)
        }
        val processOptions = ProcessOptions(
            processControl = ProcessControl(earlyTerminationPolicy = alwaysTerminate),
        )
        val process = TestProcess(processOptions, dummyPlatformServices(eventListener = listener))
        process.forceStatus(AgentProcessStatusCode.RUNNING)

        process.runIdentifyEarlyTermination()

        assertEquals(
            AgentProcessStatusCode.TERMINATED,
            listener.statusAtDelivery,
            "Listener must observe TERMINATED status when the policy-termination event is delivered",
        )
    }
}
