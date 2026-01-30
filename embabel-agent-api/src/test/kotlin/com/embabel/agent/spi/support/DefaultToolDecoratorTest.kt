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
package com.embabel.agent.spi.support

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentProcessRunning
import com.embabel.common.ai.model.LlmOptions
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val Tool.Result.content: String
    get() = when (this) {
        is Tool.Result.Text -> content
        is Tool.Result.WithArtifact -> content
        is Tool.Result.Error -> message
    }

object RuntimeExceptionTool {

    @LlmTool
    fun toolThatThrowsRuntimeException(input: String): String {
        throw RuntimeException("This tool always fails")
    }
}

object ReplanningAnnotatedTool {

    @LlmTool(description = "Routes user to appropriate handler based on intent")
    fun routeUser(message: String): String {
        // Classify intent and request replan
        throw ReplanRequestedException(
            reason = "Classified as support request",
            blackboardUpdater = { bb ->
                bb["intent"] = "support"
                bb["confidence"] = 0.95
                bb["originalMessage"] = message
            }
        )
    }
}

object SimpleReplanTool {

    @LlmTool(description = "Always replans with minimal context")
    fun triggerReplan(input: String): String {
        throw ReplanRequestedException(
            reason = "Replan triggered by tool",
            blackboardUpdater = { bb -> bb["triggered"] = true }
        )
    }
}


class DefaultToolDecoratorTest {

    @Test
    fun `test handle runtime exception from tool`() {
        val toolDecorator = DefaultToolDecorator()
        val badTool = safelyGetToolsFrom(ToolObject(RuntimeExceptionTool)).single()
        val decorated = toolDecorator.decorate(
            tool = badTool,
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null, llmOptions = LlmOptions(),
        )
        val result = decorated.call(
            """
            { "input": "anything at all" }
        """.trimIndent()
        )
        assertTrue(
            result.content.contains("This tool always fails"),
            "Expected result to contain the exception message: Got '${result.content}'"
        )
    }

    @Test
    fun `test AgentContext is bound`() {
        val toolDecorator = DefaultToolDecorator()

        class NeedsAgentProcess {
            @LlmTool
            fun toolThatNeedsAgentProcess(input: String): String {
                assertNotNull(AgentProcess.get(), "Agent process must have been bound")
                return "AgentProcess is bound"
            }
        }

        val tool = safelyGetToolsFrom(ToolObject(NeedsAgentProcess())).single()
        val decorated = toolDecorator.decorate(
            tool = tool,
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null, llmOptions = LlmOptions(),
        )
        val result = decorated.call(
            """
            { "input": "anything at all" }
        """.trimIndent()
        )
        assertTrue(result.content.contains("AgentProcess is bound"))
    }

    @Test
    fun `ReplanRequestedException propagates through decorator chain`() {
        val toolDecorator = DefaultToolDecorator()
        val replanTool = safelyGetToolsFrom(ToolObject(ReplanningAnnotatedTool)).single()
        val decorated = toolDecorator.decorate(
            tool = replanTool,
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null,
            llmOptions = LlmOptions(),
        )

        val exception = assertThrows<ReplanRequestedException> {
            decorated.call("""{ "message": "I need help with billing" }""")
        }

        assertEquals("Classified as support request", exception.reason)
        val mockBlackboard = mockk<Blackboard>(relaxed = true)
        exception.blackboardUpdater.accept(mockBlackboard)
        verify { mockBlackboard["intent"] = "support" }
        verify { mockBlackboard["confidence"] = 0.95 }
        verify { mockBlackboard["originalMessage"] = "I need help with billing" }
    }

    @Test
    fun `ReplanRequestedException is not suppressed by ExceptionSuppressingTool in decorator chain`() {
        val toolDecorator = DefaultToolDecorator()
        val replanTool = safelyGetToolsFrom(ToolObject(SimpleReplanTool)).single()
        val decorated = toolDecorator.decorate(
            tool = replanTool,
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null,
            llmOptions = LlmOptions(),
        )

        // This should throw, not return an error result like RuntimeException would
        val exception = assertThrows<ReplanRequestedException> {
            decorated.call("""{ "input": "trigger" }""")
        }

        assertEquals("Replan triggered by tool", exception.reason)
        val mockBlackboard = mockk<Blackboard>(relaxed = true)
        exception.blackboardUpdater.accept(mockBlackboard)
        verify { mockBlackboard["triggered"] = true }
    }

    @Test
    fun `ReplanRequestedException preserves blackboard updater through decorator chain`() {
        val toolDecorator = DefaultToolDecorator()

        class ComplexReplanTool {
            @LlmTool(description = "Tool with complex blackboard updates")
            fun complexReplan(query: String): String {
                throw ReplanRequestedException(
                    reason = "Complex replan needed",
                    blackboardUpdater = { bb ->
                        bb["stringValue"] = "test"
                        bb["intValue"] = 42
                        bb["doubleValue"] = 3.14
                        bb["boolValue"] = true
                        bb["listValue"] = listOf("a", "b", "c")
                        bb["mapValue"] = mapOf("nested" to "value")
                    }
                )
            }
        }

        val tool = safelyGetToolsFrom(ToolObject(ComplexReplanTool())).single()
        val decorated = toolDecorator.decorate(
            tool = tool,
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null,
            llmOptions = LlmOptions(),
        )

        val exception = assertThrows<ReplanRequestedException> {
            decorated.call("""{ "query": "test query" }""")
        }

        val mockBlackboard = mockk<Blackboard>(relaxed = true)
        exception.blackboardUpdater.accept(mockBlackboard)
        verify { mockBlackboard["stringValue"] = "test" }
        verify { mockBlackboard["intValue"] = 42 }
        verify { mockBlackboard["doubleValue"] = 3.14 }
        verify { mockBlackboard["boolValue"] = true }
        verify { mockBlackboard["listValue"] = listOf("a", "b", "c") }
        verify { mockBlackboard["mapValue"] = mapOf("nested" to "value") }
    }

}
