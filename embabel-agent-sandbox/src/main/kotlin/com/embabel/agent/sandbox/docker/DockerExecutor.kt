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

import com.embabel.agent.sandbox.ExecutionArtifact
import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxedExecutor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Docker-based executor that runs commands inside containers for strong isolation.
 *
 * This provides isolation from the host system while still allowing commands to:
 * - Read input files via INPUT_DIR mount (/input)
 * - Write output artifacts via OUTPUT_DIR mount (/output)
 * - Optionally mount a working directory
 * - Access network (can be disabled)
 *
 * ## Example
 *
 * ```kotlin
 * val executor = DockerExecutor(
 *     image = "python:3.11-slim",
 *     networkEnabled = false,
 *     memoryLimit = "256m",
 * )
 *
 * val result = executor.execute(ExecutionRequest(
 *     command = listOf("python3", "-c", "print('Hello from container')"),
 *     timeout = 30.seconds,
 * ))
 * ```
 *
 * ## Security
 *
 * For maximum isolation, use:
 * ```kotlin
 * val executor = DockerExecutor.isolated(image = "my-image")
 * ```
 *
 * This disables networking and applies strict resource limits.
 *
 * @param image the Docker image to use
 * @param networkEnabled whether to allow network access
 * @param memoryLimit memory limit (e.g., "512m", "1g")
 * @param cpuLimit CPU limit (e.g., "1.0" for 1 CPU)
 * @param baseEnvironment base environment variables for all executions
 * @param user user to run as inside the container
 * @param workDir working directory inside the container
 * @param mounts additional volume mounts
 * @param readOnlyRootfs whether to mount the root filesystem as read-only
 */
