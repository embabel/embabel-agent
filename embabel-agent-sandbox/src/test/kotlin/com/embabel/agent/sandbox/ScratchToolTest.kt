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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.sandbox.docker.DockerExecutor
import com.embabel.agent.sandbox.docker.DockerSandboxSessionManager
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ScratchToolTest {

    private var scratch: ScratchTool? = null
    private var sessionManager: DockerSandboxSessionManager? = null

    @AfterEach
    fun cleanup() {
        scratch?.close()
        sessionManager?.closeAll()
    }

    private fun resultText(result: Tool.Result): String {
        assertTrue(result is Tool.Result.Text, "Expected Text result, got: $result")
        return (result as Tool.Result.Text).content
    }

    private fun assumeSandboxAvailable() {
        assumeTrue(DockerExecutor.isDockerAvailable(), "Docker not available")
        assumeTrue(
            DockerExecutor.imageExists(ScratchTool.DEFAULT_IMAGE),
            "Sandbox image ${ScratchTool.DEFAULT_IMAGE} not available"
        )
    }

    private fun newScratch(): ScratchTool {
        val mgr = DockerSandboxSessionManager().also { sessionManager = it }
        return ScratchTool(sessionManager = mgr).also { scratch = it }
    }

    @Test
    fun `constructors are callable from Java`() {
        // A constructor with a kotlin.time.Duration parameter compiles to a private
        // constructor plus synthetic bridges, which javac cannot call. Java needs real
        // public constructors, and the framework convention is java.time.Duration.
        val javaVisible = ScratchTool::class.java.constructors.filter { !it.isSynthetic }

        assertTrue(
            javaVisible.any { it.parameterTypes.contentEquals(arrayOf(SandboxSessionManager::class.java)) },
            "Expected a public ScratchTool(SandboxSessionManager) constructor, found: ${javaVisible.map { it.parameterTypes.toList() }}",
        )
        assertTrue(
            javaVisible.any { it.parameterTypes.contains(java.time.Duration::class.java) },
            "Expected a public constructor taking java.time.Duration",
        )
        assertTrue(
            javaVisible.none { it.parameterTypes.contains(Long::class.javaPrimitiveType) },
            "No public constructor should expose kotlin.time.Duration as a raw long",
        )
    }

    @Test
    fun `kotlin duration constructor is kept for source compatibility`() {
        val mgr = DockerSandboxSessionManager().also { sessionManager = it }
        val tool = ScratchTool(sessionManager = mgr, ttl = 2.hours, timeout = 30.seconds).also { scratch = it }
        assertEquals(ScratchTool.DEFAULT_NAME, tool.definition.name)
        tool.close() // no session was created, so nothing to destroy
    }

    @Test
    fun `basic command execution`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "echo hello world"}"""))
        assertTrue(text.contains("hello world"), "Should contain output: $text")
    }

    @Test
    fun `multiline heredoc reaches Docker intact`() {
        assumeSandboxAvailable()

        val command = """
            cat > /tmp/scratch-heredoc.py <<'EOF'
            print('heredoc intact')
            EOF
            python3 /tmp/scratch-heredoc.py
        """.trimIndent()
        val input = EmbabelObjectMapperHolder.createDefault().get()
            .writeValueAsString(mapOf("command" to command))
        val text = resultText(newScratch().call(input))
        assertTrue(text.contains("heredoc intact"), "Multiline script should execute: $text")
    }

    @Test
    fun `state persists between calls`() {
        assumeSandboxAvailable()

        val s = newScratch()
        s.call("""{"command": "echo 'test content' > /tmp/myfile.txt"}""")
        val text = resultText(s.call("""{"command": "cat /tmp/myfile.txt"}"""))
        assertTrue(text.contains("test content"), "File content should persist: $text")
    }

    @Test
    fun `reports non-zero exit code`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "exit 42"}"""))
        assertTrue(text.contains("Exit code 42"), "Should report exit code: $text")
    }

    @Test
    fun `python execution`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "python3 -c 'print(2 + 2)'"}"""))
        assertTrue(text.contains("4"), "Should contain result: $text")
    }

    @Test
    fun `node execution`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "node -e 'console.log(3 * 7)'"}"""))
        assertTrue(text.contains("21"), "Should contain result: $text")
    }

    @Test
    fun `java version available`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "java -version 2>&1 | head -1"}"""))
        assertTrue(text.contains("openjdk") || text.contains("java"), "Should show java version: $text")
    }

    @Test
    fun `sqlite works`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "sqlite3 :memory: 'SELECT 1+1;'"}"""))
        assertTrue(text.contains("2"), "Should contain sqlite result: $text")
    }

    @Test
    fun `graphviz generates svg`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "echo 'digraph { A -> B }' | dot -Tsvg | head -3"}"""))
        assertTrue(text.contains("svg"), "Should contain SVG output: $text")
    }

    @Test
    fun `pip install and use package`() {
        assumeSandboxAvailable()

        val text = resultText(newScratch().call("""{"command": "python3 -c 'import pandas; print(pandas.__version__)'"}"""))
        assertTrue(text.isNotBlank(), "Should show pandas version: $text")
    }

    @Test
    fun `resumes a paused session on next call`() {
        assumeSandboxAvailable()

        val mgr = DockerSandboxSessionManager().also { sessionManager = it }
        val tool = ScratchTool(sessionManager = mgr).also { scratch = it }
        tool.call("""{"command": "echo 'persist me' > /tmp/a.txt"}""")

        val active = mgr.list().single()
        active.pause()
        assertEquals(SandboxSession.SessionState.PAUSED, active.state)

        val text = resultText(tool.call("""{"command": "cat /tmp/a.txt"}"""))
        assertTrue(text.contains("persist me"), "Paused session should be resumed and state preserved: $text")
        assertEquals(SandboxSession.SessionState.ACTIVE, active.state)
    }

    @Test
    fun `recreates session if previous was evicted`() {
        assumeSandboxAvailable()

        val mgr = DockerSandboxSessionManager().also { sessionManager = it }
        val tool = ScratchTool(sessionManager = mgr).also { scratch = it }
        tool.call("""{"command": "echo first"}""")
        val firstId = mgr.list().single().id

        mgr.list().single().close()

        val text = resultText(tool.call("""{"command": "echo second"}"""))
        assertTrue(text.contains("second"), "Should run in new session: $text")
        val newId = mgr.list().single { it.state != SandboxSession.SessionState.CLOSED }.id
        assertNotEquals(firstId, newId, "A new session should have been created")
    }

    @Test
    fun `close destroys session in manager`() {
        assumeSandboxAvailable()

        val mgr = DockerSandboxSessionManager().also { sessionManager = it }
        val tool = ScratchTool(sessionManager = mgr).also { scratch = it }
        tool.call("""{"command": "echo ready"}""")
        assertEquals(1, mgr.list().size, "Session should be registered with manager")

        tool.close()
        assertTrue(
            mgr.list().none { it.state == SandboxSession.SessionState.ACTIVE },
            "No active sessions should remain after close",
        )
    }
}
