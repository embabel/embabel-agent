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
package com.embabel.agent.core

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertNull

class EarlyTerminationPolicyTest {

    @Test
    fun `test MaxActions`() {
        val policy = EarlyTerminationPolicy.firstOf(
            EarlyTerminationPolicy.maxActions(2),
        )
        val agentProcess: AgentProcess = mockk()
        every { agentProcess.history.size } returns 3
        val termination = policy.shouldTerminate(agentProcess)
        assert(termination != null)
    }

    @Test
    fun `test first of MaxAction`() {
        val policy = EarlyTerminationPolicy.firstOf(
            EarlyTerminationPolicy.maxActions(10),
            EarlyTerminationPolicy.maxActions(2),
        )
        val agentProcess: AgentProcess = mockk()
        every { agentProcess.history.size } returns 3
        val termination = policy.shouldTerminate(agentProcess)
        assert(termination != null)
    }

    @Test
    fun `test first of MaxAction and budget terminates`() {
        val policy = EarlyTerminationPolicy.firstOf(
            EarlyTerminationPolicy.maxActions(10),
            EarlyTerminationPolicy.hardBudgetLimit(0.20),
        )
        val agentProcess: AgentProcess = mockk()
        every { agentProcess.history.size } returns 3
        every { agentProcess.cost() } returns 0.25
        val termination = policy.shouldTerminate(agentProcess)
        assert(termination != null)
    }

    @Test
    fun `test first of MaxAction and budget does not terminate`() {
        val policy = EarlyTerminationPolicy.firstOf(
            EarlyTerminationPolicy.maxActions(10),
            EarlyTerminationPolicy.hardBudgetLimit(1.20),
        )
        val agentProcess: AgentProcess = mockk()
        every { agentProcess.history.size } returns 3
        every { agentProcess.cost() } returns 0.25
        val termination = policy.shouldTerminate(agentProcess)
        assertNull(termination)
    }

}
