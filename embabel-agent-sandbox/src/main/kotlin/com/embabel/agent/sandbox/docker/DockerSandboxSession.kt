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
import com.embabel.agent.sandbox.process.ProcessExecution
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
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

        // stdout carries only the container id; stderr carries pull progress ("Unable to find image …"),
        // so the two are read apart (embabel-agent-experimental#46).
        val process = ProcessBuilder(cmd).start()
        // A thread of its own, as [execute] uses: a pooled task can sit queued behind other sessions'
        // starts while Docker blocks on a full stderr pipe.
        var stderr = ""
        val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText().trim() }
            .apply { isDaemon = true; start() }
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        stderrThread.join()

        if (exitCode != 0) {
            state = SandboxSession.SessionState.CLOSED
            throw RuntimeException("Failed to start sandbox container: ${listOf(stderr, output).filter { it.isNotEmpty() }.joinToString("\n")}")
        }

        containerId = output.take(12)
        logger.info("Sandbox session '{}' ({}) started: container={}", label, id, containerId)
    }

    override fun execute(request: ExecutionRequest): ExecutionResult {
        val started = TimeSource.Monotonic.markNow()
        var locked = false
        fun finishProcess() {
            if (locked) {
                lastActiveAt = Instant.now()
                operationLock.unlock()
                locked = false
            }
        }
        return try {
            locked = operationLock.tryLock(request.timeout.inWholeNanoseconds.coerceAtLeast(0), TimeUnit.NANOSECONDS)
            val remaining = request.timeout - started.elapsedNow()
            if (!locked || !remaining.isPositive()) ExecutionResult.TimedOut(duration = started.elapsedNow())
            else {
                check(state == SandboxSession.SessionState.ACTIVE) {
                    "Cannot execute in session '$id' with state $state"
                }
                // A callback may close the session or copy a file, so release its lock before awaiting callbacks.
                when (val result = executeCommand(request.copy(timeout = remaining), ::finishProcess)) {
                    is ExecutionResult.Completed -> result.copy(duration = started.elapsedNow())
                    is ExecutionResult.TimedOut -> result.copy(duration = started.elapsedNow())
                    is ExecutionResult.Failed -> result.copy(duration = started.elapsedNow())
                    is ExecutionResult.Denied -> result.copy(duration = started.elapsedNow())
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecutionResult.Failed("Session execution interrupted", cause = e, duration = started.elapsedNow())
        } finally {
            finishProcess()
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
        private val COPY_TIMEOUT = 30.seconds

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

    private fun executeCommand(request: ExecutionRequest, onProcessFinished: () -> Unit): ExecutionResult {
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

        val result = ProcessExecution.run(ProcessBuilder(execCmd), request, onProcessFinished)
        return if (result is ExecutionResult.Completed) result.copy(
            stdout = result.stdout.replace("\r\n", "\n").removeSuffix("\n").trimEnd('\r'),
        ) else result
    }

    override fun copyFrom(containerPath: String, hostPath: Path) = withFileTransfer { cid, run ->
        run(listOf("docker", "cp", "$cid:$containerPath", hostPath.toString()))
    }

    override fun copyTo(hostPath: Path, containerPath: String) = withFileTransfer { cid, run ->
        require(containerPath.isNotEmpty()) { "Container destination path must not be empty" }
        // docker cp resolves relative container paths from /, independently of the image WORKDIR.
        val destination = if (containerPath.startsWith('/')) containerPath else "/$containerPath"
        val directory = java.nio.file.Files.isDirectory(hostPath)
        val targetDirectory = if (directory || destination.endsWith('/')) destination
        else destination.substringBeforeLast('/').ifEmpty { "/" }
        run(listOf("docker", "exec", cid, "mkdir", "-p", "--", targetDirectory))
        val source = if (directory) "$hostPath/." else hostPath.toString()
        run(listOf("docker", "cp", source, "$cid:$destination"))
    }

    private fun withFileTransfer(action: (String, (List<String>) -> Unit) -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        var locked = false
        try {
            locked = operationLock.tryLock(COPY_TIMEOUT.inWholeNanoseconds, TimeUnit.NANOSECONDS)
            if (!locked) throw IOException("File copy timed out waiting for the session")
            val cid = containerId ?: throw IllegalStateException("No container")
            action(cid) { command ->
                when (val result = ProcessExecution.run(ProcessBuilder(command),
                    ExecutionRequest(command, timeout = COPY_TIMEOUT - started.elapsedNow()))) {
                    is ExecutionResult.Completed -> if (!result.success) {
                        throw RuntimeException("Docker file copy failed (exit ${result.exitCode}): ${result.stderr}${result.stdout}")
                    }
                    is ExecutionResult.TimedOut -> throw IOException("Docker file copy timed out")
                    is ExecutionResult.Failed -> {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("File copy interrupted")
                        throw IOException(result.error, result.cause)
                    }
                    is ExecutionResult.Denied -> throw IOException(result.reason)
                }
            }
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
