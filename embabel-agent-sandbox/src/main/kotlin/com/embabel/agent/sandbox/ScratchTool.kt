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
package com.embabel.agent.sandbox

import com.embabel.agent.api.tool.Tool
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * A [Tool] that exposes a persistent [SandboxSession] to an LLM as a bash-like
 * REPL. The session stays alive between calls so the LLM can build up state
 * (install packages, create files, run scripts) across multiple tool invocations.
 *
 * The tool owns its session: the session is created lazily on first [call] via
 * [sessionManager] and destroyed when [close] is invoked. All container lifecycle
 * (create, exec, pause/resume, destroy) is delegated to the [SandboxSession]
 * abstraction — this class does not call `docker` directly.
 *
 * ## Example
 *
 * ```kotlin
 * val scratch = ScratchTool(
 *     sessionManager = DockerSandboxSessionManager(),
 *     config = SandboxConfig(enabled = true, image = ScratchTool.DEFAULT_IMAGE),
 *     owner = "alice",
 * )
 *
 * promptRunner.withTool(scratch)
 *     .generateText("Install numpy and compute the eigenvalues of [[1,2],[3,4]]")
 *
 * scratch.close() // destroys the underlying session
 * ```
 *
 * From Java, every leading subset of the parameters is a constructor overload:
 *
 * ```java
 * var scratch = new ScratchTool(new DockerSandboxSessionManager());
 * var custom = new ScratchTool(sessionManager, ScratchTool.DEFAULT_CONFIG, "alice", Duration.ofHours(2));
 * ```
 *
 * Durations are [java.time.Duration], as elsewhere in the framework. A secondary
 * constructor accepts [kotlin.time.Duration] for existing Kotlin callers.
 *
 * @param sessionManager manager that owns the underlying session
 * @param config sandbox configuration (image, resources, env)
 * @param owner optional owner identifier passed through to the session
 * @param ttl idle TTL for the session
 * @param name tool name visible to the LLM
 * @param description tool description visible to the LLM
 * @param shell shell used to interpret commands inside the container
 * @param timeout per-command execution timeout
 */
