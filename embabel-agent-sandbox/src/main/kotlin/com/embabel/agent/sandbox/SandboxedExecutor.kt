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

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * A generalized executor for running commands in sandboxed environments.
 *
 * This provides a common abstraction for:
 * - Running scripts (Python, Bash, JS, etc.)
 * - Running CLI tools (Claude Code, Aider, etc.)
 * - Running arbitrary commands with isolation
 *
 * Implementations can provide different sandboxing strategies:
 * - **ProcessExecutor**: OS process isolation (basic)
 * - **DockerExecutor**: Container isolation (strong)
 * - **NoOpExecutor**: Denies all execution (safe default)
 *
 * ## Environment Variables
 *
 * All executors provide these environment variables to commands:
 * - `INPUT_DIR`: Directory containing input files (read-only)
 * - `OUTPUT_DIR`: Directory where commands can write artifacts
 *
 * @see ExecutionRequest
 * @see ExecutionResult
 */
interface SandboxedExecutor {

    /**
     * Execute a command in the sandbox synchronously.
     *
     * @param request the execution request
     * @return the execution result
     */
    fun execute(request: ExecutionRequest): ExecutionResult

    /**
     * Execute a command in the sandbox asynchronously.
     *
     * This allows for:
     * - Starting long-running tasks without blocking
     * - Cancelling execution mid-flight
     * - Getting partial output while running
     *
     * Default implementation wraps [execute] in a [CompletableFuture].
     * Implementations can override for better streaming and cancellation support.
     *
     * @param request the execution request
     * @return an [AsyncExecution] handle for monitoring and controlling the execution
     */
    fun executeAsync(request: ExecutionRequest): AsyncExecution {
        return DefaultAsyncExecution(this, request)
    }

    /**
     * Check if the executor is available and properly configured.
     *
     * @return null if available, or a reason string if not
     */
    fun checkAvailability(): String?

    /**
     * Validate a request without executing it.
     *
     * @param request the request to validate
     * @return null if valid, or a [ExecutionResult.Denied] with reason
     */
    fun validate(request: ExecutionRequest): ExecutionResult.Denied? = null
}

/**
 * Handle for an asynchronous execution.
 *
 * Allows monitoring progress, getting partial output, and cancelling execution.
 */
interface AsyncExecution {

    /**
     * Whether the execution is still running.
     */
    val isRunning: Boolean

    /**
     * Whether the execution was cancelled.
     */
    val isCancelled: Boolean

    /**
     * Wait for completion and get the result.
     *
     * @return the execution result
     */
    fun await(): ExecutionResult

    /**
     * Wait for completion with a timeout.
     *
     * @param timeout maximum time to wait
     * @return the result, or [ExecutionResult.TimedOut] if the wait times out
     */
    fun await(timeout: Duration): ExecutionResult

    /**
     * Cancel the execution.
     *
     * @return true if cancellation was initiated, false if already completed
     */
    fun cancel(): Boolean

    /**
     * Get partial stdout captured so far (if available).
     *
     * Not all implementations support streaming output.
     *
     * @return partial output, or null if not supported
     */
    fun getPartialStdout(): String? = null

    /**
     * Get partial stderr captured so far (if available).
     *
     * @return partial stderr, or null if not supported
     */
    fun getPartialStderr(): String? = null

    /**
     * Get a [CompletableFuture] that completes when execution finishes.
     */
    fun toFuture(): CompletableFuture<ExecutionResult>
}

/**
 * Default implementation of [AsyncExecution] that wraps synchronous execution.
 */
