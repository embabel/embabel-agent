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
package com.embabel.agent.sandbox

import com.embabel.agent.api.tool.ArtifactSink
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.spi.loop.*
import com.embabel.agent.spi.support.springai.SpringToolCallbackAdapter
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.mockk.mockk
import org.springframework.ai.tool.execution.ToolExecutionException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ScratchToolArtifactTest {
    private val file = ExecutionArtifact.File("report.csv", Path.of("report.csv"), "text/csv", 12)
    private val completed = ExecutionResult.Completed(0, "stdout", "stderr", 5.milliseconds, listOf(file))

    @Nested
    inner class Results {
        @Test
        fun `library names remain shell commands`() {
            for (command in listOf("numpy --help", "pandas --help")) {
                var captured: ExecutionRequest? = null
                tool(completed) { captured = it }.call("""{"command":"$command"}""")
                assertEquals(listOf("bash", "-s"), captured?.command)
                assertEquals(command, captured?.stdin)
            }
        }

        @Test
        fun `Python statements are still wrapped`() {
            var captured: ExecutionRequest? = null
            tool(completed) { captured = it }.call("""{"command":"import sys; print(sys.version)"}""")
            assertEquals(listOf("bash", "-s"), captured?.command)
            assertEquals("python3 -c 'import sys; print(sys.version)'", captured?.stdin)
        }

        @Test
        fun `timeout is an error explaining that side effects may already exist`() {
            val result = tool(ExecutionResult.TimedOut(duration = 5.milliseconds)).call("""{"command":"true"}""")
            assertIs<Tool.Result.Error>(result)
            assertContains(result.message, "timed out")
            assertContains(result.message, "Check existing files before retrying")
        }

        @Test
        fun `Spring adapter propagates timeout as a tool execution error`() {
            var executions = 0
            val scratch = tool(ExecutionResult.TimedOut(duration = 5.milliseconds)) { executions++ }
            val failure = assertFailsWith<ToolExecutionException> {
                SpringToolCallbackAdapter(scratch).call("""{"command":"true"}""")
            }
            assertContains(failure.cause!!.message!!, "timed out")
            assertEquals(1, executions)
        }

        @Test
        fun `native loop sends timeout feedback to the model without replaying the command`() {
            var executions = 0
            val scratch = tool(ExecutionResult.TimedOut(duration = 5.milliseconds)) { executions++ }
            val responses = ArrayDeque(listOf(
                LlmMessageResponse(AssistantMessageWithToolCalls("", listOf(
                    ToolCall("call_1", scratch.definition.name, """{"command":"true"}"""))), ""),
                LlmMessageResponse(AssistantMessage("Execution timed out; I will check the files."),
                    "Execution timed out; I will check the files."),
            ))
            val factory = ToolLoopFactory.create(ToolLoopConfiguration(), mockk<Asyncer>(), AutoCorrectionPolicy())
            val loop = factory.create(LlmMessageSender { _, _ -> responses.removeFirst() },
                EmbabelObjectMapperHolder.createDefault().get(), ToolInjectionStrategy.NONE, 3, null,
                emptyList(), emptyList(), emptyList(), ToolCallContext.EMPTY)
            val result = loop.execute(listOf(UserMessage("Generate a file")), listOf(scratch)) { it }
            val feedback = result.conversationHistory.filterIsInstance<ToolResultMessage>().single()
            assertContains(feedback.content, "Error: Command timed out")
            assertEquals(1, executions)
            assertEquals("Execution timed out; I will check the files.", result.result)
        }

        @Test
        fun `multiline shell commands containing Python remain shell commands`() {
            var captured: ExecutionRequest? = null
            tool(completed) { captured = it }.call("""{"command":"cat << 'EOF' > file.py\nimport reportlab\nEOF\npython3 file.py"}""")
            assertEquals(listOf("bash", "-s"), captured?.command)
            assertEquals(
                "cat << 'EOF' > file.py\nimport reportlab\nEOF\npython3 file.py",
                captured?.stdin,
            )
        }

        @Test
        fun `explicit input remains command stdin`() {
            var captured: ExecutionRequest? = null
            tool(completed) { captured = it }.call("""{"command":"cat","stdin":"input text"}""")
            assertEquals(listOf("bash", "-c", "cat"), captured?.command)
            assertEquals("input text", captured?.stdin)
        }

        @Test
        fun `forwards artifacts through the existing sink with command output intact`() {
            val received = mutableListOf<Any>()
            val tool = Tool.sinkArtifacts(tool(completed), ExecutionArtifact.File::class.java, ArtifactSink { received.add(it) })
            val result = tool.call("""{"command":"echo hello"}""", ToolCallContext.EMPTY)
            assertIs<Tool.Result.WithArtifact>(result)
            assertEquals("stdout\nstderr", result.content)
            assertEquals(listOf(file), result.artifact)
            assertEquals(listOf<Any>(file), received)
        }

        @Test
        fun `nonzero exit keeps artifacts and exit code`() {
            val result = tool(completed.copy(exitCode = 7)).call("""{"command":"exit 7"}""")
            assertIs<Tool.Result.WithArtifact>(result)
            assertEquals("Exit code 7:\nstdout\nstderr", result.content)
            assertEquals(listOf(file), result.artifact)
        }

        @Test
        fun `text-only completed results retain existing format`() {
            val result = tool(completed.copy(artifacts = emptyList(), stdout = "", stderr = ""))
                .call("""{"command":"true"}""")
            assertEquals(Tool.Result.text("(no output)"), result)
        }

        @Test
        fun `denial startup failure and timeout remain non-artifact results`() {
            for (execution in listOf(
                ExecutionResult.Denied("not allowed"),
                ExecutionResult.Failed("cannot start"),
                ExecutionResult.TimedOut(duration = 5.milliseconds),
            )) {
                assertFalse(tool(execution).call("""{"command":"true"}""") is Tool.Result.WithArtifact)
            }
        }
    }

    private fun tool(result: ExecutionResult, onExecute: (ExecutionRequest) -> Unit = {}): ScratchTool {
        val session = object : SandboxSession {
            override val id = "test-session"
            override val label = "test"
            override val owner: String? = null
            override val createdAt = Instant.now()
            override val lastActiveAt = createdAt
            override val state = SandboxSession.SessionState.ACTIVE
            override val config = SandboxConfig()
            override fun execute(request: ExecutionRequest): ExecutionResult {
                onExecute(request)
                return result
            }
            override fun copyFrom(containerPath: String, hostPath: Path) = error("Unexpected file copy")
            override fun copyTo(hostPath: Path, containerPath: String) = error("Unexpected file copy")
            override fun pause() = Unit
            override fun resume() = Unit
            override fun close() = Unit
        }
        val manager = object : SandboxSessionManager {
            override fun create(label: String, config: SandboxConfig, owner: String?, ttl: Duration,
                metadata: Map<String, String>) = session
            override fun get(id: String) = session.takeIf { it.id == id }
            override fun list(owner: String?) = listOf(session)
            override fun evictExpired() = Unit
            override fun closeAll() = Unit
        }
        return ScratchTool(manager)
    }
}
