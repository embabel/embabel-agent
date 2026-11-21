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

import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxSession
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DockerSessionAdmissionTest {
    private val config = SandboxConfig(enabled = true, image = "test-image")
    private val workers = Executors.newFixedThreadPool(3)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val starts = AtomicInteger()
    private lateinit var manager: DockerSandboxSessionManager

    @BeforeEach
    fun interceptDocker() = mockkConstructor(ProcessBuilder::class)

    @AfterEach
    fun cleanup() {
        release.countDown()
        workers.shutdown()
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow()
            if (::manager.isInitialized) manager.closeAll()
        } finally {
            unmockkConstructor(ProcessBuilder::class)
        }
    }

    private fun process(exit: Int = 0, beforeExit: () -> Unit = {}): Process {
        val process = mockk<Process>(relaxed = true)
        every { process.inputStream } returns ByteArrayInputStream("0123456789abcdef\n".toByteArray())
        every { process.errorStream } returns ByteArrayInputStream("startup diagnostic".toByteArray())
        every { process.waitFor() } answers { beforeExit(); exit }
        every { process.waitFor(any<Long>(), any<TimeUnit>()) } returns true
        return process
    }

    private fun slowFirstStart() {
        val slow = process {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "Test did not release the slow start" }
        }
        every { anyConstructed<ProcessBuilder>().start() } answers {
            if (starts.incrementAndGet() == 1) slow else process()
        }
    }

    @Nested
    inner class Reservations {
        @Test
        fun `a slow start does not block other owners and in-flight starts count toward total capacity`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 2)
            slowFirstStart()
            val slow = workers.submit<SandboxSession> { manager.create("slow", config, owner = "alice") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(manager.list().isEmpty())
            val fast = workers.submit<SandboxSession> { manager.create("fast", config, owner = "bob") }
                .get(5, TimeUnit.SECONDS)
            assertEquals(listOf(fast), manager.list())
            assertFailsWith<IllegalStateException> { manager.create("full", config, owner = "charlie") }
            release.countDown()
            assertEquals(SandboxSession.SessionState.ACTIVE, slow.get(5, TimeUnit.SECONDS).state)
            assertEquals(2, manager.list().size)
            fast.close()
            assertNotNull(manager.create("replacement", config, owner = "charlie"))
        }

        @Test
        fun `owner quota counts both registered sessions and in-flight starts`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 2, maxTotalSessions = 4)
            slowFirstStart()
            val slow = workers.submit<SandboxSession> { manager.create("slow", config, owner = "alice") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.create("fast", config, owner = "alice")
            assertFailsWith<IllegalStateException> { manager.create("owner-full", config, owner = "alice") }
            assertNotNull(manager.create("other-owner", config, owner = "bob"))
            release.countDown()
            slow.get(5, TimeUnit.SECONDS)
            assertEquals(2, manager.list("alice").size)
        }

        @Test
        fun `anonymous in-flight starts count toward the total quota`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 2)
            slowFirstStart()
            val slow = workers.submit<SandboxSession> { manager.create("slow", config) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotNull(manager.create("another-anonymous", config))
            assertFailsWith<IllegalStateException> { manager.create("full", config) }
            assertEquals(2, starts.get())
            release.countDown()
            slow.get(5, TimeUnit.SECONDS)
        }
    }

    @Nested
    inner class FailedStarts {
        @Test
        fun `closeAll keeps registered capacity occupied until the container closes`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 1)
            val closing = process()
            every { closing.waitFor(any<Long>(), any<TimeUnit>()) } answers {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                true
            }
            every { anyConstructed<ProcessBuilder>().start() } answers {
                if (starts.incrementAndGet() == 2) closing else process()
            }
            manager.create("existing", config, owner = "alice")
            val shutdown = workers.submit { manager.closeAll() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { manager.create("too-early", config, owner = "bob") }
            release.countDown()
            shutdown.get(5, TimeUnit.SECONDS)
            assertNotNull(manager.create("replacement", config, owner = "bob"))
        }

        @Test
        fun `failure to launch Docker releases global and owner capacity`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 1)
            every { anyConstructed<ProcessBuilder>().start() } answers {
                if (starts.incrementAndGet() == 1) throw IOException("cannot launch Docker")
                process()
            }
            assertFailsWith<IOException> { manager.create("failed", config, owner = "alice") }
            assertNotNull(manager.create("replacement", config, owner = "alice"))
        }

        @Test
        fun `nonzero Docker exit releases global and owner capacity`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 1)
            every { anyConstructed<ProcessBuilder>().start() } answers {
                process(exit = if (starts.incrementAndGet() == 1) 125 else 0)
            }
            assertContains(assertFailsWith<RuntimeException> {
                manager.create("failed", config, owner = "alice")
            }.message!!, "startup diagnostic")
            assertNotNull(manager.create("replacement", config, owner = "alice"))
        }

        @Test
        fun `interrupted startup releases its reservation`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 1)
            val failure = process(beforeExit = { throw InterruptedException("startup interrupted") })
            every { anyConstructed<ProcessBuilder>().start() } answers {
                if (starts.incrementAndGet() == 1) failure else process()
            }
            assertFailsWith<InterruptedException> { manager.create("interrupted", config, owner = "alice") }
            assertNotNull(manager.create("replacement", config, owner = "alice"))
        }

        @Test
        fun `closeAll cancels an in-flight start and closes its eventual container`() {
            manager = DockerSandboxSessionManager(maxSessionsPerOwner = 1, maxTotalSessions = 2)
            slowFirstStart()
            val slow = workers.submit<SandboxSession> { manager.create("slow", config, owner = "alice") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.closeAll()
            assertNotNull(manager.create("new-generation", config, owner = "bob"))
            release.countDown()
            assertIs<IllegalStateException>(assertFailsWith<ExecutionException> {
                slow.get(5, TimeUnit.SECONDS)
            }.cause)
            assertEquals(3, starts.get()) // Two starts and removal of the cancelled container.
            assertTrue(manager.list("alice").isEmpty())
            assertNotNull(manager.create("replacement", config, owner = "alice"))
        }
    }
}
