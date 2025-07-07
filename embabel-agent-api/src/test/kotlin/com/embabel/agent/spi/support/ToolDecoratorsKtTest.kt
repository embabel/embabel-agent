/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi.support

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.ToolCallRequestEvent
import com.embabel.agent.event.ToolCallResponseEvent
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.annotation.Tool

class SimpleTools {

    @Tool
    fun testTool(
    ): String {
        return "foobar"
    }
}

class ToolDecoratorsKtTest {

    @Test
    fun `preserves metadata`() {
        val tool = ToolCallbacks.from(SimpleTools()).single()
        val agentProcess = mockk<AgentProcess>()
        val llm = LlmOptions()
        val decorated = tool.withEventPublication(agentProcess, null, llm)
        assertEquals(tool.toolDefinition.name(), decorated.toolDefinition.name())
        assertEquals(tool.toolDefinition.inputSchema(), decorated.toolDefinition.inputSchema())
    }

    @Test
    fun `has same return`() {
        val tool = ToolCallbacks.from(SimpleTools()).single()
        val mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.eventListener } returns EventSavingAgenticEventListener()
        val agentProcess = mockk<AgentProcess>()
        every { agentProcess.id } returns "test-process-id"
        every { agentProcess.processContext.platformServices } returns mockPlatformServices
        every { agentProcess.processContext.onProcessEvent(any()) } answers {
            // Do nothing
        }
        every { agentProcess.processContext.platformServices } returns mockPlatformServices
        every { mockPlatformServices.operationScheduler } returns OperationScheduler.PRONTO
        val llm = LlmOptions()
        val decorated = tool.withEventPublication(agentProcess, null, llm)
        val rawResult = tool.call("{}")
        val decoratedRest = decorated.call("{}")
        assertEquals(rawResult, decoratedRest)
    }

    @Test
    fun `emits events`() {
        val ese = EventSavingAgenticEventListener()
        val tool = ToolCallbacks.from(SimpleTools()).single()
        val mockPlatformServices = mockk<PlatformServices>()
        every { mockPlatformServices.eventListener } returns ese
        val agentProcess = mockk<AgentProcess>()
        every { agentProcess.processContext.onProcessEvent(any()) } answers {
            ese.onProcessEvent(firstArg())
        }
        every { agentProcess.id } returns "test-process-id"
        every { agentProcess.processContext.platformServices } returns mockPlatformServices
        every { mockPlatformServices.operationScheduler } returns OperationScheduler.PRONTO
        val llm = LlmOptions()
        val decorated = tool.withEventPublication(agentProcess, null, llm)
        decorated.call("{}")
        assertEquals(2, ese.processEvents.size)
        assertEquals(0, ese.platformEvents.size)
        val fce = ese.processEvents.filterIsInstance<ToolCallRequestEvent>().single()
        val fre = ese.processEvents.filterIsInstance<ToolCallResponseEvent>().single()
        assertEquals(decorated.toolDefinition.name(), fce.tool)
        assertEquals(decorated.toolDefinition.name(), fre.request.tool, decorated.toolDefinition.name())
        assertEquals(llm, fce.llmOptions)
        assertEquals(llm, fre.request.llmOptions)
    }

}
