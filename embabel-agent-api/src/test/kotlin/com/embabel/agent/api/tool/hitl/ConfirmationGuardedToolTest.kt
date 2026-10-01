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
package com.embabel.agent.api.tool.hitl

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.TypedTool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.hitl.AwaitableResponseException
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.core.hitl.ConfirmationResponse
import com.embabel.agent.core.hitl.ResponseImpact
import com.embabel.agent.core.support.InMemoryBlackboard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.function.Function

class ConfirmationGuardedToolTest {

    data class TaskRequest(val title: String, val priority: Int? = null)
    data class TaskResponse(val id: String, val title: String)

    private lateinit var blackboard: Blackboard

    @BeforeEach
    fun setUp() {
        blackboard = InMemoryBlackboard()
        AgentProcess.set(processOver(blackboard))
    }

    @AfterEach
    fun tearDown() {
        AgentProcess.remove()
    }

    private fun processOver(bb: Blackboard): AgentProcess {
        val process = mockk<AgentProcess>(relaxed = true)
        every { process.blackboard } returns bb
        return process
    }

    /** Delegate that records every raw input it was called with. */
    private class RecordingTool(name: String = "create_task") {
        val calls = mutableListOf<String>()
        val tool: Tool = Tool.create(name, "Create a task on the board") { input ->
            calls += input
            Tool.Result.text("created:$input")
        }
    }

    private fun text(result: Tool.Result): String =
        (result as? Tool.Result.Text)?.content ?: fail("Expected Text result, got $result")

    private fun proposals(toolName: String = "create_task") =
        blackboard.objectsOfType(ToolCallProposal::class.java).filter { it.toolName == toolName }

    private fun verdicts() = blackboard.objectsOfType(ToolCallVerdict::class.java)

    private fun outcomes() = blackboard.objectsOfType(ToolCallOutcome::class.java)

