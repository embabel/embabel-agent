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

import com.embabel.agent.sandbox.ExecutionArtifact
import com.embabel.agent.sandbox.ExecutionRequest
import com.embabel.agent.sandbox.ExecutionResult
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxedExecutor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Process-based executor that runs commands as subprocesses.
 *
 * This provides basic isolation through process boundaries but does NOT
 * provide strong sandboxing - commands have access to the filesystem and
 * network as permitted by the OS user running the JVM.
 *
 * ## Security Considerations
 *
 * This executor is suitable for:
 * - Trusted commands from known sources
 * - Development and testing environments
 * - Scenarios where OS-level user permissions provide adequate isolation
 *
 * For untrusted commands, use [DockerExecutor] instead.
 *
 * ## Example
 *
 * ```kotlin
 * val executor = ProcessExecutor()
 *
 * val result = executor.execute(ExecutionRequest(
 *     command = listOf("python3", "script.py"),
 *     timeout = 30.seconds,
 * ))
 *
 * when (result) {
 *     is ExecutionResult.Completed -> println("Output: ${result.stdout}")
 *     is ExecutionResult.TimedOut -> println("Timed out!")
 *     is ExecutionResult.Failed -> println("Failed: ${result.error}")
 *     is ExecutionResult.Denied -> println("Denied: ${result.reason}")
 * }
 * ```
 *
 * @param baseEnvironment base environment variables for all executions
 * @param inheritEnvironment whether to inherit the current process environment
 */
class ProcessExecutor(
    private val baseEnvironment: Map<String, String> = emptyMap(),
    private val inheritEnvironment: Boolean = true,
) : SandboxedExecutor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun checkAvailability(): String? = null // Always available

    override fun execute(request: ExecutionRequest): ExecutionResult {
        // Validate input files exist
        for (inputFile in request.inputFiles) {
            if (!Files.exists(inputFile)) {
                return ExecutionResult.Denied("Input file does not exist: $inputFile")
            }
            if (!Files.isRegularFile(inputFile)) {
                return ExecutionResult.Denied("Input path is not a file: $inputFile")
            }
        }

        // Create I/O directories
        val inputDir = Files.createTempDirectory("exec-input-")
        val outputDir = Files.createTempDirectory("exec-output-")

        return try {
            // Copy input files
            for (inputFile in request.inputFiles) {
                val targetPath = inputDir.resolve(inputFile.fileName)
                Files.copy(inputFile, targetPath)
                logger.debug("Copied input file {} to {}", inputFile, targetPath)
            }

            logger.debug("Executing command: {}", request.command.joinToString(" "))

            val (result, duration) = measureTimedValue {
                executeProcess(request, inputDir, outputDir)
            }

            when (result) {
                is ProcessResult.Completed -> {
                    val artifacts = collectArtifacts(outputDir)
                    cleanupDirectory(inputDir)
                    ExecutionResult.Completed(
                        exitCode = result.exitCode,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        duration = duration,
                        artifacts = artifacts,
                    )
                }

                is ProcessResult.TimedOut -> {
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ExecutionResult.TimedOut(
                        partialStderr = result.stderr,
                        duration = duration,
                    )
                }

                is ProcessResult.Failed -> {
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ExecutionResult.Failed(error = result.error)
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error executing command: {}", e.message, e)
            cleanupDirectory(inputDir)
            cleanupDirectory(outputDir)
            ExecutionResult.Failed(
                error = "Unexpected error: ${e.message}",
                cause = e,
            )
        }
    }

    private fun executeProcess(
        request: ExecutionRequest,
        inputDir: Path,
        outputDir: Path,
    ): ProcessResult {
        return try {
            val processBuilder = ProcessBuilder(request.command)
                .redirectErrorStream(false)

            request.workingDirectory?.let { processBuilder.directory(it.toFile()) }

            // Set up environment
            val env = processBuilder.environment()
            if (!inheritEnvironment) {
                env.clear()
            }
            env.putAll(baseEnvironment)
            env.putAll(request.environment)
            env["INPUT_DIR"] = inputDir.toAbsolutePath().toString()
            env["OUTPUT_DIR"] = outputDir.toAbsolutePath().toString()

            val process = processBuilder.start()

            // Write stdin if provided
            if (request.stdin != null) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(request.stdin)
                }
            } else {
                process.outputStream.close()
            }

            // Read stdout and stderr concurrently
            var stdout = ""
            var stderr = ""

            val stdoutThread = Thread {
                stdout = if (request.captureOutput) {
                    process.inputStream.bufferedReader().readText()
                } else {
                    process.inputStream.bufferedReader().use { it.skip(Long.MAX_VALUE) }
                    ""
                }
            }.apply { start() }

            val stderrThread = Thread {
                stderr = if (request.captureOutput) {
                    process.errorStream.bufferedReader().readText()
                } else {
                    process.errorStream.bufferedReader().use { it.skip(Long.MAX_VALUE) }
                    ""
                }
            }.apply { start() }

            // Wait with timeout
            val completed = process.waitFor(request.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(1000)
                stderrThread.join(1000)
                return ProcessResult.TimedOut(stderr)
            }

            stdoutThread.join()
            stderrThread.join()

            ProcessResult.Completed(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
            )
        } catch (e: Exception) {
            ProcessResult.Failed(e.message ?: "Unknown error starting process")
        }
    }

    private fun collectArtifacts(outputDir: Path): List<ExecutionArtifact> {
        if (!Files.isDirectory(outputDir)) {
            return emptyList()
        }

        return Files.list(outputDir)
            .filter { Files.isRegularFile(it) }
            .map { file ->
                ExecutionArtifact.File(
                    name = file.fileName.toString(),
                    path = file.toAbsolutePath(),
                    mimeType = ExecutionArtifact.inferMimeType(file.fileName.toString()),
                    sizeBytes = Files.size(file),
                )
            }
            .toList()
            .sortedBy { it.name }
    }

    private fun cleanupDirectory(dir: Path) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            logger.warn("Failed to clean up directory {}: {}", dir, e.message)
        }
    }

    private sealed class ProcessResult {
        data class Completed(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
        ) : ProcessResult()

        data class TimedOut(
            val stderr: String,
        ) : ProcessResult()

        data class Failed(
            val error: String,
        ) : ProcessResult()
    }
}