class DockerExecutor(
    private val image: String,
    private val networkEnabled: Boolean = true,
    private val memoryLimit: String? = "512m",
    private val cpuLimit: String? = "1.0",
    private val baseEnvironment: Map<String, String> = emptyMap(),
    private val user: String? = null,
    private val workDir: String? = null,
    private val mounts: List<Mount> = emptyList(),
    private val readOnlyRootfs: Boolean = false,
) : SandboxedExecutor {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * A volume mount specification.
     *
     * @param hostPath path on the host
     * @param containerPath path in the container
     * @param readOnly whether the mount is read-only
     */
    data class Mount(
        val hostPath: Path,
        val containerPath: String,
        val readOnly: Boolean = false,
    ) {
        fun toDockerArg(): String {
            val mode = if (readOnly) "ro" else "rw"
            return "${hostPath.absolutePathString()}:$containerPath:$mode"
        }
    }

    override fun checkAvailability(): String? {
        return try {
            val process = ProcessBuilder("docker", "version")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "Docker check timed out"
            }
            if (process.exitValue() != 0) {
                val output = process.inputStream.bufferedReader().readText()
                return "Docker returned error: $output"
            }
            null
        } catch (e: Exception) {
            "Docker is not available: ${e.message}"
        }
    }

    override fun validate(request: ExecutionRequest): ExecutionResult.Denied? {
        checkAvailability()?.let { return ExecutionResult.Denied(it) }

        // Validate input files
        for (inputFile in request.inputFiles) {
            if (!Files.exists(inputFile)) {
                return ExecutionResult.Denied("Input file does not exist: $inputFile")
            }
        }

        return null
    }

    override fun execute(request: ExecutionRequest): ExecutionResult {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        validate(request)?.let { return it }

        // Create temp directories
        val tempBase = Files.createTempDirectory("docker-exec-")
        val inputDir = tempBase.resolve("input")
        val outputDir = tempBase.resolve("output")

        Files.createDirectories(inputDir)
        Files.createDirectories(outputDir)

        try {
            // Copy input files
            for (inputFile in request.inputFiles) {
                Files.copy(inputFile, inputDir.resolve(inputFile.fileName))
            }

            val dockerCommand = buildDockerCommand(request, inputDir, outputDir)
            logger.debug("Executing docker command: {}", dockerCommand.joinToString(" "))

            val processBuilder = ProcessBuilder(dockerCommand)
                .redirectErrorStream(false)

            val process = processBuilder.start()

            // Write stdin if provided
            if (request.stdin != null) {
                process.outputStream.bufferedWriter().use { it.write(request.stdin) }
            } else {
                process.outputStream.close()
            }

            // Read stdout line-by-line in a background thread so we can
            // stream progress via the callback while Docker is still running.
            val outputLines = mutableListOf<String>()
            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { rawLine ->
                            // Strip carriage returns added by Docker's -t (pseudo-TTY) flag.
                            // Without this, JSON parsing fails on the trailing \r.
                            val line = rawLine.trimEnd('\r')
                            outputLines.add(line)
                            logger.debug("Docker stdout [{}]: {}", outputLines.size, line.take(80))
                            request.stdoutCallback?.invoke(line)
                        }
                    }
                    logger.info("Docker stdout reader finished: {} lines read", outputLines.size)
                } catch (e: Exception) {
                    logger.warn("Docker stdout reader error: {}", e.message)
                }
            }.apply { isDaemon = true; start() }

            var stderr = ""
            val stderrThread = Thread {
                stderr = process.errorStream.bufferedReader().readText()
            }.apply { isDaemon = true; start() }

            // Wait with timeout
            val completed: Boolean
            val duration = measureTime {
                completed = process.waitFor(request.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }

            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(1000)
                stderrThread.join(1000)
                cleanupDirectory(tempBase)
                return ExecutionResult.TimedOut(
                    partialStderr = stderr.takeIf { it.isNotBlank() },
                    duration = duration,
                )
            }

            stdoutThread.join()
            stderrThread.join()

            val stdout = outputLines.joinToString("\n")
            val exitCode = process.exitValue()

            // Collect artifacts before cleanup
            val artifacts = collectAndCopyArtifacts(outputDir)

            cleanupDirectory(tempBase)

            return ExecutionResult.Completed(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                duration = duration,
                artifacts = artifacts,
            )
        } catch (e: Exception) {
            logger.error("Docker execution failed: {}", e.message, e)
            cleanupDirectory(tempBase)
            return ExecutionResult.Failed(
                error = "Docker execution failed: ${e.message}",
                cause = e,
                duration = started.elapsedNow(),
            )
        }
    }

    private fun buildDockerCommand(
        request: ExecutionRequest,
        inputDir: Path,
        outputDir: Path,
    ): List<String> {
        // When streaming stdout (stdoutCallback is set), allocate a pseudo-TTY
        // to force line-buffered output. Without this, Docker buffers stdout
        // and lines don't arrive until the process exits.
        // Note: -t merges stderr into stdout, so stderr will be empty when streaming.
        val command = if (request.stdoutCallback != null) {
            mutableListOf("docker", "run", "--rm", "-t")
        } else {
            mutableListOf("docker", "run", "--rm")
        }

        // Resource limits
        memoryLimit?.let { command.addAll(listOf("--memory", it)) }
        cpuLimit?.let { command.addAll(listOf("--cpus", it)) }

        // Network
        if (!networkEnabled) {
            command.addAll(listOf("--network", "none"))
        }

        // Read-only root filesystem
        if (readOnlyRootfs) {
            command.add("--read-only")
            // Add a tmpfs for /tmp since many programs need it
            command.addAll(listOf("--tmpfs", "/tmp:rw,noexec,nosuid,size=64m"))
        }

        // User
        user?.let { command.addAll(listOf("--user", it)) }

        // Working directory
        val effectiveWorkDir = workDir ?: "/workspace"
        command.addAll(listOf("--workdir", effectiveWorkDir))

        // Mount input/output directories
        command.addAll(listOf("-v", "${inputDir.absolutePathString()}:/input:ro"))
        command.addAll(listOf("-v", "${outputDir.absolutePathString()}:/output:rw"))

        // Mount working directory if specified
        request.workingDirectory?.let { wd ->
            command.addAll(listOf("-v", "${wd.absolutePathString()}:$effectiveWorkDir:rw"))
        }

        // Additional mounts
        for (mount in mounts) {
            command.addAll(listOf("-v", mount.toDockerArg()))
        }

        // Environment variables
        command.addAll(listOf("-e", "INPUT_DIR=/input"))
        command.addAll(listOf("-e", "OUTPUT_DIR=/output"))
        for ((key, value) in baseEnvironment) {
            command.addAll(listOf("-e", "$key=$value"))
        }
        for ((key, value) in request.environment) {
            command.addAll(listOf("-e", "$key=$value"))
        }

        // Image and command
        command.add(image)
        command.addAll(request.command)

        return command
    }

    private fun collectAndCopyArtifacts(outputDir: Path): List<ExecutionArtifact> {
        if (!Files.isDirectory(outputDir)) {
            return emptyList()
        }

        val artifactsDir = Files.createTempDirectory("docker-artifacts")

        return Files.list(outputDir)
            .filter { Files.isRegularFile(it) }
            .map { file ->
                val persistentPath = artifactsDir.resolve(file.fileName)
                Files.copy(file, persistentPath)
                ExecutionArtifact.File(
                    name = file.fileName.toString(),
                    path = persistentPath,
                    mimeType = ExecutionArtifact.inferMimeType(file.fileName.toString()),
                    sizeBytes = Files.size(persistentPath),
                )
            }
            .toList()
    }

    private fun cleanupDirectory(dir: Path) {
        try {
            dir.toFile().deleteRecursively()
        } catch (e: Exception) {
            logger.warn("Failed to cleanup directory {}: {}", dir, e.message)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DockerExecutor::class.java)

        /**
         * Default sandbox image with common tools installed.
         */
        const val DEFAULT_SANDBOX_IMAGE = "embabel/agent-sandbox:latest"

        /**
         * Check if Docker is available on this system.
         */
        fun isDockerAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("docker", "version")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Check if a Docker image exists locally.
         */
        fun imageExists(image: String): Boolean {
            return try {
                val process = ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Pull a Docker image if it doesn't exist locally.
         *
         * @return true if the image is available (already existed or was pulled)
         */
        fun ensureImage(image: String): Boolean {
            if (imageExists(image)) {
                return true
            }

            logger.info("Pulling Docker image: {}", image)
            return try {
                val process = ProcessBuilder("docker", "pull", image)
                    .inheritIO()
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                logger.error("Failed to pull image {}: {}", image, e.message)
                false
            }
        }

        /**
         * Create a maximally isolated executor.
         *
         * This configuration:
         * - Disables networking
         * - Limits memory to 256MB
         * - Limits CPU to 0.5 cores
         * - Mounts root filesystem as read-only
         */
        fun isolated(
            image: String = DEFAULT_SANDBOX_IMAGE,
        ) = DockerExecutor(
            image = image,
            networkEnabled = false,
            memoryLimit = "256m",
            cpuLimit = "0.5",
            readOnlyRootfs = true,
        )

        /**
         * Create an executor suitable for running Claude Code.
         *
         * Claude Code needs:
         * - Network access for API calls
         * - More memory for complex operations
         * - Read-write access to working directory
         */
        fun forClaudeCode(
            image: String = "embabel/claude-code-sandbox:latest",
        ) = DockerExecutor(
            image = image,
            networkEnabled = true,
            memoryLimit = "2g",
            cpuLimit = "2.0",
        )

        /**
         * Create an executor suitable for running Python scripts.
         */
        fun forPython(
            image: String = "python:3.11-slim",
            networkEnabled: Boolean = false,
        ) = DockerExecutor(
            image = image,
            networkEnabled = networkEnabled,
            memoryLimit = "512m",
            cpuLimit = "1.0",
        )
    }
}
