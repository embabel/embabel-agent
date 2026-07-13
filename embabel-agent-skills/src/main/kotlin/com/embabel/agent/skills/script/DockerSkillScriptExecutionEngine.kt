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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Script execution engine that runs scripts inside a Docker container for sandboxed execution.
 *
 * This provides isolation from the host system while still allowing scripts to:
 * - Read input files via INPUT_DIR
 * - Write output artifacts via OUTPUT_DIR
 * - Access network (can be disabled)
 *
 * Self-contained implementation — no dependency on the embabel-agent-sandbox module.
 * All Docker CLI interaction is handled directly via [ProcessBuilder].
 *
 * @param image the Docker image to use for execution
 * @param timeout maximum execution time before killing the container
 * @param supportedLanguages which script languages this engine supports
 * @param networkEnabled whether to allow network access from the container
 * @param memoryLimit memory limit for the container (e.g., "512m", "1g")
 * @param cpuLimit CPU limit for the container (e.g., "1.0" for 1 CPU)
 * @param environment additional environment variables to pass to the container
 * @param workDir working directory inside the container
 * @param user user to run as inside the container (default: "agent" for the embabel image)
 * @param fileTools FileReadTools for resolving input file paths securely.
 *                  Input paths are resolved relative to the fileTools root with path traversal protection.
 *                  Defaults to current working directory.
 */
