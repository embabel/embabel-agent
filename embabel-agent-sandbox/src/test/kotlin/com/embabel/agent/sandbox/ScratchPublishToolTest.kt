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
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import kotlin.time.Duration

class ScratchPublishToolTest {
    private val file = ExecutionArtifact.File("report.pdf", Path.of("export"), "application/pdf", 12)
    private var creates = 0

    @Test
    fun `execution and publication share a session and existing sink accepts the file`() {
        var selected: String? = null
        val session = object : FakeSession(), ArtifactPublishingSession {
            override fun publishFiles(containerPaths: List<String>): ArtifactPublication {
                selected = containerPaths.single()
                return ArtifactPublication(listOf(PublishedFile("/tmp/report.pdf", file)))
            }
        }
        val scratch = scratch(session)
        scratch.call("""{"command":"create report"}""")
        val received = mutableListOf<Any>()
        val tool = Tool.sinkArtifacts(ScratchPublishTool(scratch), ExecutionArtifact.File::class.java,
            ArtifactSink { received.add(it) })
        val result = tool.call("""{"paths":["/tmp/report.pdf"]}""", ToolCallContext.EMPTY)
        assertIs<Tool.Result.WithArtifact>(result)
        assertContains(result.content, "Do not regenerate or republish")
        assertEquals(listOf(file), result.artifact)
        assertEquals(listOf<Any>(file), received)
        assertEquals("/tmp/report.pdf", selected)
        assertEquals(1, creates)
    }

    @Test
    fun `invalid arguments return an error without creating a session`() {
        val tool = ScratchPublishTool(scratch(FakeSession()))
        for (input in listOf("not json", "{}", "null", "[]", "{\"path\":42}", "{\"path\":\" \"}")) {
            assertIs<Tool.Result.Error>(tool.call(input), input)
        }
        assertEquals(0, creates)
    }

    @Test
    fun `batch forwards every successful artifact and reports failed paths`() {
        val second = file.copy(name = "data.csv", path = Path.of("second"))
        val session = object : FakeSession(), ArtifactPublishingSession {
            override fun publishFiles(containerPaths: List<String>): ArtifactPublication {
                assertEquals(listOf("/tmp/report.pdf", "/tmp/data.csv", "/tmp/missing"), containerPaths)
                return ArtifactPublication(listOf(
                    PublishedFile("/tmp/report.pdf", file), PublishedFile("/tmp/data.csv", second),
                ), mapOf("/tmp/missing" to "does not exist"))
            }
        }
        val received = mutableListOf<Any>()
        val tool = Tool.sinkArtifacts(ScratchPublishTool(scratch(session)), ExecutionArtifact.File::class.java,
            ArtifactSink { received.add(it) })
        val result = assertIs<Tool.Result.WithArtifact>(tool.call(
            """{"paths":["/tmp/report.pdf","/tmp/data.csv","/tmp/missing"]}""", ToolCallContext.EMPTY))
        assertEquals(listOf<Any>(file, second), received)
        assertEquals(listOf(file, second), result.artifact)
        assertContains(result.content, "Published /tmp/report.pdf")
        assertContains(result.content, "Published /tmp/data.csv")
        assertContains(result.content, "Failed /tmp/missing: does not exist")
        assertEquals(1, creates)
    }

    @Test
    fun `batch with no successes returns an error`() {
        val session = object : FakeSession(), ArtifactPublishingSession {
            override fun publishFiles(containerPaths: List<String>) = ArtifactPublication(emptyList(),
                mapOf("/tmp/missing" to "does not exist"))
        }
        val result = ScratchPublishTool(scratch(session)).call("""{"paths":["/tmp/missing"]}""")
        assertContains(assertIs<Tool.Result.Error>(result).message, "Failed /tmp/missing")
    }

    @Test
    fun `rejects ambiguous empty duplicate and nonstring batches before creating a session`() {
        val tool = ScratchPublishTool(scratch(FakeSession()))
        for (input in listOf(
            """{"path":"/tmp/a","paths":["/tmp/a"]}""", """{"paths":[]}""",
            """{"paths":["/tmp/a","/tmp/a"]}""", """{"paths":["/tmp/a",42]}""",
            """{"paths":null}""", """{"paths":"/tmp/a"}""", """{"paths":[" "] }""",
        )) assertIs<Tool.Result.Error>(tool.call(input), input)
        assertEquals(0, creates)
    }

    @Test
    fun `unsupported sessions report capability error`() {
        val result = ScratchPublishTool(scratch(FakeSession())).call("""{"paths":["/tmp/report.pdf"]}""")
        assertContains(assertIs<Tool.Result.Error>(result).message, "does not support")
    }

    @Test
    fun `publication failures retain their diagnostic and interruption flag`() {
        var failure: Exception = IOException("Selected file exceeds maxFileBytes")
        val session = object : FakeSession(), ArtifactPublishingSession {
            override fun publishFiles(containerPaths: List<String>): ArtifactPublication = throw failure
        }
        val tool = ScratchPublishTool(scratch(session))
        assertContains(assertIs<Tool.Result.Error>(tool.call("""{"paths":["/tmp/report.pdf"]}""")).message, "maxFileBytes")
        failure = InterruptedException("cancelled")
        try {
            assertContains(assertIs<Tool.Result.Error>(tool.call("""{"paths":["/tmp/report.pdf"]}""")).message, "interrupted")
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private open class FakeSession : SandboxSession {
        override val id = "test"
        override val label = "test"
        override val owner: String? = null
        override val createdAt = Instant.now()
        override val lastActiveAt = createdAt
        override val state = SandboxSession.SessionState.ACTIVE
        override val config = SandboxConfig()
        override fun execute(request: ExecutionRequest) = ExecutionResult.Completed(0, "ok", "", Duration.ZERO)
        override fun copyFrom(containerPath: String, hostPath: Path) = error("Unexpected copy")
        override fun copyTo(hostPath: Path, containerPath: String) = error("Unexpected copy")
        override fun pause() = Unit
        override fun resume() = Unit
        override fun close() = Unit
    }

    private fun scratch(session: SandboxSession): ScratchTool = ScratchTool(object : SandboxSessionManager {
        override fun create(label: String, config: SandboxConfig, owner: String?, ttl: Duration, metadata: Map<String, String>): SandboxSession {
            creates++
            return session
        }
        override fun get(id: String) = session.takeIf { it.id == id }
        override fun list(owner: String?) = listOf(session)
        override fun evictExpired() = Unit
        override fun closeAll() = Unit
    })
}
