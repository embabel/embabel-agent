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

import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.ScratchTool
import com.embabel.agent.sandbox.process.ProcessExecution
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class DockerSessionCopyTest {
    @TempDir
    lateinit var root: Path
    private val config = SandboxConfig(enabled = true, image = "alpine:latest", network = false, propagateEnv = emptyList())

    @BeforeEach
    fun requireDocker() {
        assumeTrue(DockerExecutor.isDockerAvailable() && DockerExecutor.imageExists(config.image))
    }

    private fun read(session: DockerSandboxSession, path: String): String {
        val result = session.execute(ExecutionRequest(listOf("cat", path), timeout = 10.seconds))
        assertIs<ExecutionResult.Completed>(result)
        assertEquals(0, result.exitCode)
        return result.stdout
    }

    @Nested
    inner class Destinations {
        @Test
        fun `relative file destinations use container root despite a non-root image working directory`() {
            assumeTrue(DockerExecutor.imageExists(ScratchTool.DEFAULT_IMAGE))
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("relative-copy", config.copy(image = ScratchTool.DEFAULT_IMAGE), ttl = 1.hours).use { session ->
                val pwd = assertIs<ExecutionResult.Completed>(session.execute(ExecutionRequest(listOf("pwd"), timeout = 10.seconds)))
                assertEquals(0, pwd.exitCode)
                assertNotEquals("/", pwd.stdout.trim())
                session.copyTo(source, "tmp/relative-copy/result.txt")
                assertEquals("content", read(session, "/tmp/relative-copy/result.txt"))
                session.copyFrom("tmp/relative-copy/result.txt", root.resolve("copied.txt"))
                assertEquals("content", Files.readString(root.resolve("copied.txt")))
            }
        }

        @Test
        fun `single file can have a different destination name with spaces`() {
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("file-copy", config, ttl = 1.hours).use { session ->
                session.copyTo(source, "/tmp/nested directory/renamed.txt")
                assertEquals("content", read(session, "/tmp/nested directory/renamed.txt"))
                session.copyFrom("/tmp/nested directory/renamed.txt", root.resolve("copied.txt"))
                assertEquals("content", Files.readString(root.resolve("copied.txt")))
            }
        }

        @Test
        fun `copying a directory copies its contents`() {
            val source = Files.createDirectory(root.resolve("source"))
            Files.writeString(source.resolve("child.txt"), "child")
            DockerSandboxSession("directory-copy", config, ttl = 1.hours).use { session ->
                session.copyTo(source, "/tmp/destination")
                assertEquals("child", read(session, "/tmp/destination/child.txt"))
                session.copyFrom("/tmp/destination", root.resolve("copied"))
                assertEquals("child", Files.readString(root.resolve("copied/child.txt")))
            }
        }

        @Test
        fun `file can be copied into an existing directory`() {
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("existing-directory", config, ttl = 1.hours).use { session ->
                session.copyTo(Files.createDirectory(root.resolve("empty")), "/tmp/destination")
                session.copyTo(source, "/tmp/destination")
                assertEquals("content", read(session, "/tmp/destination/source.txt"))
            }
        }

        @Test
        fun `explicit directory destination creates a directory for a file`() {
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("explicit-directory", config, ttl = 1.hours).use { session ->
                session.copyTo(source, "/tmp/new-directory/")
                assertEquals("content", read(session, "/tmp/new-directory/source.txt"))
            }
        }

        @Test
        fun `mkdir failure is reported and releases the session lock`() {
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("mkdir-failure", config, ttl = 1.hours).use { session ->
                assertFailsWith<RuntimeException> { session.copyTo(source, "/proc/missing/file") }
                val result = session.execute(ExecutionRequest(listOf("true"), timeout = 10.seconds))
                assertEquals(0, assertIs<ExecutionResult.Completed>(result).exitCode)
            }
        }
    }

    @Nested
    inner class Deadlines {
        private fun stallMkdir(session: DockerSandboxSession) {
            val result = session.execute(ExecutionRequest(listOf("sh", "-s"), timeout = 10.seconds, stdin = """
                rm /bin/mkdir
                cat > /bin/mkdir <<'EOF'
                #!/bin/sh
                echo ready > /tmp/copy-started
                sleep 60
                exec /bin/busybox mkdir "${'$'}@"
                EOF
                chmod +x /bin/mkdir
            """.trimIndent()))
            assertEquals(0, assertIs<ExecutionResult.Completed>(result).exitCode)
        }

        private fun restoreMkdir(session: DockerSandboxSession) {
            val result = session.execute(ExecutionRequest(
                listOf("/bin/busybox", "ln", "-sf", "/bin/busybox", "/bin/mkdir"), timeout = 10.seconds))
            assertEquals(0, assertIs<ExecutionResult.Completed>(result).exitCode)
        }

        @Test
        fun `stalled copy times out and releases the session lock`() {
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("copy-timeout", config, ttl = 1.hours).use { session ->
                stallMkdir(session)
                val started = System.nanoTime()
                assertContains(assertFailsWith<IOException> { session.copyTo(source, "/tmp/file") }.message!!, "timed out")
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 40)
                restoreMkdir(session)
                session.copyTo(source, "/tmp/file")
                assertEquals("content", read(session, "/tmp/file"))
            }
        }

        @Test
        fun `interrupted copy releases the session lock and retains interrupt status`() {
            val source = Files.writeString(root.resolve("source.txt"), "content")
            DockerSandboxSession("copy-interruption", config, ttl = 1.hours).use { session ->
                stallMkdir(session)
                val failure = AtomicReference<Throwable?>()
                val interrupted = AtomicBoolean()
                val worker = Thread {
                    try { session.copyTo(source, "/tmp/file") }
                    catch (e: Throwable) {
                        failure.set(e)
                        interrupted.set(Thread.currentThread().isInterrupted)
                    }
                }.apply { isDaemon = true; start() }
                try {
                    val command = listOf("docker", "exec", session.containerId!!, "sh", "-c",
                        "while [ ! -e /tmp/copy-started ]; do sleep 0.05; done")
                    val ready = ProcessExecution.run(ProcessBuilder(command), ExecutionRequest(command, timeout = 10.seconds))
                    assertEquals(0, assertIs<ExecutionResult.Completed>(ready).exitCode)
                    worker.interrupt()
                    worker.join(3000)
                    assertFalse(worker.isAlive)
                    assertIs<InterruptedException>(failure.get())
                    assertTrue(interrupted.get())
                } finally { worker.interrupt() }
                restoreMkdir(session)
                session.copyTo(source, "/tmp/file")
                assertEquals("content", read(session, "/tmp/file"))
            }
        }
    }
}
