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

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One deadline covers process completion and draining both pipes. */
internal class ArtifactDeadline(timeout: java.time.Duration) {
    private val started = System.nanoTime()
    private val budget = timeout.toNanos()

    fun remainingNanos(): Long {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Artifact export interrupted")
        val remaining = budget - (System.nanoTime() - started)
        if (remaining <= 0) throw IOException("Artifact export timed out")
        return remaining
    }

    fun remainingNanosOrZero(): Long = (budget - (System.nanoTime() - started)).coerceAtLeast(0)
}

internal object ArtifactTransfer {
    fun run(command: List<String>, output: OutputStream, maxBytes: Long, deadline: ArtifactDeadline, stdin: String? = null) {
        deadline.remainingNanos()
        val process = ProcessBuilder(command).start()
        val failure = AtomicReference<Exception?>()
        val errors = ByteArrayOutputStream()
        val stdout = Thread {
            try {
                val buffer = ByteArray(8192)
                var count = 0L
                process.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > maxBytes - count) throw IOException("Artifact transfer byte limit exceeded")
                        count += read
                        output.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
                process.destroyForcibly()
            }
        }.apply { isDaemon = true; name = "sandbox-artifact-stdout" }
        val stderr = Thread {
            try {
                val buffer = ByteArray(4096)
                process.errorStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val retained = minOf(read, 4096 - errors.size())
                        if (retained > 0) errors.write(buffer, 0, retained)
                    }
                }
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
                process.destroyForcibly()
            }
        }.apply { isDaemon = true; name = "sandbox-artifact-stderr" }
        val writer = Thread {
            try {
                process.outputStream.use { input ->
                    if (stdin != null) input.write(stdin.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
                process.destroyForcibly()
            }
        }.apply { isDaemon = true; name = "sandbox-artifact-stdin" }
        try {
            stdout.start()
            stderr.start()
            writer.start()
            if (!process.waitFor(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw IOException("Artifact export timed out")
            }
            for (reader in listOf(stdout, stderr, writer)) {
                TimeUnit.NANOSECONDS.timedJoin(reader, deadline.remainingNanos())
                if (reader.isAlive) throw IOException("Artifact export timed out draining output")
            }
            failure.get()?.let { throw IOException("Artifact transfer failed: ${it.message}", it) }
            if (process.exitValue() != 0) {
                throw IOException("Docker artifact operation failed (exit ${process.exitValue()}): ${errors.toString(Charsets.UTF_8).trim()}")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            process.destroyForcibly()
            // Cleanup uses at most 100ms of the remaining budget, including on interruption.
            val interrupted = Thread.interrupted()
            val cleanupStarted = System.nanoTime()
            val cleanupBudget = minOf(deadline.remainingNanosOrZero(), TimeUnit.MILLISECONDS.toNanos(100))
            fun cleanupRemaining() = (cleanupBudget - (System.nanoTime() - cleanupStarted)).coerceAtLeast(0)
            try {
                cleanupRemaining().takeIf { it > 0 }?.let {
                    process.waitFor(it, TimeUnit.NANOSECONDS)
                }
                for (reader in listOf(stdout, stderr, writer)) {
                    cleanupRemaining().takeIf { it > 0 }?.let {
                        TimeUnit.NANOSECONDS.timedJoin(reader, it)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
