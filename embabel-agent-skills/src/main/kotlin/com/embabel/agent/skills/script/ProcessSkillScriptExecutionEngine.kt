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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Process-based script execution engine.
 *
 * Executes scripts as subprocesses using the appropriate interpreter.
 * This provides basic isolation through process boundaries but does NOT
 * provide strong sandboxing - scripts have access to the filesystem and
 * network as permitted by the OS user running the JVM.
 *
 * ## Security Considerations
 *
 * This engine is suitable for:
 * - Trusted scripts from known sources
 * - Development and testing environments
 * - Scenarios where OS-level user permissions provide adequate isolation
 *
 * For untrusted scripts, consider using [DockerSkillScriptExecutionEngine], which
 * provides stronger isolation.
 *
 * @param timeout maximum execution time before the process is killed
 * @param supportedLanguages languages this engine will execute (defaults to all)
 * @param interpreters map of language to interpreter command
 * @param environment environment variables to pass to scripts (always passed through)
 * @param inheritEnvironment whether to inherit the FULL host environment. Defaults to
 * true (scripts see the host env, including any secrets). Set false to restrict scripts
 * to [environmentAllowlist] (a safe subset, e.g. PATH/HOME) so they can find interpreters
 * without seeing host secrets (API keys, etc.).
 * @param environmentAllowlist host variables passed through when [inheritEnvironment] is
 * false (defaults to [SAFE_ENV_ALLOWLIST]); override to add or restrict variables
 * @param fileTools resolves and validates input file paths against a root, rejecting path
 * traversal and files outside the root (defaults to the current working directory)
 */
