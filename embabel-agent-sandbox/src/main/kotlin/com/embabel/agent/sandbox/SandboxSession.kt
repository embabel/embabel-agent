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
import java.time.Instant
import kotlin.time.Duration

/**
 * A persistent sandbox environment that survives across multiple executions.
 *
 * Unlike [DockerExecutor] which creates a fresh container per execution,
 * a SandboxSession maintains a long-lived container where state (installed packages,
 * created files, environment changes) persists between calls.
 *
 * Sessions have identity, lifecycle management (pause/resume), and can be
 * used by any executor that accepts a session reference.
 *
 * ## Example
 *
 * ```kotlin
 * val session = sessionManager.create("my-project", config)
 *
 * // Turn 1: install dependencies
 * session.execute(ExecutionRequest(command = listOf("pip", "install", "numpy")))
 *
 * // Turn 2: numpy is still installed
 * session.execute(ExecutionRequest(command = listOf("python3", "-c", "import numpy; print(numpy.__version__)")))
 *
 * // Pause when idle (container stopped, filesystem preserved)
 * session.pause()
 *
 * // Resume later
 * session.resume()
 *
 * // Destroy when done
 * session.close()
 * ```
 */
interface SandboxSession : AutoCloseable {

    /** Unique session identifier. */
    val id: String

    /** Human-readable label (e.g., "alice's refactoring session"). */
    val label: String

    /** Who owns this session (user or agent identifier). */
    val owner: String?

    /** When this session was created. */
    val createdAt: Instant

    /** When the last execution completed. */
    val lastActiveAt: Instant

    /** Current lifecycle state. */
    val state: SessionState

    /** The sandbox config this session was created with. */
    val config: SandboxConfig

    /**
     * Free-form key/value tags supplied at create-time. The implementation
     * mirrors these to the underlying executor (e.g. docker `--label k=v`)
     * so they're queryable via the executor's CLI as well as the Kotlin
     * object. Semantics belong to the caller — see
     * [SandboxSessionManager.create] for typical uses.
     */
    val metadata: Map<String, String>
        get() = emptyMap()

    /**
     * Execute a command in this session's persistent environment.
     * The environment retains all state from prior executions.
     *
     * @throws IllegalStateException if the session is [SessionState.CLOSED]
     */
    fun execute(request: ExecutionRequest): ExecutionResult

    /**
     * Copy a file or directory from inside the session container to the host.
     *
     * @param containerPath path inside the container
     * @param hostPath destination on the host filesystem
     */
    fun copyFrom(containerPath: String, hostPath: Path)

    /**
     * Copy a file or directory from the host into the session container.
     *
     * @param hostPath path on the host filesystem
     * @param containerPath destination inside the container
     */
    fun copyTo(hostPath: Path, containerPath: String)

    /**
     * Pause the session: stop the container but preserve its filesystem.
     * A paused session can be resumed later without losing state.
     */
    fun pause()

    /**
     * Resume a paused session. Restarts the stopped container.
     *
     * @throws IllegalStateException if the session is not [SessionState.PAUSED]
     */
    fun resume()

    /**
     * Destroy the session and all its state. This is irreversible.
     */
    override fun close()

    /**
     * Lifecycle states for a sandbox session.
     */
    enum class SessionState {
        /** Container running, accepting executions. */
        ACTIVE,
        /** Container stopped, filesystem preserved, can resume. */
        PAUSED,
        /** Container removed, all state destroyed. */
        CLOSED,
    }
}
