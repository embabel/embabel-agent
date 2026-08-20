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
package com.embabel.agent.skills.script

import com.embabel.agent.tools.file.FileTools
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Common base for OCI-container-based script execution engines (Docker, Podman, …).
 *
 * Subclasses only need to supply the container-runtime-specific differences: the CLI
 * command name, display name, temp-dir prefix, the availability-error hint, and whether
 * the runtime supports `--workdir` directly (Docker does; Podman does not auto-create the
 * working directory, so it falls back to a `/bin/sh` wrapper that `mkdir -p && cd`s first).
 *
 * All staging, I/O pumping, timeout handling, artifact collection, and input confinement
 * logic lives here and is shared unchanged.
 *
 * @param image the OCI image to use for execution
 * @param timeout maximum execution time before killing the container
 * @param supportedLanguages which script languages this engine supports
 * @param networkEnabled whether to allow network access from the container
 * @param memoryLimit memory limit for the container (e.g., [MemorySize.megabytes] (512)), passed to `--memory`
 * @param cpuLimit CPU limit for the container as a number of cores (e.g., [CpuLimit.cores] (1)), passed to `--cpus`
 * @param environment additional environment variables to pass to the container
 * @param workDir working directory inside the container
 * @param user user to run as inside the container
 * @param fileTools FileTools for resolving input file paths securely.
 */