class ScratchTool @JvmOverloads constructor(
    private val sessionManager: SandboxSessionManager,
    private val config: SandboxConfig = DEFAULT_CONFIG,
    private val owner: String? = null,
    private val ttl: Duration = Duration.ofHours(1),
    name: String = DEFAULT_NAME,
    description: String = DEFAULT_DESCRIPTION,
    private val shell: String = "bash",
    private val timeout: Duration = Duration.ofSeconds(60),
) : Tool, AutoCloseable {

    /**
     * Source-compatible constructor for Kotlin callers that pass [kotlin.time.Duration]
     * values. Both durations are required so that a call without them resolves to the
     * primary constructor. The Kotlin compiler makes a constructor with value class
     * parameters private, so this one is not callable from Java: use the primary
     * constructor with [java.time.Duration] there.
     */
    constructor(
        sessionManager: SandboxSessionManager,
        config: SandboxConfig = DEFAULT_CONFIG,
        owner: String? = null,
        ttl: kotlin.time.Duration,
        name: String = DEFAULT_NAME,
        description: String = DEFAULT_DESCRIPTION,
        shell: String = "bash",
        timeout: kotlin.time.Duration,
    ) : this(
        sessionManager = sessionManager,
        config = config,
        owner = owner,
        ttl = ttl.toJavaDuration(),
        name = name,
        description = description,
        shell = shell,
        timeout = timeout.toJavaDuration(),
    )

    private val logger = LoggerFactory.getLogger(ScratchTool::class.java)
    private val objectMapper = EmbabelObjectMapperHolder.createDefault().get()

    @Volatile
    private var session: SandboxSession? = null

    override val definition = Tool.Definition(
        name = name,
        description = description,
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string(
                "command",
                "The bash command to run in the sandbox. Can be multi-line. " +
                        "Examples: 'pip install pandas', 'python3 script.py', 'ls -la /workspace'",
            ),
            Tool.Parameter.string(
                "stdin",
                "Optional input to pipe to the command's stdin",
                required = false,
            ),
        ),
    )

    @Synchronized
    override fun call(input: String): Tool.Result {
        return try {
            @Suppress("UNCHECKED_CAST")
            val params = objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
            val rawCommand = params["command"] as? String
                ?: return Tool.Result.error("'command' parameter is required")
            val stdin = params["stdin"] as? String

            val command = wrapIfCode(rawCommand)
            val active = ensureSession()
            // Feed the script through stdin so Docker's Windows CLI cannot reparse or
            // truncate a multiline command argument (for example, a heredoc).
            val request = if (stdin == null) {
                ExecutionRequest(command = listOf(shell, "-s"), stdin = command, timeout = timeout.toKotlinDuration())
            } else {
                ExecutionRequest(command = listOf(shell, "-c", command), stdin = stdin, timeout = timeout.toKotlinDuration())
            }
            renderResult(active.execute(request))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Tool.Result.error("Command interrupted")
        } catch (e: Exception) {
            logger.warn("Scratch command failed: {}", e.message)
            Tool.Result.error("Command failed: ${e.message}")
        }
    }

    private fun ensureSession(): SandboxSession = synchronized(this) {
        val current = session
        when (current?.state) {
            SandboxSession.SessionState.ACTIVE -> current
            SandboxSession.SessionState.PAUSED -> {
                logger.info("Resuming paused scratch session {}", current.id)
                current.resume()
                current
            }

            SandboxSession.SessionState.CLOSED, null -> {
                if (current != null) {
                    logger.info("Previous scratch session {} was closed — creating a new one", current.id)
                }
                sessionManager.create(
                    label = definition.name,
                    config = config,
                    owner = owner,
                    ttl = ttl.toKotlinDuration(),
                ).also { session = it }
            }
        }
    }

    private fun renderResult(result: ExecutionResult): Tool.Result = when (result) {
        is ExecutionResult.Completed -> {
            val combined = buildString {
                append(result.stdout)
                if (result.stderr.isNotEmpty()) {
                    if (isNotEmpty() && !endsWith("\n")) append("\n")
                    append(result.stderr)
                }
            }
            val text = if (result.success) combined.ifBlank { "(no output)" }
            else "Exit code ${result.exitCode}:\n$combined"
            if (result.artifacts.isEmpty()) Tool.Result.text(text)
            else Tool.Result.withArtifact(text, result.artifacts)
        }
        is ExecutionResult.TimedOut -> Tool.Result.text("Command timed out after ${timeout.toSeconds()}s")
        is ExecutionResult.Failed -> Tool.Result.error("Command failed: ${result.error}")
        is ExecutionResult.Denied -> Tool.Result.error("Command denied: ${result.reason}")
    }

    /** Publish selected files from the same persistent session used by [call]. */
    @Synchronized
    internal fun publishFiles(containerPaths: List<String>): Tool.Result = try {
        val publisher = ensureSession() as? ArtifactPublishingSession
            ?: return Tool.Result.error("This sandbox session does not support artifact publishing")
        val result = publisher.publishFiles(containerPaths)
        val text = buildString {
            result.files.forEach { appendLine("Published ${it.containerPath}") }
            result.failures.forEach { (path, error) -> appendLine("Failed $path: $error") }
            if (result.files.isNotEmpty()) {
                appendLine("Published files were exported successfully. Do not regenerate or republish them unless they change.")
            }
        }.trim()
        if (result.files.isEmpty()) Tool.Result.error(text.ifBlank { "No files were published" })
        else Tool.Result.withArtifact(text, result.files.map(PublishedFile::artifact))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Tool.Result.error("File publication interrupted")
    } catch (e: Exception) {
        logger.warn("Scratch batch publication failed: {}", e.message)
        Tool.Result.error("Could not publish files: ${e.message}")
    }

    @Synchronized
    override fun close() {
        val s = session ?: return
        try {
            s.close()
        } catch (e: Exception) {
            logger.warn("Failed to close scratch session {}: {}", s.id, e.message)
        }
        session = null
    }

    /**
     * Detect when the LLM passes raw Python or JavaScript code instead of
     * a bash command, and auto-wrap it so it actually runs.
     */
    private fun wrapIfCode(command: String): String {
        val trimmed = command.trim()

        val pythonIndicators = listOf("import ", "from ", "def ", "class ", "print(", "numpy", "pandas")
        if (pythonIndicators.any { trimmed.startsWith(it) }) {
            if (!trimmed.startsWith("python")) {
                logger.debug("Auto-wrapping as Python script")
                val escaped = trimmed.replace("'", "'\\''")
                return "python3 -c '$escaped'"
            }
        }

        val jsIndicators = listOf("const ", "let ", "var ", "console.log", "require(")
        if (jsIndicators.any { trimmed.startsWith(it) }) {
            if (!trimmed.startsWith("node")) {
                logger.debug("Auto-wrapping as Node.js script")
                val escaped = trimmed.replace("'", "'\\''")
                return "node -e '$escaped'"
            }
        }

        return command
    }

    companion object {

        /**
         * Default sandbox image with Python, Node.js, Java, Perl, SQLite,
         * Graphviz, ImageMagick, and common data science packages.
         */
        const val DEFAULT_IMAGE = "embabel/agent-sandbox:latest"

        /** Default tool name visible to the LLM. */
        const val DEFAULT_NAME = "scratch_run"

        /** Default sandbox configuration: the default image with 1g of memory and 2 CPUs. */
        @JvmField
        val DEFAULT_CONFIG = SandboxConfig(enabled = true, image = DEFAULT_IMAGE, memory = "1g", cpus = "2.0")

        /** Default tool description visible to the LLM. */
        @JvmField
        val DEFAULT_DESCRIPTION = """
            Run a bash command in a persistent Docker sandbox with Python 3, Node.js, Java,
            Perl, SQLite, Graphviz, ImageMagick, numpy, pandas, and common utilities.
            State persists between calls — files, packages, and environment carry over.
            For Python: python3 -c 'code' or write a script file then run it.
            For Node: node -e 'code'. For multi-line code, write to a file first.
            If a command fails, diagnose the error and try again — DO NOT give up.
            Install missing packages if needed (pip install, apt-get, npm install).
            When scratch_publish is available, use it to publish selected finished files.
        """.trimIndent()
    }
}
