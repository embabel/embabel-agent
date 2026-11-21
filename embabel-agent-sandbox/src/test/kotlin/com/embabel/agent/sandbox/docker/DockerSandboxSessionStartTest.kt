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
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class DockerSandboxSessionStartTest {
    @BeforeEach
    fun interceptDocker() = mockkConstructor(ProcessBuilder::class)

    @AfterEach
    fun restoreDocker() = unmockkConstructor(ProcessBuilder::class)

    private fun dockerOutput(stdout: String, stderr: String, exit: Int = 0) {
        val process = mockk<Process>(relaxed = true)
        every { process.inputStream } returns ByteArrayInputStream(stdout.toByteArray())
        every { process.errorStream } returns ByteArrayInputStream(stderr.toByteArray())
        every { process.waitFor() } returns exit
        every { anyConstructed<ProcessBuilder>().start() } returns process
    }

    @Nested
    inner class Startup {
        @Test
        fun `pull progress is not used as the container ID`() {
            dockerOutput("4f2a9c1e7b3d0123456789abcdef\n",
                "Unable to find image locally\nPulling image\nDownload complete\n")
            val session = DockerSandboxSession("pull", SandboxConfig(), ttl = 1.hours)
            assertEquals("4f2a9c1e7b3d", session.containerId)
        }

        @Test
        fun `failed start reports stderr`() {
            dockerOutput("", "Docker: no space left on device", exit = 125)
            val failure = assertFailsWith<RuntimeException> {
                DockerSandboxSession("failure", SandboxConfig(), ttl = 1.hours)
            }
            assertContains(failure.message!!, "no space left on device")
        }
    }
}