private class DefaultAsyncExecution(
    private val executor: SandboxedExecutor,
    private val request: ExecutionRequest,
) : AsyncExecution {

    private val cancelled = AtomicBoolean(false)
    private val future: CompletableFuture<ExecutionResult> = CompletableFuture.supplyAsync {
        if (cancelled.get()) {
            ExecutionResult.Failed("Execution was cancelled before starting")
        } else {
            executor.execute(request)
        }
    }

    override val isRunning: Boolean
        get() = !future.isDone

    override val isCancelled: Boolean
        get() = cancelled.get()

    override fun await(): ExecutionResult = future.get()

    override fun await(timeout: Duration): ExecutionResult {
        return try {
            future.get(timeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            ExecutionResult.TimedOut(duration = timeout)
        }
    }

    override fun cancel(): Boolean {
        if (cancelled.compareAndSet(false, true)) {
            future.cancel(true)
            return true
        }
        return false
    }

    override fun toFuture(): CompletableFuture<ExecutionResult> = future
}

/**
 * Request to execute a command in a sandbox.
 *
 * @param command the command and arguments to execute
 * @param workingDirectory the working directory for execution
 * @param environment additional environment variables
 * @param stdin input to provide via standard input
 * @param inputFiles files to make available to the command (will be in INPUT_DIR)
 * @param timeout maximum execution time
 * @param captureOutput whether to capture stdout/stderr (default true)
 */
data class ExecutionRequest(
    val command: List<String>,
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    val inputFiles: List<Path> = emptyList(),
    val timeout: Duration,
    val captureOutput: Boolean = true,
    /**
     * Optional callback invoked for each line of stdout as it streams.
     * Enables real-time progress reporting while the command runs.
     * The full stdout is still captured and returned in [ExecutionResult.Completed].
     */
    val stdoutCallback: ((String) -> Unit)? = null,
)

/**
 * Result of a sandboxed execution.
 */
sealed interface ExecutionResult {

    /**
     * Wall-clock time the execution took, regardless of outcome. Present on EVERY result —
     * a denial still spends validation time, a failed-to-start still spends setup time — so
     * callers (logging, the Cypher console, metrics) report it uniformly. Defaults to
     * [Duration.ZERO] on the cases with no meaningful elapsed to report.
     */
    val duration: Duration

    /**
     * Successful execution (process completed, regardless of exit code).
     *
     * @param exitCode the process exit code
     * @param stdout captured standard output
     * @param stderr captured standard error
     * @param duration how long execution took
     * @param artifacts files produced in OUTPUT_DIR
     */
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        override val duration: Duration,
        val artifacts: List<ExecutionArtifact> = emptyList(),
    ) : ExecutionResult {

        /** Whether the process exited successfully (exit code 0) */
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Execution timed out.
     *
     * @param partialStderr any stderr captured before timeout
     * @param duration how long before timeout
     */
    data class TimedOut(
        val partialStderr: String? = null,
        override val duration: Duration,
    ) : ExecutionResult

    /**
     * Execution failed to start.
     *
     * @param error the error message
     * @param cause the underlying exception, if any
     * @param duration how long before the failure (zero if it failed before any work)
     */
    data class Failed(
        val error: String,
        val cause: Throwable? = null,
        override val duration: Duration = Duration.ZERO,
    ) : ExecutionResult

    /**
     * Execution was denied (validation failed or not permitted).
     *
     * @param reason why execution was denied
     * @param duration how long validation took (usually near-zero)
     */
    data class Denied(
        val reason: String,
        override val duration: Duration = Duration.ZERO,
    ) : ExecutionResult
}

/**
 * Something produced by a script execution. Two kinds:
 *
 * - [File] — bytes the script wrote (the classic case: a CSV, a PDF,
 *   a generated HTML app). The runner copies the file out of the
 *   sandbox and the artifact carries name + path + mime type + size.
 *
 * - [Entity] — a *reference* to a domain object the script touched
 *   (a Person, an Issue, a Message). Carries identity, not bytes.
 *   Lets downstream consumers — chat UIs, provenance writers, context
 *   graphs — see *what* an execution interacted with, not just what
 *   files it wrote. Emitting executors decide what counts as an
 *   entity for their domain (a Cypher executor might emit one per
 *   returned node; an integration tool one per fetched record).
 *
 * Sealed so the set of cases is closed and exhaustive — when this
 * grows (e.g. `Metric`, `Event`), the compiler flags every consumer
 * that needs to handle the new case.
 */
sealed interface ExecutionArtifact {
    /** Human-readable identifier, used in audit logs and UIs. */
    val name: String

    /**
     * A file produced by execution.
     *
     * @param name the file name
     * @param path absolute path to the artifact
     * @param mimeType detected MIME type
     * @param sizeBytes file size in bytes
     */
    data class File(
        override val name: String,
        val path: Path,
        val mimeType: String? = null,
        val sizeBytes: Long,
    ) : ExecutionArtifact

    /**
     * A reference to a domain entity the script touched. Carries
     * identity and minimal display metadata — *not* the entity's
     * full body. Consumers that need the body re-fetch it via the
     * relevant tool / data path.
     *
     * @param label the entity's type (e.g. "Person", "Message",
     *   "Issue"). Free-form by convention; a pack-declared ontology
     *   registry is the natural place to constrain these.
     * @param id a stable identifier within the [label]'s namespace.
     *   Combined with [source], `(source, label, id)` uniquely
     *   identifies the entity across executions.
     * @param displayName optional human-readable label. UIs that
     *   render entity chips show this; falls back to [id] when null.
     * @param source optional opaque tag for the producing tool /
     *   integration / namespace (e.g. "gmail", "github",
     *   "context-graph"). Used by identity resolution to decide
     *   whether two artifacts refer to the same entity.
     * @param name defaults to "[label]/[id]" — overridable for
     *   producers that want a richer audit-log line.
     */
    data class Entity(
        val label: String,
        val id: String,
        val displayName: String? = null,
        val source: String? = null,
        override val name: String = "$label/$id",
    ) : ExecutionArtifact

    companion object {
        /**
         * Infer MIME type from file name extension. Kept here (not on
         * [File]) so callers that already do `ExecutionArtifact.inferMimeType(...)`
         * — the existing public API — keep working unchanged.
         */
        fun inferMimeType(fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "pdf" -> "application/pdf"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "csv" -> "text/csv"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "zip" -> "application/zip"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "py" -> "text/x-python"
                "js" -> "text/javascript"
                "kt", "kts" -> "text/x-kotlin"
                "java" -> "text/x-java"
                "sh" -> "text/x-shellscript"
                else -> "application/octet-stream"
            }
        }
    }
}

/**
 * A no-op executor that denies all execution.
 *
 * This is the safe default when no sandbox is configured.
 */
object NoOpExecutor : SandboxedExecutor {

    override fun execute(request: ExecutionRequest): ExecutionResult =
        ExecutionResult.Denied("Execution is disabled. No sandbox executor is configured.")

    override fun checkAvailability(): String =
        "No sandbox executor is configured."

    override fun validate(request: ExecutionRequest): ExecutionResult.Denied =
        ExecutionResult.Denied("Execution is disabled. No sandbox executor is configured.")
}
