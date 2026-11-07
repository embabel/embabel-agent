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
import com.embabel.agent.sandbox.ExecutionArtifact
import com.embabel.agent.sandbox.ArtifactPublishingSession
import com.embabel.agent.sandbox.ArtifactPublication
import com.embabel.agent.sandbox.PublishedFile
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxSession
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toJavaDuration

/**
 * Docker-backed [SandboxSession] that maintains a long-lived container.
 *
 * The container is created and started atomically with `docker run -d`.
 * Commands are executed via `docker exec`. State (files, packages, env) persists
 * across executions. The container can be paused (`docker pause`) and resumed
 * (`docker unpause`) without losing any state including tmpfs mounts.
 *
 * @param label human-readable session label
 * @param config sandbox configuration
 * @param owner optional owner identifier
 * @param ttl time-to-live for idle eviction
 * @param artifactExport optional explicit publication; published files are owned by the application
 */
class DockerSandboxSession(
    override val label: String,
    override val config: SandboxConfig,
    override val owner: String? = null,
    val ttl: Duration,
    override val metadata: Map<String, String> = emptyMap(),
    private val artifactExport: ArtifactExportConfig? = null,
) : SandboxSession, ArtifactPublishingSession {

    private val logger = LoggerFactory.getLogger(DockerSandboxSession::class.java)
    private val operationLock = ReentrantLock()

    override val id: String = UUID.randomUUID().toString().take(12)

    override val createdAt: Instant = Instant.now()

    @Volatile
    override var lastActiveAt: Instant = Instant.now()
        private set

    @Volatile
    override var state: SandboxSession.SessionState = SandboxSession.SessionState.ACTIVE
        private set

    /** The Docker container ID (short form). */
    @Volatile
    var containerId: String? = null
        private set

    private val containerName = "sandbox-session-$id"

    init {
        startContainer()
    }

    /**
     * Creates and starts the container atomically using `docker run -d`.
     *
     * Uses `docker run -d` instead of separate `docker create` + `docker start`
     * to avoid race conditions in CI environments where the container could
     * disappear between the two commands.
     */
    private fun startContainer() {
        val cmd = mutableListOf(
            "docker", "run", "-d",
            "--name", containerName,
        )

        // Mirror caller-supplied metadata to docker --label so it's queryable
        // via `docker ps --filter label=k=v`. Semantics live with the caller
        // — typical uses include per-JVM ownership tagging, request id for
        // diagnostics, tenant grouping. Validate keys early to surface
        // mistakes on the Kotlin side rather than as cryptic docker errors.
        for ((k, v) in metadata) {
            require(k.isNotBlank() && !k.contains('=') && !k.contains(' ')) {
                "Invalid metadata key '$k' — must be non-blank, no '=' or whitespace"
            }
            cmd.addAll(listOf("--label", "$k=$v"))
        }

        config.memory.let { cmd.addAll(listOf("--memory", it)) }
        config.cpus.let { cmd.addAll(listOf("--cpus", it)) }
        if (!config.network) cmd.addAll(listOf("--network", "none"))

        // Resolve and add environment variables
        for (key in config.propagateEnv) {
            System.getenv(key)?.let { cmd.addAll(listOf("-e", "$key=$it")) }
        }
        for ((containerKey, hostKey) in config.mapEnv) {
            System.getenv(hostKey)?.let { cmd.addAll(listOf("-e", "$containerKey=$it")) }
        }
        for ((key, value) in config.runtimeEnv) {
            cmd.addAll(listOf("-e", "$key=$value"))
        }
        for ((host, ip) in config.extraHosts) {
            cmd.addAll(listOf("--add-host", "$host:$ip"))
        }

        cmd.addAll(listOf(config.image, "sleep", "infinity"))

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            state = SandboxSession.SessionState.CLOSED
            throw RuntimeException("Failed to start sandbox container: $output")
        }

        containerId = output.take(12)
        logger.info("Sandbox session '{}' ({}) started: container={}", label, id, containerId)
    }

    override fun execute(request: ExecutionRequest): ExecutionResult {
        // Callbacks run while output streams, but never on a thread that execute waits for
        // while holding operationLock. A callback may itself call close or copyFrom.
        val started = TimeSource.Monotonic.markNow()
        val callbacks = request.stdoutCallback?.let { StdoutCallbacks(it) }
        val effectiveRequest = callbacks?.let { callback ->
            request.copy(stdoutCallback = { line ->
                val remaining = (request.timeout - started.elapsedNow()).inWholeNanoseconds
                if (remaining <= 0) throw TimeoutException("Stdout callback delivery timed out")
                callback.submit(line, remaining)
            })
        } ?: request
        var locked = false
        val result = try {
            locked = operationLock.tryLock(request.timeout.inWholeNanoseconds.coerceAtLeast(0), TimeUnit.NANOSECONDS)
            val remaining = request.timeout - started.elapsedNow()
            if (!locked || !remaining.isPositive()) ExecutionResult.TimedOut(duration = started.elapsedNow())
            else executeLocked(effectiveRequest.copy(timeout = remaining))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecutionResult.Failed("Session execution interrupted", cause = e)
        } finally {
            if (locked) operationLock.unlock()
            callbacks?.finish()
        }
        try {
            if (callbacks != null && !callbacks.await(request.timeout - started.elapsedNow())) {
                return ExecutionResult.TimedOut(duration = started.elapsedNow())
            }
        } catch (e: InterruptedException) {
            callbacks?.cancel()
            Thread.currentThread().interrupt()
            return ExecutionResult.Failed("Session execution interrupted", cause = e)
        }
        return result
    }

    private fun executeLocked(request: ExecutionRequest): ExecutionResult {
        check(state == SandboxSession.SessionState.ACTIVE) {
            "Cannot execute in session '$id' with state $state"
        }
        return try {
            executeCommand(request)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecutionResult.Failed("Session execution interrupted", cause = e)
        } catch (e: Exception) {
            ExecutionResult.Failed("Session execution failed: ${e.message}", cause = e)
        } finally {
            lastActiveAt = Instant.now()
        }
    }

    override fun publishFiles(containerPaths: List<String>): ArtifactPublication {
        val paths = containerPaths.toList()
        val export = requireNotNull(artifactExport) { "Artifact publishing is not configured" }
        require(paths.isNotEmpty() && paths.size <= export.maxFiles) {
            "Select between 1 and ${export.maxFiles} files per publication"
        }
        require(paths.distinct().size == paths.size) { "Publication paths must not contain duplicates" }
        paths.forEach { publishedFileName(it) }
        return withPublication { cid, settings, deadline ->
            val exporter = DockerArtifactExporter(settings)
            val files = mutableListOf<PublishedFile>()
            val failures = linkedMapOf<String, String>()
            var remainingBytes = settings.maxTotalBytes
            for (path in paths) {
                try {
                    deadline.remainingNanos()
                    val file = publishSelectedFile(cid, path, exporter, deadline,
                        minOf(settings.maxFileBytes, remainingBytes))
                    files.add(PublishedFile(path, file))
                    remainingBytes -= file.sizeBytes
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failures[path] = "Publication interrupted"
                } catch (e: ArtifactPayloadLimitException) {
                    failures[path] = if (remainingBytes < settings.maxFileBytes) {
                        "Selected file exceeds remaining batch maxTotalBytes ($remainingBytes bytes)"
                    } else requireNotNull(e.message)
                } catch (e: Exception) {
                    failures[path] = e.message ?: "Publication failed"
                }
            }
            ArtifactPublication(files, failures)
        }
    }

    private fun <T> withPublication(action: (String, ArtifactExportConfig, ArtifactDeadline) -> T): T {
        val export = requireNotNull(artifactExport) { "Artifact publishing is not configured" }
        val deadline = ArtifactDeadline(export.timeout)
        var locked = false
        try {
            locked = operationLock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
            if (!locked) throw IOException("Artifact publication timed out waiting for the session")
            check(state == SandboxSession.SessionState.ACTIVE) { "Cannot publish from session '$id' with state $state" }
            val cid = requireNotNull(containerId) { "No container ID" }
            return action(cid, export, deadline)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            if (locked) {
                lastActiveAt = Instant.now()
                operationLock.unlock()
            }
        }
    }

    private fun publishSelectedFile(
        cid: String, path: String, exporter: DockerArtifactExporter,
        deadline: ArtifactDeadline, maxPayloadBytes: Long,
    ): ExecutionArtifact.File {
        // Pass paths as positional arguments, never as shell syntax. Archive validation
        // independently checks the selected leaf and bounds actual transferred bytes.
        ArtifactTransfer.run(listOf("docker", "exec", "-i", cid, "sh", "-s", "--", path),
            OutputStream.nullOutputStream(), 4096, deadline, stdin = VALIDATE_PUBLISH_SOURCE)
        return exporter.export(cid, path, deadline, maxPayloadBytes)
    }

    private companion object {
        private const val MAX_CAPTURED_CHARS = 1_048_576
        private const val MAX_CALLBACK_LINE_CHARS = 65_536
        private const val CALLBACK_QUEUE_SIZE = 64

        // POSIX sh only: no Python, hashing utility, or temporary container file required.
        val VALIDATE_PUBLISH_SOURCE = """
            set -efu
            source_path=${'$'}1
            ancestor=${'$'}source_path
            while [ "${'$'}ancestor" != / ]; do
                if [ -L "${'$'}ancestor" ]; then
                    echo 'Cannot publish a symbolic link or a path through one' >&2
                    exit 2
                fi
                ancestor=${'$'}{ancestor%/*}
                [ -n "${'$'}ancestor" ] || ancestor=/
            done
            if [ ! -e "${'$'}source_path" ]; then
                echo 'Selected file does not exist or is inaccessible' >&2
                exit 3
            fi
            if [ ! -f "${'$'}source_path" ]; then
                echo 'Selected path is not a regular file' >&2
                exit 4
            fi
        """.trimIndent()
    }

    private class CallbackOutputLimitException(message: String) : IOException(message)

    private class StdoutCallbacks(private val callback: (String) -> Unit) {
        private val finished = AtomicBoolean()
        private val lines = ArrayBlockingQueue<String>(CALLBACK_QUEUE_SIZE)
        private val worker = Thread {
            while (true) {
                val next = try { lines.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (next == null) {
                    if (finished.get() && lines.isEmpty()) break
                    continue
                }
                try {
                    callback(next)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // Callback failures must not prevent stdout from being drained.
                }
            }
        }.apply { isDaemon = true; name = "sandbox-stdout-callback"; start() }

        fun submit(line: String, remainingNanos: Long) {
            if (!lines.offer(line, remainingNanos, TimeUnit.NANOSECONDS)) {
                throw TimeoutException("Stdout callback delivery timed out")
            }
        }

        fun finish() {
            finished.set(true)
        }

        fun cancel() = worker.interrupt()

        fun await(remaining: Duration): Boolean {
            if (remaining.isPositive()) TimeUnit.NANOSECONDS.timedJoin(worker, remaining.inWholeNanoseconds)
            if (worker.isAlive) {
                worker.interrupt()
                return false
            }
            return true
        }
    }

    private fun readStdout(stream: InputStream, request: ExecutionRequest): String {
        val captured = StringBuilder()
        val callbackLine = request.stdoutCallback?.let { StringBuilder() }
        var truncated = false
        stream.reader().use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val char = buffer[i]
                    if (request.captureOutput) {
                        if (captured.length < MAX_CAPTURED_CHARS) captured.append(char)
                        else truncated = true
                    }
                    if (callbackLine != null) {
                        if (char == '\n') {
                            request.stdoutCallback?.invoke(callbackLine.toString().trimEnd('\r'))
                            callbackLine.setLength(0)
                        } else {
                            if (callbackLine.length >= MAX_CALLBACK_LINE_CHARS) {
                                throw CallbackOutputLimitException("Stdout callback line exceeded $MAX_CALLBACK_LINE_CHARS characters")
                            }
                            callbackLine.append(char)
                        }
                    }
                }
            }
        }
        if (callbackLine != null && callbackLine.isNotEmpty()) {
            request.stdoutCallback?.invoke(callbackLine.toString().trimEnd('\r'))
        }
        if (!request.captureOutput) return ""
        val text = captured.toString().replace("\r\n", "\n").removeSuffix("\n").trimEnd('\r')
        return if (truncated) "$text\n[stdout truncated after $MAX_CAPTURED_CHARS characters]" else text
    }

    private fun executeCommand(request: ExecutionRequest): ExecutionResult {
        check(state == SandboxSession.SessionState.ACTIVE) {
            "Cannot execute in session '$id' with state $state"
        }

        val cid = containerId ?: return ExecutionResult.Failed("No container ID")

        val execCmd = mutableListOf("docker", "exec")
        if (request.stdin != null) execCmd.add("-i")

        // Set working directory if specified
        request.workingDirectory?.let {
            execCmd.addAll(listOf("-w", it.toString()))
        }

        // Add request-specific environment variables
        for ((key, value) in request.environment) {
            execCmd.addAll(listOf("-e", "$key=$value"))
        }

        execCmd.add(cid)
        execCmd.addAll(request.command)

        logger.debug("Session '{}' exec: {}", id, request.command.joinToString(" ").take(100))

        val started = TimeSource.Monotonic.markNow()
        var process: Process? = null
        val stdout = AtomicReference("")
        val stderr = StringBuffer()
        fun remainingNanos(): Long {
            val remaining = (request.timeout - started.elapsedNow()).inWholeNanoseconds
            if (remaining <= 0) throw TimeoutException("Command timed out")
            return remaining
        }
        return try {
            remainingNanos()
            val running = ProcessBuilder(execCmd).start().also { process = it }
            val ioFailure = AtomicReference<Exception?>()
            val stdoutThread = Thread {
                try {
                    stdout.set(readStdout(running.inputStream, request))
                } catch (e: Exception) {
                    ioFailure.compareAndSet(null, e)
                    running.destroyForcibly()
                }
            }.apply { isDaemon = true; name = "sandbox-stdout"; start() }
            val stderrThread = Thread {
                try {
                    running.errorStream.reader().use { reader ->
                        val buffer = CharArray(4096)
                        val characters = java.nio.CharBuffer.wrap(buffer)
                        var truncated = false
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (request.captureOutput) {
                                val retained = minOf(count, MAX_CAPTURED_CHARS - stderr.length)
                                if (retained > 0) stderr.appendRange(characters, 0, retained)
                                if (retained < count) truncated = true
                            }
                        }
                        if (truncated) stderr.append("\n[stderr truncated after $MAX_CAPTURED_CHARS characters]")
                    }
                } catch (e: Exception) { ioFailure.compareAndSet(null, e) }
            }.apply { isDaemon = true; name = "sandbox-stderr"; start() }
            // Write concurrently with draining both output streams. A child may produce
            // output before consuming all input, or may never consume its input at all.
            val stdinThread = Thread {
                try {
                    running.outputStream.bufferedWriter().use { writer -> request.stdin?.let { writer.write(it) } }
                } catch (e: Exception) { ioFailure.compareAndSet(null, e) }
            }.apply { isDaemon = true; name = "sandbox-stdin"; start() }
            if (!running.waitFor(remainingNanos(), TimeUnit.NANOSECONDS)) throw TimeoutException()
            for (worker in listOf(stdoutThread, stderrThread, stdinThread)) {
                TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos())
                if (worker.isAlive) throw TimeoutException()
            }
            // Preserve an actual nonzero command exit even if it closed stdin early.
            val failure = ioFailure.get()
            if (failure is TimeoutException) throw failure
            if (failure is CallbackOutputLimitException || running.exitValue() == 0) {
                failure?.let { throw IOException("Command I/O failed: ${it.message}", it) }
            }
            ExecutionResult.Completed(running.exitValue(), stdout.get(),
                stderr.toString(), started.elapsedNow())
        } catch (e: TimeoutException) {
            ExecutionResult.TimedOut(partialStderr = stderr.toString().takeIf { it.isNotBlank() },
                duration = started.elapsedNow())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecutionResult.Failed("Session execution interrupted", cause = e)
        } catch (e: Exception) {
            logger.error("Session '{}' execution failed: {}", id, e.message, e)
            ExecutionResult.Failed("Execution failed: ${e.message}", cause = e)
        } finally {
            process?.destroyForcibly()
            lastActiveAt = Instant.now()
        }
    }

    override fun copyFrom(containerPath: String, hostPath: Path) = operationLock.withLock {
        copyFromContainer(containerPath, hostPath)
    }

    private fun copyFromContainer(containerPath: String, hostPath: Path) {
        val cid = containerId ?: throw IllegalStateException("No container")
        val process = ProcessBuilder("docker", "cp", "$cid:$containerPath", hostPath.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw RuntimeException("docker cp failed: $output")
        }
    }

    override fun copyTo(hostPath: Path, containerPath: String) = operationLock.withLock {
        copyToContainer(hostPath, containerPath)
    }

    private fun copyToContainer(hostPath: Path, containerPath: String) {
        val cid = containerId ?: throw IllegalStateException("No container")
        // Ensure target directory exists
        ProcessBuilder("docker", "exec", cid, "mkdir", "-p", containerPath)
            .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        // Append /. to copy directory CONTENTS, not the directory itself
        val source = if (hostPath.toFile().isDirectory) "${hostPath}/." else hostPath.toString()
        val process = ProcessBuilder("docker", "cp", source, "$cid:$containerPath")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw RuntimeException("docker cp failed: $output")
        }
    }

    /**
     * Pauses the container using `docker pause`.
     *
     * Uses `docker pause` (not `docker stop`) to freeze processes:
     *
     * ```
     * ┌──────────────────────┬──────────────────┬───────────┬────────────┬──────────────┐
     * │       Command        │    Processes     │  Memory   │ Filesystem │ /tmp (tmpfs) │
     * ├──────────────────────┼──────────────────┼───────────┼────────────┼──────────────┤
     * │ docker stop/start    │ Killed/Restarted │ Lost      │ Preserved  │ Cleared      │
     * ├──────────────────────┼──────────────────┼───────────┼────────────┼──────────────┤
     * │ docker pause/unpause │ Frozen/Unfrozen  │ Preserved │ Preserved  │ Preserved    │
     * └──────────────────────┴──────────────────┴───────────┴────────────┴──────────────┘
     * ```
     */
    override fun pause() = operationLock.withLock { pauseContainer() }

    private fun pauseContainer() {
        if (state != SandboxSession.SessionState.ACTIVE) return
        val cid = containerId ?: return

        val process = ProcessBuilder("docker", "pause", cid)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            throw RuntimeException("Failed to pause container: $output")
        }

        state = SandboxSession.SessionState.PAUSED
        logger.info("Session '{}' ({}) paused", label, id)
    }

    /**
     * Resumes the container using `docker unpause`.
     *
     * @see pause for why `unpause` is used instead of `start`
     */
    override fun resume() = operationLock.withLock { resumeContainer() }

    private fun resumeContainer() {
        check(state == SandboxSession.SessionState.PAUSED) {
            "Cannot resume session '$id' with state $state"
        }
        val cid = containerId ?: throw IllegalStateException("No container ID")

        val process = ProcessBuilder("docker", "unpause", cid)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) {
            throw RuntimeException("Failed to resume container: $output")
        }

        state = SandboxSession.SessionState.ACTIVE
        lastActiveAt = Instant.now()
        logger.info("Session '{}' ({}) resumed", label, id)
    }

    override fun close() = operationLock.withLock { closeContainer() }

    internal fun evictIfIdle(pauseGracePeriod: Duration): Boolean {
        if (!operationLock.tryLock()) return false
        try {
            val idle = java.time.Duration.between(lastActiveAt, Instant.now())
            when (state) {
                SandboxSession.SessionState.ACTIVE -> if (idle > ttl.toJavaDuration()) pauseContainer()
                SandboxSession.SessionState.PAUSED ->
                    if (idle > (ttl + pauseGracePeriod).toJavaDuration()) closeContainer()
                SandboxSession.SessionState.CLOSED -> Unit
            }
            return state == SandboxSession.SessionState.CLOSED
        } finally {
            operationLock.unlock()
        }
    }

    private fun closeContainer() {
        if (state == SandboxSession.SessionState.CLOSED) return
        val cid = containerId ?: return

        try {
            ProcessBuilder("docker", "rm", "-f", cid)
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS)
            logger.info("Session '{}' ({}) closed", label, id)
        } catch (e: Exception) {
            logger.warn("Failed to remove container for session '{}': {}", id, e.message)
        }

        state = SandboxSession.SessionState.CLOSED
        containerId = null
    }
}
