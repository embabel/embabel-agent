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
import com.embabel.agent.sandbox.SandboxSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [DockerSandboxSessionManager].
 * These require Docker to be running.
 */
class DockerSandboxSessionManagerTest {

    private lateinit var manager: DockerSandboxSessionManager

    private val config = SandboxConfig(
        enabled = true,
        image = "alpine:latest",
        memory = "128m",
        cpus = "0.5",
        network = false,
    )

    @BeforeEach
    fun setup() {
        assumeTrue(DockerExecutor.isDockerAvailable(), "Docker is not available")
        manager = DockerSandboxSessionManager(
            maxSessionsPerOwner = 3,
            maxTotalSessions = 5,
        )
    }

    @AfterEach
    fun cleanup() {
        if (::manager.isInitialized) manager.closeAll()
    }

    @Test
    fun `creates session with correct properties`() {
        val session = manager.create(
            label = "test-session",
            config = config,
            owner = "alice",
            ttl = 2.hours,
        )

        assertEquals("test-session", session.label)
        assertEquals("alice", session.owner)
        assertEquals(SandboxSession.SessionState.ACTIVE, session.state)
    }

    @Test
    fun `get returns created session`() {
        val session = manager.create(label = "test", config = config, owner = "alice")
        val retrieved = manager.get(session.id)
        assertNotNull(retrieved)
        assertEquals(session.id, retrieved.id)
    }

    @Test
    fun `get returns null for unknown id`() {
        assertNull(manager.get("nonexistent"))
    }

    @Test
    fun `get returns null for closed session`() {
        val session = manager.create(label = "test", config = config)
        session.close()
        assertNull(manager.get(session.id))
    }

    @Test
    fun `list returns all active sessions`() {
        manager.create(label = "s1", config = config, owner = "alice")
        manager.create(label = "s2", config = config, owner = "alice")
        manager.create(label = "s3", config = config, owner = "bob")

        assertEquals(3, manager.list().size)
    }

    @Test
    fun `list filters by owner`() {
        manager.create(label = "s1", config = config, owner = "alice")
        manager.create(label = "s2", config = config, owner = "alice")
        manager.create(label = "s3", config = config, owner = "bob")

        assertEquals(2, manager.list(owner = "alice").size)
        assertEquals(1, manager.list(owner = "bob").size)
    }

    @Test
    fun `enforces max sessions per owner`() {
        repeat(3) { i ->
            manager.create(label = "s$i", config = config, owner = "alice")
        }

        assertThrows<IllegalStateException> {
            manager.create(label = "s4", config = config, owner = "alice")
        }
    }

    @Test
    fun `enforces max total sessions`() {
        repeat(5) { i ->
            manager.create(label = "s$i", config = config, owner = "user$i")
        }

        assertThrows<IllegalStateException> {
            manager.create(label = "s6", config = config, owner = "user6")
        }
    }

    @Test
    fun `different owners can have sessions up to their limit`() {
        repeat(3) { i -> manager.create(label = "a$i", config = config, owner = "alice") }
        // Bob should still be able to create sessions (total = 3, max = 5)
        val session = manager.create(label = "b1", config = config, owner = "bob")
        assertNotNull(session)
    }

    @Test
    fun `evictExpired pauses idle sessions`() {
        // Create a session with a very short TTL
        val session = manager.create(
            label = "short-lived",
            config = config,
            owner = "alice",
            ttl = 1.milliseconds,
        )

        // Wait for it to become idle
        Thread.sleep(50)

        manager.evictExpired()

        assertEquals(SandboxSession.SessionState.PAUSED, session.state)
    }

    @Test
    fun `closeAll removes all sessions`() {
        manager.create(label = "s1", config = config)
        manager.create(label = "s2", config = config)

        manager.closeAll()

        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `session executes commands through manager`() {
        val session = manager.create(label = "exec-test", config = config)

        val result = session.execute(ExecutionRequest(
            command = listOf("echo", "from-managed-session"),
            timeout = 10.seconds,
        ))

        assertTrue(result is ExecutionResult.Completed)
        assertTrue((result as ExecutionResult.Completed).stdout.contains("from-managed-session"))
    }
}