abstract class AbstractContainerSkillScriptExecutionEngine(
    protected val image: String,
    protected val timeout: Duration,
    protected val supportedLanguages: Set<ScriptLanguage>,
    protected val networkEnabled: Boolean,
    protected val memoryLimit: MemorySize?,
    protected val cpuLimit: CpuLimit?,
    protected val environment: Map<String, String>,
    protected val workDir: String,
    protected val user: String?,
    protected val fileTools: FileTools,
) : SkillScriptExecutionEngine {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Confines input files to the user root (rejecting path traversal) and stages produced
    // artifacts so a later skill can reuse them as input.
    private val inputs = ConfinedInputResolver(fileTools)

    // --- Runtime-specific hooks (subclasses implement) ---

    /** The CLI binary, e.g. `"docker"` or `"podman"`. */
    protected abstract val containerCommand: String

    /** Display name used in log/error messages, e.g. `"Docker"` or `"Podman"`. */
    protected abstract val containerName: String

    /** Prefix for the per-run temp directory, e.g. `"skills-docker-"`. */
    protected abstract val tempDirPrefix: String

    /**
     * Hint shown when `containerCommand version` exits non-zero, e.g.
     * `"is the Docker daemon running?"` or `"is Podman installed and functional?"`.
     */
    protected abstract val daemonErrorMessage: String

    /**
     * Whether the runtime accepts `--workdir` directly. `true` (Docker) emits the flag;
     * `false` (Podman) wraps the command in `/bin/sh` to `mkdir -p && cd` first, because
     * Podman does not auto-create a missing working directory.
     */
    protected open val useWorkdir: Boolean = true

    // --- SkillScriptExecutionEngine ---

    override fun supportedLanguages(): Set<ScriptLanguage> = supportedLanguages

    override fun validate(script: SkillScript): ScriptExecutionResult.Denied? {
        if (script.language !in supportedLanguages) {
            return ScriptExecutionResult.Denied(
                "Script language ${script.language} is not enabled. Enabled languages: $supportedLanguages"
            )
        }

        if (!script.scriptPath.exists()) {
            return ScriptExecutionResult.Denied("Script file does not exist: ${script.scriptPath}")
        }

        checkContainerAvailability()?.let { reason ->
            return ScriptExecutionResult.Denied(reason)
        }

        return null
    }

    override fun execute(
        script: SkillScript,
        args: List<String>,
        stdin: String?,
        inputFiles: List<Path>,
    ): ScriptExecutionResult {
        validate(script)?.let { return it }

        // Resolve and validate input files, confining them to the user root
        // (rejects path traversal and files outside the root).
        val resolvedInputFiles = try {
            inputFiles.map { inputs.resolve(it) }
        } catch (e: SecurityException) {
            return ScriptExecutionResult.Denied("Path traversal not allowed: ${e.message}")
        } catch (e: Exception) {
            return ScriptExecutionResult.Denied("Input file error: ${e.message}")
        }

        // Temp directories: script mount, input files, output artifacts
        val tempBase = Files.createTempDirectory(tempDirPrefix)
        val scriptDir = tempBase.resolve("script").also { Files.createDirectories(it) }
        val inputDir = tempBase.resolve("input").also { Files.createDirectories(it) }
        val outputDir = tempBase.resolve("output").also { Files.createDirectories(it) }

        try {
            // Stage the script file into its own mount directory
            Files.copy(script.scriptPath, scriptDir.resolve(script.fileName))

            // Stage input files (unique names so same-named inputs from different
            // folders don't collide).
            for (inputFile in resolvedInputFiles) {
                copyIntoUniqueName(inputFile, inputDir)
            }

            val interpreter = interpreterFor(script.language)
            val command = interpreter + listOf("/script/${script.fileName}") + args
            val containerCmd = buildContainerCommand(command, scriptDir, inputDir, outputDir)

            logger.debug("Executing {} command: {}", containerCommand, containerCmd.joinToString(" "))

            return runContainerProcess(containerCmd, stdin, outputDir, script.fileName)
        } finally {
            try {
                tempBase.toFile().deleteRecursively()
            } catch (e: Exception) {
                logger.warn("Failed to cleanup temp directory: ${tempBase}, error:${e.message}")
            }
        }
    }

    // --- Container command construction ---

    private fun buildContainerCommand(
        command: List<String>,
        scriptDir: Path,
        inputDir: Path,
        outputDir: Path,
    ): List<String> {
        return buildList {
            // -i keeps the container's stdin open so a provided stdin is actually
            // delivered; without it the runtime attaches stdin to /dev/null and drops it.
            add(containerCommand); add("run"); add("--rm"); add("-i")

            memoryLimit?.let { addAll(listOf("--memory", it.render())) }
            cpuLimit?.let { addAll(listOf("--cpus", it.render())) }

            if (!networkEnabled) {
                addAll(listOf("--network", "none"))
            }

            user?.let { addAll(listOf("--user", it)) }

            if (useWorkdir) {
                addAll(listOf("--workdir", workDir))
            }
            // When useWorkdir is false, the workdir is handled by the shell wrapper
            // appended after the image (Podman path).

            // Script, input, and output mounts
            addAll(listOf("-v", "${scriptDir.absolutePathString()}:/script:ro"))
            addAll(listOf("-v", "${inputDir.absolutePathString()}:/input:ro"))
            addAll(listOf("-v", "${outputDir.absolutePathString()}:/output:rw"))

            // Environment
            for ((key, value) in environment) {
                if (key != "INPUT_DIR" && key != "OUTPUT_DIR") {
                    addAll(listOf("-e", "$key=$value"))
                }
            }
            addAll(listOf("-e", "INPUT_DIR=/input"))
            addAll(listOf("-e", "OUTPUT_DIR=/output"))

            add(image)

            if (!useWorkdir) {
                // Shell wrapper: ensure workdir exists (Podman won't create it), cd into it,
                // then exec the real command so signals / exit code pass through cleanly.
                addAll(
                    listOf(
                        "/bin/sh",
                        "-c",
                        $$"mkdir -p \"$$workDir\" && cd \"$$workDir\" && exec \"$@\"", "--"
                    )
                )
            }
            addAll(command)
        }
    }

    // --- Process execution ---

    private fun runContainerProcess(
        containerCommand: List<String>,
        stdin: String?,
        outputDir: Path,
        scriptFileName: String,
    ): ScriptExecutionResult {
        val process = ProcessBuilder(containerCommand)
            .redirectErrorStream(false)
            .start()

        val (io, duration) = measureTimedValue { process.pumpIo(stdin, timeout) }

        if (io.timedOut) {
            logger.warn("Script {} timed out after {}", scriptFileName, timeout)
            return ScriptExecutionResult.Failure(
                error = "Script execution timed out after $timeout",
                stderr = io.stderr.takeIf { it.isNotBlank() },
                timedOut = true,
                duration = duration,
            )
        }

        val exitCode = io.exitCode!!
        val artifacts = inputs.stageArtifacts(outputDir)

        logger.debug(
            "Script {} completed: exit={}, duration={}, artifacts={}",
            scriptFileName, exitCode, duration, artifacts.size,
        )

        return ScriptExecutionResult.Success(
            stdout = io.stdout,
            stderr = io.stderr,
            exitCode = exitCode,
            duration = duration,
            artifacts = artifacts,
        )
    }

    // --- Interpreter selection ---

    private fun interpreterFor(language: ScriptLanguage): List<String> = when (language) {
        ScriptLanguage.PYTHON -> listOf("python3")
        ScriptLanguage.BASH -> listOf("bash")
        ScriptLanguage.JAVASCRIPT -> listOf("node")
        ScriptLanguage.KOTLIN_SCRIPT -> listOf("kotlin")
    }

    // --- Container availability (instance-level, returns denial reason or null) ---

    private fun checkContainerAvailability(): String? = try {
        val process = ProcessBuilder(containerCommand, "version")
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        when {
            !completed -> {
                process.destroyForcibly(); "$containerName availability check timed out"
            }

            process.exitValue() != 0 -> "$containerName returned an error; $daemonErrorMessage"
            else -> null
        }
    } catch (e: Exception) {
        "$containerName is not available: ${e.message}"
    }

    companion object {
        /**
         * Default OCI image for script execution.
         * Build from the Dockerfile in embabel-agent-skills/docker:
         * ```
         * docker build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
         * # or, with Podman:
         * podman build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
         * ```
         */
        const val DEFAULT_IMAGE = "embabel/agent-sandbox:latest"

        private val logger = LoggerFactory.getLogger(AbstractContainerSkillScriptExecutionEngine::class.java)

        /** Run `[containerCommand] <args>`, returning true if it exits 0 within 5 seconds. */
        fun commandSucceeds(containerCommand: String, vararg args: String): Boolean = try {
            val process = ProcessBuilder(listOf(containerCommand, *args))
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn("containerCommand: $containerCommand failed. error: ${e.message}")
            false
        }

        /** Check if the given container runtime is available on this system. */
        fun isAvailable(containerCommand: String): Boolean = commandSucceeds(containerCommand, "version")

        /** Check if an OCI image exists locally for the given runtime. */
        fun imageExists(containerCommand: String, image: String): Boolean =
            commandSucceeds(containerCommand, "image", "inspect", image)

        /**
         * Ensure the default sandbox image exists, logging build instructions if not.
         *
         * @param containerCommand the runtime CLI, e.g. "docker" or "podman"
         * @param containerName display name for log messages, e.g. "Docker"
         * @param buildInstructions the warning text to log when the image is missing
         * @return true if the image is ready to use
         */
        fun ensureDefaultImageExists(
            containerCommand: String,
            containerName: String,
            buildInstructions: String,
        ): Boolean {
            if (!isAvailable(containerCommand)) {
                logger.error("$containerName is not available. Please install $containerName to use this engine.")
                return false
            }
            if (!imageExists(containerCommand, DEFAULT_IMAGE)) {
                logger.warn(buildInstructions)
                return false
            }
            return true
        }
    }
}
