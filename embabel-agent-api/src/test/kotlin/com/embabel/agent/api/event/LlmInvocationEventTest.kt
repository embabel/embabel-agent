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
package com.embabel.agent.api.event

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.Usage
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

class LlmInvocationEventTest {

    private val metadata = LlmMetadata(
        name = "gpt-test",
        provider = "test-provider",
        pricingModel = PricingModel.usdPer1MTokens(1.0, 2.0),
    )

    private fun makeInvocation(
        promptTokens: Int = 100,
        completionTokens: Int = 50,
    ) = LlmInvocation(
        llmMetadata = metadata,
        usage = Usage(promptTokens, completionTokens, null),
        agentName = "test-agent",
        timestamp = Instant.now(),
        runningTime = Duration.ZERO,
    )

    @Test
    fun `exposes invocation, interactionId and agentProcess`() {
        val agentProcess = mock(AgentProcess::class.java)
        `when`(agentProcess.id).thenReturn("process-1")
        val invocation = makeInvocation()

        val event = LlmInvocationEvent(agentProcess, invocation, "interaction-42")

        assertSame(agentProcess, event.agentProcess)
        assertSame(invocation, event.invocation)
        assertEquals("interaction-42", event.interactionId)
        assertEquals("process-1", event.processId)
    }

    @Test
    fun `toString surfaces model, interactionId, usage and cost for diagnostics`() {
        val agentProcess = mock(AgentProcess::class.java)
        `when`(agentProcess.id).thenReturn("p")
        val invocation = makeInvocation(promptTokens = 1_000_000, completionTokens = 0)

        val s = LlmInvocationEvent(agentProcess, invocation, "i-1").toString()

        assertTrue(s.contains("gpt-test"), "expected model name in toString: $s")
        assertTrue(s.contains("i-1"), "expected interactionId in toString: $s")
        assertTrue(s.contains("usage="), "expected usage in toString: $s")
        assertTrue(s.contains("cost="), "expected cost in toString: $s")
    }
}