class ProcessSkillScriptExecutionEngine @JvmOverloads constructor(
    private val timeout: Duration = 30.seconds,
    private val supportedLanguages: Set<ScriptLanguage> = ScriptLanguage.entries.toSet(),
    private val interpreters: Map<ScriptLanguage, List<String>> = defaultInterpreters,
    private val environment: Map<String, String> = emptyMap(),
    private val inheritEnvironment: Boolean = true,
    private val environmentAllowlist: Set<String> = SAFE_ENV_ALLOWLIST,
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

    companion object {
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
        fun confinedTo(root: String): ProcessSkillScriptExecutionEngine =
            ProcessSkillScriptExecutionEngine(fileTools = FileTools.readWrite(root))

        /**
         * Environment variables passed through to scripts when the full host environment
         * is not inherited. Enough to locate and run interpreters, without leaking host
         * secrets (API keys, tokens, ...).
         */
        val SAFE_ENV_ALLOWLIST: Set<String> = setOf(
            "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TERM", "TZ",
            // Windows equivalents
            "SystemRoot", "SystemDrive", "TEMP", "TMP", "USERPROFILE", "PATHEXT",
        )

        /**
         * Default interpreters for each language.
         * The script path will be appended to this command.
         */
        val defaultInterpreters: Map<ScriptLanguage, List<String>> = mapOf(
            ScriptLanguage.PYTHON to listOf("python3"),
            ScriptLanguage.BASH to listOf("bash"),
            ScriptLanguage.JAVASCRIPT to listOf("node"),
            ScriptLanguage.KOTLIN_SCRIPT to listOf("kotlin"),
        )
    }

    override fun supportedLanguages(): Set<ScriptLanguage> = supportedLanguages

    override fun validate(script: SkillScript): ScriptExecutionResult.Denied? {
        // Check language support
        if (script.language !in supportedLanguages) {
            return ScriptExecutionResult.Denied(
                "Language ${script.language} is not enabled. " +
                    "Enabled languages: ${supportedLanguages.joinToString()}"
            )
        }

        // Check interpreter is configured
        if (script.language !in interpreters) {
            return ScriptExecutionResult.Denied(
                "No interpreter configured for ${script.language}"
            )
        }

        // Check script file exists
        val scriptFile = script.scriptPath.toFile()
        if (!scriptFile.exists()) {
            return ScriptExecutionResult.Denied(
                "Script file does not exist: ${script.scriptPath}"
            )
        }

        if (!scriptFile.isFile) {
            return ScriptExecutionResult.Denied(
                "Script path is not a file: ${script.scriptPath}"
            )
        }

        return null
    }

    override fun execute(
        script: SkillScript,
        args: List<String>,
        stdin: String?,
        inputFiles: List<Path>,
    ): ScriptExecutionResult {
        // Validate first
        validate(script)?.let { return it }

        // Resolve and validate input files, confining them to the fileTools root
        // (rejects path traversal and files outside the root, like the Docker engine).
        val resolvedInputFiles = try {
            inputFiles.map { resolveInputFile(it) }
        } catch (e: SecurityException) {
            return ScriptExecutionResult.Denied("Path traversal not allowed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ScriptExecutionResult.Denied("Input file error: ${e.message}")
        }

        val interpreter = interpreters[script.language]!!
        val command = interpreter + listOf(script.scriptPath.toAbsolutePath().toString()) + args

        // Create input directory and copy input files
        val inputDir = Files.createTempDirectory("script-input-")
        logger.debug("Created input directory: {}", inputDir)

        // Create output directory for artifacts
        val outputDir = Files.createTempDirectory("script-output-")
        logger.debug("Created output directory: {}", outputDir)

        return try {
            // Copy input files to input directory (unique names so same-named inputs
            // from different folders don't collide).
            for (inputFile in resolvedInputFiles) {
                val targetPath = copyIntoUniqueName(inputFile, inputDir)
                logger.debug("Copied input file {} to {}", inputFile, targetPath)
            }

            logger.debug("Executing script: {} with args: {}, {} input files", script.fileName, args, inputFiles.size)

            val (result, duration) = measureTimedValue {
                executeProcess(
                    command = command,
                    workingDir = script.basePath.toFile(),
                    stdin = stdin,
                    inputDir = inputDir,
                    outputDir = outputDir,
                )
            }

            when (result) {
                is ProcessResult.Completed -> {
                    val artifacts = collectArtifacts(outputDir)
                    logger.debug(
                        "Script {} completed with exit code {} in {}, produced {} artifacts",
                        script.fileName,
                        result.exitCode,
                        duration,
                        artifacts.size
                    )
                    // Artifacts are copied to the artifacts root, so both temp dirs
                    // can be cleaned up now.
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ScriptExecutionResult.Success(
                        stdout = result.stdout,
                        stderr = result.stderr,
                        exitCode = result.exitCode,
                        duration = duration,
                        artifacts = artifacts,
                    )
                }

                is ProcessResult.TimedOut -> {
                    logger.warn("Script {} timed out after {}", script.fileName, timeout)
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ScriptExecutionResult.Failure(
                        error = "Script execution timed out after $timeout",
                        stderr = result.stderr,
                        timedOut = true,
                        duration = duration,
                    )
                }

                is ProcessResult.Failed -> {
                    logger.error("Script {} failed to start: {}", script.fileName, result.error)
                    cleanupDirectory(inputDir)
                    cleanupDirectory(outputDir)
                    ScriptExecutionResult.Failure(
                        error = result.error,
                        duration = duration,
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error executing script {}: {}", script.fileName, e.message, e)
            cleanupDirectory(inputDir)
            cleanupDirectory(outputDir)
            ScriptExecutionResult.Failure(
                error = "Unexpected error: ${e.message}",
            )
        }
    }

    /**
     * Collect artifacts from the output directory.
     */
    private fun collectArtifacts(outputDir: Path): List<ScriptArtifact> {
        if (!Files.isDirectory(outputDir)) {
            return emptyList()
        }

        val files = Files.list(outputDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }
        if (files.isEmpty()) {
            return emptyList()
        }

        // Copy artifacts into the engine's artifacts root so a subsequent skill can
        // reuse them as input (they would be rejected from a system temp dir).
        val runArtifacts = Files.createTempDirectory(artifactsRoot, "run-")
        return files
            .map { file ->
                val dest = runArtifacts.resolve(file.fileName)
                Files.copy(file, dest)
                ScriptArtifact(
                    name = file.fileName.toString(),
                    path = dest.toAbsolutePath(),
                    mimeType = ScriptArtifact.inferMimeType(file.fileName.toString()),
                    sizeBytes = Files.size(dest),
                )
            }
            .sortedBy { it.name }
    }

    /**
     * Clean up a temporary directory.
     */
    private fun cleanupDirectory(dir: Path) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            logger.warn("Failed to clean up directory {}: {}", dir, e.message)
        }
    }

    private fun executeProcess(
        command: List<String>,
        workingDir: File,
        stdin: String?,
        inputDir: Path,
        outputDir: Path,
    ): ProcessResult {
        return try {
            val processBuilder = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)

            // Set up environment
            val env = processBuilder.environment()
            if (!inheritEnvironment) {
                // Secure default: don't hand the full host environment (API keys, other
                // secrets) to scripts. Keep only a safe allowlist — enough to locate and
                // run interpreters.
                val allowed = env.filterKeys { it in environmentAllowlist }
                env.clear()
                env.putAll(allowed)
            }
            env.putAll(environment)

            // Add INPUT_DIR for scripts to read input files
            env["INPUT_DIR"] = inputDir.toAbsolutePath().toString()

            // Add OUTPUT_DIR for scripts to write artifacts
            env["OUTPUT_DIR"] = outputDir.toAbsolutePath().toString()

            val io = processBuilder.start().pumpIo(stdin, timeout)

            if (io.timedOut) {
                ProcessResult.TimedOut(io.stderr)
            } else {
                ProcessResult.Completed(
                    exitCode = io.exitCode!!,
                    stdout = io.stdout,
                    stderr = io.stderr,
                )
            }
        } catch (e: Exception) {
            ProcessResult.Failed(e.message ?: "Unknown error starting process")
        }
    }

    /**
     * Internal result type for process execution.
     */
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
