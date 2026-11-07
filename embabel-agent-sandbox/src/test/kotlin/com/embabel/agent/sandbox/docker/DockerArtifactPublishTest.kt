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
package com.embabel.agent.sandbox.docker

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.sandbox.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class DockerArtifactPublishTest {
    @TempDir lateinit var root: Path
    private val config = SandboxConfig(enabled = true, image = "alpine:latest", network = false, propagateEnv = emptyList())

    @BeforeEach
    fun requireDocker() {
        assumeTrue(DockerExecutor.isDockerAvailable() && DockerExecutor.imageExists(config.image), "Requires Docker and alpine:latest")
    }

    private fun run(session: SandboxSession, command: String) {
        val result = assertIs<ExecutionResult.Completed>(session.execute(
            ExecutionRequest(listOf("sh"), stdin = command, timeout = 10.seconds)))
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun `publishes only selected files anywhere in the workspace and leaves output directory alone`() {
        DockerSandboxSessionManager(ArtifactExportConfig(root)).use { manager ->
            ScratchTool(manager, config, shell = "sh").use { scratch ->
                val result = scratch.call("""{"command":"test ! -e /output && test -z \"${'$'}{OUTPUT_DIR+x}\" && mkdir -p /tmp/work && printf final > '/tmp/work/final report.pdf' && printf source > /tmp/work/build.py"}""")
                assertIs<Tool.Result.Text>(result)
                assertEquals("(no output)", result.content)
                val publish = ScratchPublishTool(scratch)
                val published = assertIs<Tool.Result.WithArtifact>(publish.call("""{"paths":["/tmp/work/final report.pdf"]}"""))
                val file = assertIs<ExecutionArtifact.File>((published.artifact as List<*>).single())
                assertEquals("final report.pdf", file.name)
                assertEquals("final", Files.readString(file.path))
                assertEquals(1L, Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
                val again = assertIs<Tool.Result.WithArtifact>(publish.call("""{"paths":["/tmp/work/final report.pdf"]}"""))
                val second = assertIs<ExecutionArtifact.File>((again.artifact as List<*>).single())
                assertNotEquals(file.path, second.path)
                assertEquals("final", Files.readString(second.path))
                scratch.close()
                assertEquals("final", Files.readString(file.path))
            }
        }
    }

    @Test
    fun `batch delivers only selected files with per-path failures and independent same-name outputs`() {
        DockerSandboxSession("batch", config, ttl = 1.hours, artifactExport = ArtifactExportConfig(root)).use { session ->
            run(session, "mkdir -p /tmp/a /tmp/b; printf first > /tmp/a/report.pdf; printf second > /tmp/b/report.pdf; printf script > /tmp/a/build.py")
            val result = session.publishFiles(listOf("/tmp/a/report.pdf", "/tmp/missing", "/tmp/b/report.pdf"))
            assertEquals(listOf("/tmp/a/report.pdf", "/tmp/b/report.pdf"), result.files.map { it.containerPath })
            assertEquals(listOf("report.pdf", "report.pdf"), result.files.map { it.artifact.name })
            assertEquals(listOf("first", "second"), result.files.map { Files.readString(it.artifact.path) })
            assertEquals(setOf("/tmp/missing"), result.failures.keys)
            assertContains(result.failures.getValue("/tmp/missing"), "does not exist")
            assertEquals(2L, Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
            session.close()
            result.files.forEach { assertTrue(Files.isReadable(it.artifact.path)) }
        }
    }

    @Test
    fun `batch bounds successful bytes and rejects invalid selections before exporting`() {
        DockerSandboxSession("batch-limits", config, ttl = 1.hours,
            artifactExport = ArtifactExportConfig(root, maxFiles = 3, maxTotalBytes = 5)).use { session ->
            run(session, "printf abc > /tmp/a; printf def > /tmp/b; printf ok > /tmp/c")
            for (paths in listOf(emptyList(), listOf("/tmp/a", "/tmp/b", "/tmp/c", "/tmp/d"),
                listOf("/tmp/a", "/tmp/a"), listOf("/tmp/a", "/tmp/../etc/passwd"))) {
                assertFailsWith<IllegalArgumentException> { session.publishFiles(paths) }
                assertEquals(0L, Files.list(root).use { it.count() })
            }
            val result = session.publishFiles(listOf("/tmp/a", "/tmp/b", "/tmp/c"))
            assertEquals(listOf("a", "c"), result.files.map { it.artifact.name })
            assertEquals(5L, result.files.sumOf { it.artifact.sizeBytes })
            assertEquals(setOf("/tmp/b"), result.failures.keys)
            assertEquals(2L, Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
        }
    }

    @Test
    fun `publication treats shell metacharacters in paths as literal data`() {
        DockerSandboxSession("quoted-path", config, ttl = 1.hours,
            artifactExport = ArtifactExportConfig(root)).use { session ->
            val path = "/tmp/report ' ${'$'}(touch injected) ; &.pdf"
            val created = assertIs<ExecutionResult.Completed>(session.execute(ExecutionRequest(
                listOf("sh", "-s"), stdin = "printf safe > \"${'$'}FILE\"", environment = mapOf("FILE" to path),
                timeout = 10.seconds)))
            assertEquals(0, created.exitCode, created.stderr)
            val result = session.publishFiles(listOf(path))
            assertTrue(result.failures.isEmpty(), result.failures.toString())
            assertEquals("safe", Files.readString(result.files.single().artifact.path))
            assertEquals(path, result.files.single().containerPath)
            assertEquals(path.substringAfterLast('/'), result.files.single().artifact.name)
            run(session, "test ! -e injected")
        }
    }

    @Test
    fun `empty files remain publishable after the batch payload allowance is exhausted`() {
        DockerSandboxSession("empty-at-limit", config, ttl = 1.hours,
            artifactExport = ArtifactExportConfig(root, maxTotalBytes = 2)).use { session ->
            run(session, "printf ok > /tmp/full; touch /tmp/empty; printf x > /tmp/extra")
            val result = session.publishFiles(listOf("/tmp/full", "/tmp/empty", "/tmp/extra"))
            assertEquals(listOf("full", "empty"), result.files.map { it.artifact.name })
            assertEquals(listOf(2L, 0L), result.files.map { it.artifact.sizeBytes })
            assertContains(result.failures.getValue("/tmp/extra"), "maxTotalBytes")
            assertEquals(2L, Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.count() })
        }
    }

    @Test
    fun `publication reports missing directory link and oversize errors without retaining partial exports`() {
        DockerSandboxSession("errors", config, ttl = 1.hours,
            artifactExport = ArtifactExportConfig(root, maxFileBytes = 2)).use { session ->
            run(session, "mkdir -p /tmp/folder; printf large > /tmp/large; ln -s /tmp/large /tmp/link; ln -s /tmp/folder /tmp/alias; printf ok > /tmp/folder/ok")
            for ((path, message) in mapOf("/tmp/folder" to "not a regular file", "/tmp/link" to "symbolic link",
                "/tmp/alias/ok" to "symbolic link", "/tmp/missing" to "does not exist")) {
                val result = session.publishFiles(listOf(path))
                assertTrue(result.files.isEmpty())
                assertContains(result.failures.getValue(path), message)
            }
            val tooLarge = session.publishFiles(listOf("/tmp/large"))
            assertTrue(tooLarge.files.isEmpty())
            assertContains(tooLarge.failures.getValue("/tmp/large"), "maxFileBytes")
            assertFailsWith<IllegalArgumentException> { session.publishFiles(listOf("/tmp/../etc/passwd")) }
            assertEquals(0L, Files.list(root).use { it.count() })
            assertEquals("ok", Files.readString(session.publishFiles(listOf("/tmp/folder/ok")).files.single().artifact.path))
        }
    }

    @Test
    fun `publishing refreshes activity and execution does not reserve OUTPUT_DIR`() {
        DockerSandboxSessionManager(ArtifactExportConfig(root)).use { manager ->
            val session = manager.create("activity", config, ttl = 1.seconds) as DockerSandboxSession
            val result = assertIs<ExecutionResult.Completed>(session.execute(ExecutionRequest(listOf("sh"),
                stdin = "test \"\$OUTPUT_DIR\" = /custom && printf ok > /tmp/report", timeout = 10.seconds,
                environment = mapOf("OUTPUT_DIR" to "/custom"))))
            assertEquals(0, result.exitCode, result.stderr)
            val before = session.lastActiveAt
            Thread.sleep(1100)
            val published = session.publishFiles(listOf("/tmp/report"))
            assertTrue(published.failures.isEmpty(), published.failures.toString())
            assertTrue(session.lastActiveAt > before)
            manager.evictExpired()
            assertEquals(SandboxSession.SessionState.ACTIVE, session.state)
        }
    }

    @Test
    fun `publication deadline includes waiting for an executing session`() {
        DockerSandboxSession("busy", config, ttl = 1.hours,
            artifactExport = ArtifactExportConfig(root, timeout = java.time.Duration.ofMillis(200))).use { session ->
            val workers = Executors.newSingleThreadExecutor()
            val ready = CountDownLatch(1)
            try {
                val execution = workers.submit<ExecutionResult> {
                    session.execute(ExecutionRequest(listOf("sh"), stdin = "echo ready; sleep 2; printf ok > /tmp/report",
                        timeout = 10.seconds, stdoutCallback = { ready.countDown() }))
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                assertContains(assertFailsWith<IOException> { session.publishFiles(listOf("/tmp/report")) }.message!!, "timed out")
                assertContains(assertFailsWith<IOException> {
                    session.publishFiles(listOf("/tmp/report", "/tmp/other"))
                }.message!!, "timed out")
                assertEquals(0, assertIs<ExecutionResult.Completed>(execution.get(15, TimeUnit.SECONDS)).exitCode)
                assertEquals(0L, Files.list(root).use { it.count() })
            } finally { workers.shutdownNow() }
        }
    }

    @Test
    fun `interrupted publication preserves cancellation and session usability`() {
        DockerSandboxSession("interrupt", config, ttl = 1.hours, artifactExport = ArtifactExportConfig(root)).use { session ->
            run(session, "printf ok > /tmp/report")
            try {
                Thread.currentThread().interrupt()
                assertFailsWith<InterruptedException> { session.publishFiles(listOf("/tmp/report")) }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            assertEquals("ok", Files.readString(session.publishFiles(listOf("/tmp/report")).files.single().artifact.path))
        }
    }
}
