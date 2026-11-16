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
package com.embabel.agent.sandbox.process

import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource

/** Bounded process I/O shared by host execution and Docker CLI operations. */
internal object ProcessExecution {
    private const val MAX_CAPTURED_CHARS = 1_048_576
    private const val MAX_CALLBACK_LINE_CHARS = 65_536
    private val logger = LoggerFactory.getLogger(ProcessExecution::class.java)

    fun run(builder: ProcessBuilder, request: ExecutionRequest): ExecutionResult {
        val started = TimeSource.Monotonic.markNow()
        fun remainingNanos(): Long {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Execution interrupted")
            val remaining = (request.timeout - started.elapsedNow()).inWholeNanoseconds
            if (remaining <= 0) throw TimeoutException("Command timed out")
            return remaining
        }
        val stdout = Capture("stdout", request.captureOutput)
        val stderr = Capture("stderr", request.captureOutput)
        val failure = AtomicReference<Exception?>()
        val inputFailure = AtomicReference<Exception?>()
        val callbacksFinished = AtomicBoolean()
        val lines = ArrayBlockingQueue<String>(64)
        val workers = mutableListOf<Thread>()
        var process: Process? = null
        return try {
            remainingNanos()
            val running = builder.start().also { process = it }
            fun worker(name: String, task: () -> Unit): Thread = Thread(task).apply {
                isDaemon = true
                this.name = "sandbox-$name"
                workers.add(this)
                start()
            }
            val callbackWorker = request.stdoutCallback?.let { callback ->
                worker("callback") {
                    try {
                        while (!callbacksFinished.get() || lines.isNotEmpty()) {
                            val line = lines.poll(50, TimeUnit.MILLISECONDS) ?: continue
                            try {
                                callback(line)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            } catch (e: Exception) {
                                logger.debug("Stdout callback failed: {}", e.message)
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            val stdoutWorker = worker("stdout") {
                try {
                    read(running.inputStream, stdout, request.stdoutCallback?.let {
                        { line ->
                            if (!lines.offer(line, remainingNanos(), TimeUnit.NANOSECONDS)) {
                                throw TimeoutException("Stdout callback delivery timed out")
                            }
                        }
                    })
                } catch (e: Exception) {
                    failure.compareAndSet(null, e)
                    running.destroyForcibly()
                } finally {
                    callbacksFinished.set(true)
                }
            }
            val stderrWorker = worker("stderr") {
                try {
                    read(running.errorStream, stderr)
                } catch (e: Exception) {
                    failure.compareAndSet(null, e)
                    running.destroyForcibly()
                }
            }
            val stdinWorker = worker("stdin") {
                try {
                    running.outputStream.bufferedWriter().use { writer -> request.stdin?.let(writer::write) }
                } catch (e: Exception) {
                    inputFailure.set(e)
                }
            }
            if (!running.waitFor(remainingNanos(), TimeUnit.NANOSECONDS)) throw TimeoutException()
            for (worker in listOfNotNull(stdoutWorker, stderrWorker, stdinWorker, callbackWorker)) {
                if (worker.isAlive) TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos())
                if (worker.isAlive) throw TimeoutException()
            }
            failure.get()?.let { throw it }
            // Preserve a nonzero command exit when the child deliberately closes stdin early.
            if (running.exitValue() == 0) inputFailure.get()?.let { throw IOException("Command input failed", it) }
            ExecutionResult.Completed(running.exitValue(), stdout.text(), stderr.text(), started.elapsedNow())
        } catch (e: TimeoutException) {
            ExecutionResult.TimedOut(stderr.text().takeIf(String::isNotEmpty), started.elapsedNow())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecutionResult.Failed("Execution interrupted", e, started.elapsedNow())
        } catch (e: Exception) {
            ExecutionResult.Failed("Execution failed: ${e.message}", e, started.elapsedNow())
        } finally {
            callbacksFinished.set(true)
            process?.destroyForcibly()
            workers.filter(Thread::isAlive).forEach(Thread::interrupt)
            val interrupted = Thread.interrupted()
            try {
                val cleanupStarted = TimeSource.Monotonic.markNow()
                val cleanupBudget = minOf((request.timeout - started.elapsedNow()).inWholeNanoseconds.coerceAtLeast(0),
                    TimeUnit.MILLISECONDS.toNanos(100))
                for (worker in workers) {
                    val remaining = cleanupBudget - cleanupStarted.elapsedNow().inWholeNanoseconds
                    if (worker.isAlive && remaining > 0) TimeUnit.NANOSECONDS.timedJoin(worker, remaining)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun read(stream: InputStream, capture: Capture, callback: ((String) -> Unit)? = null) {
        val line = callback?.let { StringBuilder() }
        stream.reader().use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                capture.append(buffer, count)
                if (line != null) {
                    for (i in 0 until count) {
                        if (buffer[i] == '\n') {
                            callback.invoke(line.toString().trimEnd('\r'))
                            line.setLength(0)
                        } else {
                            if (line.length >= MAX_CALLBACK_LINE_CHARS) {
                                throw IOException("Stdout callback line exceeded $MAX_CALLBACK_LINE_CHARS characters")
                            }
                            line.append(buffer[i])
                        }
                    }
                }
            }
        }
        if (line != null && line.isNotEmpty()) callback.invoke(line.toString().trimEnd('\r'))
    }

    private class Capture(private val name: String, private val enabled: Boolean) {
        private val content = StringBuilder()
        private var truncated = false

        @Synchronized
        fun append(buffer: CharArray, count: Int) {
            if (!enabled) return
            val retained = minOf(count, MAX_CAPTURED_CHARS - content.length)
            if (retained > 0) content.appendRange(java.nio.CharBuffer.wrap(buffer), 0, retained)
            if (retained < count) truncated = true
        }

        @Synchronized
        fun text(): String = if (truncated) "$content\n[$name truncated after $MAX_CAPTURED_CHARS characters]" else content.toString()
    }
}