    @Nested
    inner class AskViaLlm {

        private val delegate = RecordingTool()
        private val guard = delegate.tool.withLlmConfirmation("Create this task?")

        @Test
        fun `first call records a proposal and instructs the LLM without calling the delegate`() {
            val result = text(guard.call("""{"title":"Write spec","priority":1}"""))

            assertTrue(delegate.calls.isEmpty(), "delegate must not run before confirmation")
            assertEquals(1, proposals().size)
            val proposal = proposals().single()
            assertEquals("create_task", proposal.toolName)
            assertEquals("Create this task?", proposal.message)
            assertEquals("""{"priority":1,"title":"Write spec"}""", proposal.arguments)
            assertTrue(result.contains("Create this task?"), result)
            assertTrue(result.contains("confirm_create_task"), result)
            assertTrue(result.contains("Write spec"), result)
        }

        @Test
        fun `identical arguments in a different shape do not create a second proposal`() {
            guard.call("""{"title":"Write spec","priority":1}""")
            val result = text(guard.call("""{ "priority": 1.0,   "title": "Write spec" }"""))

            assertEquals(1, proposals().size)
            assertTrue(result.contains("confirm_create_task"), result)
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `different arguments supersede the open proposal`() {
            guard.call("""{"title":"Write spec"}""")
            guard.call("""{"title":"Write tests"}""")

            assertEquals(2, proposals().size)
            assertEquals("""{"title":"Write tests"}""", proposals().last().arguments)
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `accepted verdict executes the stored arguments exactly once`() {
            guard.call("""{"title":"Write spec","priority":1}""")

            val result = text(guard.verdictTool.call("""{"accepted":true,"note":"user said go ahead"}"""))

            assertEquals(listOf("""{"priority":1,"title":"Write spec"}"""), delegate.calls)
            assertEquals("""created:{"priority":1,"title":"Write spec"}""", result)
            val verdict = verdicts().single()
            assertTrue(verdict.accepted)
            assertEquals(VerdictSource.LLM, verdict.source)
            assertEquals("user said go ahead", verdict.note)
            assertEquals(proposals().single().id, verdict.proposalId)
            assertTrue(outcomes().single().executed)

            // The confirmation is consumed: the same call needs a fresh confirmation
            val again = text(guard.call("""{"title":"Write spec","priority":1}"""))
            assertEquals(1, delegate.calls.size)
            assertEquals(2, proposals().size)
            assertTrue(again.contains("confirm_create_task"), again)
        }

        @Test
        fun `declined verdict cancels without calling the delegate`() {
            guard.call("""{"title":"Write spec"}""")

            val result = text(guard.verdictTool.call("""{"accepted":false}"""))

            assertTrue(delegate.calls.isEmpty())
            assertTrue(result.contains("declined", ignoreCase = true), result)
            assertFalse(verdicts().single().accepted)
            assertFalse(outcomes().single().executed)
        }

        @Test
        fun `verdict tool with nothing pending returns an error`() {
            val result = guard.verdictTool.call("""{"accepted":true}""")

            assertInstanceOf(Tool.Result.Error::class.java, result)
            assertTrue(delegate.calls.isEmpty())
            assertTrue(verdicts().isEmpty())
        }

        @Test
        fun `verdict tool without accepted flag returns an error and records nothing`() {
            guard.call("""{"title":"Write spec"}""")

            val result = guard.verdictTool.call("""{"note":"hmm"}""")

            assertInstanceOf(Tool.Result.Error::class.java, result)
            assertTrue(verdicts().isEmpty())
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `guarded tools are scoped by tool name`() {
            val other = RecordingTool("send_email")
            val otherGuard = other.tool.withLlmConfirmation("Send it?")

            guard.call("""{"title":"Write spec"}""")
            otherGuard.call("""{"to":"a@b.c"}""")
            otherGuard.verdictTool.call("""{"accepted":true}""")

            assertEquals(listOf("""{"to":"a@b.c"}"""), other.calls)
            assertTrue(delegate.calls.isEmpty(), "create_task must still be pending")
            assertTrue(text(guard.call("""{"title":"Write spec"}""")).contains("confirm_create_task"))
            assertEquals(1, proposals("create_task").size)
        }

        @Test
        fun `throws when no AgentProcess available`() {
            AgentProcess.remove()

            val exception = assertThrows<IllegalStateException> { guard.call("{}") }
            assertTrue(exception.message?.contains("No AgentProcess") == true)
            assertThrows<IllegalStateException> { guard.verdictTool.call("""{"accepted":true}""") }
        }
    }

    @Nested
    inner class PauseProcess {

        private val delegate = RecordingTool()
        private val guard = delegate.tool.withLlmConfirmation(
            message = "Create this task?",
            mode = ConfirmationMode.PAUSE_PROCESS,
        )

        private fun raise(input: String): ToolCallConfirmationRequest {
            val exception = assertThrows<AwaitableResponseException> { guard.call(input) }
            return assertInstanceOf(ToolCallConfirmationRequest::class.java, exception.awaitable)
        }

        @Test
        fun `first call pauses with a ToolCallConfirmationRequest carrying the proposal`() {
            val request = raise("""{"title":"Write spec"}""")

            assertInstanceOf(ConfirmationRequest::class.java, request)
            assertEquals("Create this task?", request.message)
            assertEquals(proposals().single(), request.payload)
            assertTrue(delegate.calls.isEmpty())
        }

        @Test
        fun `UI acceptance via onResponse makes the next identical call execute`() {
            val request = raise("""{"title":"Write spec"}""")

            val impact = request.onResponse(
                ConfirmationResponse(awaitableId = request.id, accepted = true),
                AgentProcess.get()!!,
            )
            assertEquals(ResponseImpact.UPDATED, impact)
            assertEquals(VerdictSource.UI, verdicts().single().source)
            assertEquals(1, proposals().size, "onResponse must not re-promote the proposal")

            val result = text(guard.call("""{ "title" : "Write spec" }"""))

            assertEquals(listOf("""{"title":"Write spec"}"""), delegate.calls)
            assertEquals("""created:{"title":"Write spec"}""", result)
            assertTrue(outcomes().single().executed)
        }

        @Test
        fun `UI rejection via onResponse makes the next call report the decline`() {
            val request = raise("""{"title":"Write spec"}""")
            request.onResponse(
                ConfirmationResponse(awaitableId = request.id, accepted = false),
                AgentProcess.get()!!,
            )

            val result = text(guard.call("""{"title":"Write spec"}"""))

            assertTrue(delegate.calls.isEmpty())
            assertTrue(result.contains("declined", ignoreCase = true), result)
            assertFalse(outcomes().single().executed)
        }

        @Test
        fun `changed arguments after acceptance pause again instead of executing`() {
            val request = raise("""{"title":"Write spec"}""")
            request.onResponse(
                ConfirmationResponse(awaitableId = request.id, accepted = true),
                AgentProcess.get()!!,
            )

            raise("""{"title":"Delete everything"}""")

            assertTrue(delegate.calls.isEmpty())
            assertEquals(2, proposals().size)
        }

        @Test
        fun `LLM verdict tool also resolves a paused proposal`() {
            raise("""{"title":"Write spec"}""")

            val result = text(guard.verdictTool.call("""{"accepted":true}"""))

            assertEquals(listOf("""{"title":"Write spec"}"""), delegate.calls)
            assertEquals("""created:{"title":"Write spec"}""", result)
        }
    }

    @Nested
    inner class Definitions {

        @Test
        fun `guard keeps the delegate name and input schema and appends the confirmation note`() {
            val delegate = TypedTool(
                name = "create_task",
                description = "Create a task",
                inputType = TaskRequest::class.java,
                outputType = TaskResponse::class.java,
                function = Function { TaskResponse("1", it.title) },
            )

            val guard = delegate.withLlmConfirmation("Create it?")

            assertEquals("create_task", guard.definition.name)
            assertEquals(
                delegate.definition.inputSchema.toJsonSchema(),
                guard.definition.inputSchema.toJsonSchema(),
                "the guard must not degrade the delegate's schema",
            )
            assertTrue(guard.definition.description.startsWith("Create a task"))
            assertTrue(guard.definition.description.contains("confirmation"), guard.definition.description)
            assertSame(delegate, guard.delegate)
            assertEquals(delegate.metadata, guard.metadata)
        }

        @Test
        fun `verdict tool exposes accepted and an optional note`() {
            val guard = RecordingTool().tool.withLlmConfirmation("Create it?")

            val definition = guard.verdictTool.definition
            assertEquals("confirm_create_task", definition.name)
            val byName = definition.inputSchema.parameters.associateBy { it.name }
            assertEquals(setOf("accepted", "note"), byName.keys)
            assertEquals(Tool.ParameterType.BOOLEAN, byName.getValue("accepted").type)
            assertTrue(byName.getValue("accepted").required)
            assertEquals(Tool.ParameterType.STRING, byName.getValue("note").type)
            assertFalse(byName.getValue("note").required)
        }

        @Test
        fun `tools returns the guard and its verdict tool`() {
            val guard = RecordingTool().tool.withLlmConfirmation("Create it?")

            assertEquals(listOf(guard, guard.verdictTool), guard.tools())
        }

        @Test
        fun `message provider receives the raw input`() {
            var seen: String? = null
            val guard = RecordingTool().tool.withLlmConfirmation { input ->
                seen = input
                "Confirm $input?"
            }

            guard.call("""{"title":"x"}""")

            assertEquals("""{"title":"x"}""", seen)
            assertEquals("""Confirm {"title":"x"}?""", proposals().single().message)
        }

        @Test
        fun `custom confirmation note and verdict prefix are honoured`() {
            val options = ConfirmationGuardOptions(
                confirmationNote = "Ask before doing this.",
                verdictToolPrefix = "approve_",
            )

            val guard = RecordingTool().tool.withLlmConfirmation("Create it?", options = options)

            assertEquals("Create a task on the board Ask before doing this.", guard.definition.description)
            assertEquals("approve_create_task", guard.verdictTool.definition.name)
            assertTrue(text(guard.call("""{"title":"x"}""")).contains("approve_create_task"))
        }

        @Test
        fun `blank confirmation note leaves the delegate description unchanged`() {
            val delegate = RecordingTool().tool
            val options = ConfirmationGuardOptions.DEFAULT.withConfirmationNote("")

            val guard = delegate.withLlmConfirmation("Create it?", options = options)

            assertEquals(delegate.definition.description, guard.definition.description)
        }

        @Test
        fun `blank verdict prefix is rejected`() {
            assertThrows<IllegalArgumentException> { ConfirmationGuardOptions(verdictToolPrefix = " ") }
            assertThrows<IllegalArgumentException> { ConfirmationGuardOptions.DEFAULT.withVerdictToolPrefix("") }
        }

        @Test
        fun `options apply through the message provider overload`() {
            val guard = RecordingTool().tool.withLlmConfirmation(
                options = ConfirmationGuardOptions.DEFAULT.withVerdictToolPrefix("ok_"),
            ) { "Confirm $it" }

            assertEquals("ok_create_task", guard.verdictTool.definition.name)
        }

        @Test
        fun `of factory mirrors the extension functions`() {
            val tool = RecordingTool().tool

            val byMessage = ConfirmationGuardedTool.of(tool, "Create it?")
            val byProvider = ConfirmationGuardedTool.of(tool, Function { "Confirm $it" }, ConfirmationMode.PAUSE_PROCESS)

            assertEquals(ConfirmationMode.ASK_VIA_LLM, byMessage.mode)
            assertEquals(ConfirmationMode.PAUSE_PROCESS, byProvider.mode)
            assertSame(tool, byProvider.delegate)
        }
    }
}
