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
import com.embabel.agent.sandbox.SandboxedExecutor
import com.embabel.agent.sandbox.docker.DockerExecutor
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.TimeSource

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
        val started = TimeSource.Monotonic.markNow()
        // Validate input files exist
        for (inputFile in request.inputFiles) {
            if (!Files.exists(inputFile)) {
                return ExecutionResult.Denied("Input file does not exist: $inputFile", started.elapsedNow())
            }
            if (!Files.isRegularFile(inputFile)) {
                return ExecutionResult.Denied("Input path is not a file: $inputFile", started.elapsedNow())
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

            val processBuilder = ProcessBuilder(request.command)
            request.workingDirectory?.let { processBuilder.directory(it.toFile()) }
            val env = processBuilder.environment()
            if (!inheritEnvironment) env.clear()
            env.putAll(baseEnvironment)
            env.putAll(request.environment)
            env["INPUT_DIR"] = inputDir.toAbsolutePath().toString()
            env["OUTPUT_DIR"] = outputDir.toAbsolutePath().toString()
            when (val result = ProcessExecution.run(processBuilder, request)) {
                is ExecutionResult.Completed -> result.copy(
                    artifacts = collectArtifacts(outputDir), duration = started.elapsedNow(),
                )
                is ExecutionResult.TimedOut -> {
                    cleanupDirectory(outputDir)
                    result.copy(duration = started.elapsedNow())
                }
                is ExecutionResult.Failed -> {
                    cleanupDirectory(outputDir)
                    result.copy(duration = started.elapsedNow())
                }
                is ExecutionResult.Denied -> result
            }
        } catch (e: Exception) {
            logger.error("Unexpected error executing command: {}", e.message, e)
            cleanupDirectory(outputDir)
            ExecutionResult.Failed(
                error = "Unexpected error: ${e.message}",
                cause = e,
                duration = started.elapsedNow(),
            )
        } finally {
            cleanupDirectory(inputDir)
        }
    }

    private fun collectArtifacts(outputDir: Path): List<ExecutionArtifact> {
        if (!Files.isDirectory(outputDir)) {
            return emptyList()
        }

        return Files.list(outputDir).use { files -> files
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
    }

    private fun cleanupDirectory(dir: Path) {
        try {
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (e: Exception) {
            logger.warn("Failed to clean up directory {}: {}", dir, e.message)
        }
    }

}