class DockerSkillScriptExecutionEngine @JvmOverloads constructor(
    private val image: String = DEFAULT_IMAGE,
    private val timeout: Duration = 60.seconds,
    private val supportedLanguages: Set<ScriptLanguage> = ScriptLanguage.entries.toSet(),
    private val networkEnabled: Boolean = true,
    private val memoryLimit: String? = "512m",
    private val cpuLimit: String? = "1.0",
    private val environment: Map<String, String> = emptyMap(),
    private val workDir: String = "/home/agent/workspace",
    private val user: String? = "agent",
    private val fileTools: FileTools = FileTools.readWrite(System.getProperty("user.dir")),
) : SkillScriptExecutionEngine {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Dedicated, engine-owned directory where produced artifacts are kept. It is a
    // generated temp dir (never the user's working directory), and the engine trusts
    // it as an input source so a previous skill's artifact can be reused by the next.
    private val artifactsRoot: Path = Files.createTempDirectory("skill-artifacts-")
    private val artifactsFileTools: FileTools = FileTools.readWrite(artifactsRoot.toString())

    /**
     * Resolve an input file against the two allowed roots, in order:
     *
     *  1. the user root ([fileTools]) — where the user's own files live;
     *  2. the engine's artifacts root ([artifactsFileTools]) — where outputs produced by a
     *     previous run are kept, so a later skill can reuse them as input.
     *
     * Both roots reject path traversal (any path escaping the root).
     *
     * We fall back to root 2 ONLY when root 1 throws [SecurityException]. This relies on
     * [FileTools.resolveAndValidateFile] reporting two failures distinctly:
     *
     *  - [SecurityException]        -> the path is *outside* the root. That means "not a user
     *                                  file", so it is worth retrying against the artifacts root.
     *  - [IllegalArgumentException] -> the path is *inside* the root but the file is missing
     *                                  ("file not found"). That is a genuine error, NOT a
     *                                  wrong-root signal, so we let it propagate unchanged.
     *
     * Consequence: a mere typo on a user-dir path stays a clear "file not found" instead of
     * silently falling through to the artifacts root. A path outside both roots is rejected.
     */
    private fun resolveInputFile(path: Path): Path =
        try {
            fileTools.resolveAndValidateFile(path.toString())
        } catch (e: SecurityException) {
            artifactsFileTools.resolveAndValidateFile(path.toString())
        }

    override fun supportedLanguages(): Set<ScriptLanguage> = supportedLanguages

    override fun validate(script: SkillScript): ScriptExecutionResult.Denied? {
        if (script.language !in supportedLanguages) {
            return ScriptExecutionResult.Denied(
                "Script language ${script.language} is not enabled. Enabled languages: $supportedLanguages"
            )
        }

        if (!script.scriptPath.exists()) {
            return ScriptExecutionResult.Denied(
                "Script file does not exist: ${script.scriptPath}"
            )
        }

        checkDockerAvailability()?.let { reason ->
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

        // Resolve and validate input file paths securely via FileTools
        val resolvedInputFiles = mutableListOf<Path>()
        for (inputFile in inputFiles) {
            try {
                resolvedInputFiles.add(resolveInputFile(inputFile))
            } catch (e: SecurityException) {
                return ScriptExecutionResult.Denied("Path traversal not allowed: $inputFile")
            } catch (e: IllegalArgumentException) {
                return ScriptExecutionResult.Denied("Input file error: ${e.message}")
            }
        }

        // Temp directories: script mount, input files, output artifacts
        val tempBase = Files.createTempDirectory("skills-docker-")
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
            val dockerCommand = buildDockerCommand(command, scriptDir, inputDir, outputDir)

            logger.debug("Executing docker command: {}", dockerCommand.joinToString(" "))

            return runDockerProcess(dockerCommand, stdin, outputDir, script.fileName)
        } finally {
            try {
                tempBase.toFile().deleteRecursively()
            } catch (e: Exception) {
                logger.warn("Failed to cleanup temp directory: {}", tempBase, e)
            }
        }
    }

    // --- Docker command construction ---

    private fun buildDockerCommand(
        command: List<String>,
        scriptDir: Path,
        inputDir: Path,
        outputDir: Path,
    ): List<String> {
        return buildList {
            // -i keeps the container's stdin open so a provided stdin is actually
            // delivered; without it Docker attaches stdin to /dev/null and drops it.
            add("docker"); add("run"); add("--rm"); add("-i")

            memoryLimit?.let { addAll(listOf("--memory", it)) }
            cpuLimit?.let { addAll(listOf("--cpus", it)) }

            if (!networkEnabled) addAll(listOf("--network", "none"))

            user?.let { addAll(listOf("--user", it)) }
            addAll(listOf("--workdir", workDir))

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
            addAll(command)
        }
    }

    // --- Process execution ---

    private fun runDockerProcess(
        dockerCommand: List<String>,
        stdin: String?,
        outputDir: Path,
        scriptFileName: String,
    ): ScriptExecutionResult {
        val process = ProcessBuilder(dockerCommand)
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
        val artifacts = collectArtifacts(outputDir)

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

    // --- Artifact collection ---

    private fun collectArtifacts(outputDir: Path): List<ScriptArtifact> {
        if (!Files.isDirectory(outputDir)) return emptyList()
        // Copy artifacts out of outputDir (before the temp tree is deleted) into the
        // engine's artifacts root, so a subsequent skill can reuse them as input.
        val artifactsStaging = Files.createTempDirectory(artifactsRoot, "run-")
        return Files.list(outputDir).use { files ->
            files
                .filter { Files.isRegularFile(it) }
                .map { file ->
                    val dest = artifactsStaging.resolve(file.fileName)
                    Files.copy(file, dest)
                    ScriptArtifact(
                        name = file.fileName.toString(),
                        path = dest.toAbsolutePath(),
                        mimeType = ScriptArtifact.inferMimeType(file.fileName.toString()),
                        sizeBytes = Files.size(dest),
                    )
                }
                .toList()
                .sortedBy { it.name }
        }
    }

    // --- Interpreter selection ---

    private fun interpreterFor(language: ScriptLanguage): List<String> = when (language) {
        ScriptLanguage.PYTHON -> listOf("python3")
        ScriptLanguage.BASH -> listOf("bash")
        ScriptLanguage.JAVASCRIPT -> listOf("node")
        ScriptLanguage.KOTLIN_SCRIPT -> listOf("kotlin")
    }

    // --- Docker availability (instance-level, returns denial reason or null) ---

    private fun checkDockerAvailability(): String? = try {
        val process = ProcessBuilder("docker", "version")
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        when {
            !completed -> { process.destroyForcibly(); "Docker availability check timed out" }
            process.exitValue() != 0 -> "Docker returned an error; is the Docker daemon running?"
            else -> null
        }
    } catch (e: Exception) {
        "Docker is not available: ${e.message}"
    }

    companion object {
        /**
         * Default Docker image for script execution.
         * Build from the Dockerfile in embabel-agent-skills/docker:
         * ```
         * docker build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
         * ```
         */
        const val DEFAULT_IMAGE = "embabel/agent-sandbox:latest"

        private val logger = LoggerFactory.getLogger(DockerSkillScriptExecutionEngine::class.java)

        /**
         * Create an engine confined to [root]: input files are resolved against [root]
         * and anything outside it (absolute paths, `..` traversal) is rejected.
         *
         * Java-friendly entry point. Kotlin callers can pass `fileTools` directly via the
         * constructor's named argument; Java cannot reach it (the leading `Duration`
         * value-class parameter makes the deeper constructor overloads synthetic), so this
         * factory exposes the one knob a per-request/multi-tenant caller needs.
         */
        @JvmStatic
        fun confinedTo(root: String): DockerSkillScriptExecutionEngine =
            DockerSkillScriptExecutionEngine(fileTools = FileTools.readWrite(root))

        /** Check if Docker is available on this system. */
        fun isDockerAvailable(): Boolean = try {
            val process = ProcessBuilder("docker", "version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }

        /** Check if a Docker image exists locally. */
        fun imageExists(image: String): Boolean = try {
            val process = ProcessBuilder("docker", "image", "inspect", image)
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }

        /**
         * Ensure the default sandbox image exists, logging build instructions if not.
         *
         * @return true if the image is ready to use
         */
        fun ensureDefaultImageExists(): Boolean {
            if (!isDockerAvailable()) {
                logger.error("Docker is not available. Please install Docker to use DockerSkillScriptExecutionEngine.")
                return false
            }
            if (!imageExists(DEFAULT_IMAGE)) {
                logger.warn(
                    """
                    |Docker image '$DEFAULT_IMAGE' not found.
                    |
                    |Build it from the embabel-agent-skills module:
                    |  docker build -t $DEFAULT_IMAGE ./embabel-agent-skills/docker
                    |
                    |Or specify a different image:
                    |  DockerSkillScriptExecutionEngine(image = "your-image:tag")
                    """.trimMargin()
                )
                return false
            }
            return true
        }

        /** Create an engine with Python-only support. */
        fun pythonOnly(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 60.seconds,
        ) = DockerSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            supportedLanguages = setOf(ScriptLanguage.PYTHON),
        )

        /** Create an engine with maximum isolation (no network, reduced resources). */
        fun isolated(
            image: String = DEFAULT_IMAGE,
            timeout: Duration = 30.seconds,
        ) = DockerSkillScriptExecutionEngine(
            image = image,
            timeout = timeout,
            networkEnabled = false,
            memoryLimit = "256m",
            cpuLimit = "0.5",
        )
    }
}
