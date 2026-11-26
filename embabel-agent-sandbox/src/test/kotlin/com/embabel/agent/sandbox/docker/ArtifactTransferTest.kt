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

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ArtifactTransferTest {
    @TempDir
    lateinit var root: Path

    private fun command(mode: String): List<String> {
        val source = root.resolve("ArtifactProducer.java")
        Files.writeString(source, """
            class ArtifactProducer {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "large":
                            for (int i = 0; i < 100000; i++) System.out.println("output");
                            break;
                        case "error":
                            System.err.print("transfer failed"); System.exit(3); break;
                        case "sleep": Thread.sleep(30000); break;
                        default:
                            for (int i = 0; i < 100000; i++) System.err.println("diagnostic");
                            System.out.print("ok");
                    }
                }
            }
        """.trimIndent())
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return listOf(java, source.toString(), mode)
    }

    @Nested
    inner class Bounds {
        @Test
        fun `drains stderr while capturing stdout`() {
            val output = ByteArrayOutputStream()
            ArtifactTransfer.run(command("ok"), output, 2, ArtifactDeadline(Duration.ofSeconds(20)))
            assertEquals("ok", output.toString())
        }

        @Test
        fun `fails when actual transferred bytes exceed limit`() {
            val output = ByteArrayOutputStream()
            val failure = assertFailsWith<IOException> {
                ArtifactTransfer.run(command("large"), output, 20, ArtifactDeadline(Duration.ofSeconds(20)))
            }
            assertContains(failure.message!!, "byte limit")
            assertTrue(output.size() <= 20)
        }

        @Test
        fun `nonzero exit is not a successful archive transfer`() {
            val error = assertFailsWith<IOException> {
                ArtifactTransfer.run(command("error"), ByteArrayOutputStream(), 100, ArtifactDeadline(Duration.ofSeconds(20)))
            }
            assertContains(error.message!!, "transfer failed")
        }

        @Test
        fun `deadline stops an unfinished transfer`() {
            val started = System.nanoTime()
            assertFailsWith<IOException> {
                ArtifactTransfer.run(command("sleep"), ByteArrayOutputStream(), 100, ArtifactDeadline(Duration.ofMillis(300)))
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        }

        @Test
        fun `deadline includes writing input to a process that never reads it`() {
            val started = System.nanoTime()
            assertFailsWith<IOException> {
                ArtifactTransfer.run(command("sleep"), ByteArrayOutputStream(), 100,
                    ArtifactDeadline(Duration.ofMillis(500)), stdin = "x".repeat(4 * 1024 * 1024))
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        }

        @Test
        fun `interrupted transfer returns promptly and retains interrupt status`() {
            val command = command("sleep")
            val error = AtomicReference<Throwable?>()
            val interrupted = AtomicBoolean()
            val worker = Thread {
                try {
                    ArtifactTransfer.run(command, ByteArrayOutputStream(), 100,
                        ArtifactDeadline(Duration.ofSeconds(20)), stdin = "x".repeat(4 * 1024 * 1024))
                } catch (e: Throwable) {
                    error.set(e)
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }.apply { isDaemon = true; start() }
            try {
                Thread.sleep(100)
                worker.interrupt()
                worker.join(3000)
                assertFalse(worker.isAlive)
                assertIs<InterruptedException>(error.get())
                assertTrue(interrupted.get())
            } finally { worker.interrupt() }
        }
    }
}
