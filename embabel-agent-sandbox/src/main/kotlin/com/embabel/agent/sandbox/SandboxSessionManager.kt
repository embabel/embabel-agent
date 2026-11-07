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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Manages the lifecycle of [SandboxSession]s across users and executors.
 *
 * Handles creation, lookup, eviction, and resource limits. Implementations
 * are responsible for enforcing concurrency limits and TTL-based cleanup.
 *
 * ## Example
 *
 * ```kotlin
 * val manager = DockerSandboxSessionManager()
 *
 * // Create a session
 * val session = manager.create(
 *     label = "feature-42",
 *     config = SandboxConfig(enabled = true, image = "node:20-slim"),
 *     owner = "alice",
 *     ttl = 4.hours,
 * )
 *
 * // Retrieve later
 * val same = manager.get(session.id)
 *
 * // List all of alice's sessions
 * val sessions = manager.list(owner = "alice")
 *
 * // Cleanup expired sessions (call periodically)
 * manager.evictExpired()
 *
 * // Shutdown
 * manager.closeAll()
 * ```
 */
interface SandboxSessionManager : AutoCloseable {

    /**
     * Create a new persistent sandbox session.
     *
     * @param label human-readable name for this session
     * @param config sandbox configuration (image, resources, env)
     * @param owner optional owner identifier (user or agent)
     * @param ttl time-to-live — session is evicted after this idle duration
     * @param metadata free-form key/value tags. Stored on [SandboxSession.metadata]
     *   AND mirrored to the underlying executor (e.g. docker `--label k=v`) so
     *   tool-side queries (`docker ps --filter label=k=v`) and Kotlin-side
     *   filtering both work. Semantics belong to the caller — common uses:
     *   identify the JVM that owns the session for shutdown cleanup; tag the
     *   logical request the session is serving for diagnostics; group sessions
     *   by tenant for batch operations.
     * @return the created session in [SandboxSession.SessionState.ACTIVE] state
     */
    fun create(
        label: String,
        config: SandboxConfig,
        owner: String? = null,
        ttl: Duration = 1.hours,
        metadata: Map<String, String> = emptyMap(),
    ): SandboxSession

    /**
     * Retrieve an existing session by ID.
     *
     * @return the session, or null if it has been evicted, closed, or never existed
     */
    fun get(id: String): SandboxSession?

    /**
     * List active and paused sessions, optionally filtered by owner.
     *
     * @param owner if non-null, only return sessions owned by this identifier
     * @return list of sessions (excludes closed sessions)
     */
    fun list(owner: String? = null): List<SandboxSession>

    /**
     * Evict sessions that have been idle beyond their TTL.
     *
     * Pauses active idle sessions first, then closes sessions that have
     * been paused beyond a grace period.
     *
     * This should be called periodically (e.g., every 5 minutes).
     */
    fun evictExpired()

    /**
     * Shut down all sessions. Called on application shutdown.
     */
    fun closeAll()

    /**
     * Close the manager and all its sessions.
     */
    override fun close() = closeAll()
}
