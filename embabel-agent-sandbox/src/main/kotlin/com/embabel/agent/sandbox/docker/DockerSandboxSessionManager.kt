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

import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.SandboxSession
import com.embabel.agent.sandbox.SandboxSessionManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Docker-backed [SandboxSessionManager] that manages persistent sandbox sessions.
 *
 * Sessions are stored in memory and backed by Docker containers.
 * Idle sessions are paused (container stopped) and eventually evicted (container removed).
 *
 * @param maxSessionsPerOwner maximum concurrent sessions per owner (0 = unlimited)
 * @param maxTotalSessions maximum total concurrent sessions (0 = unlimited)
 * @param pauseGracePeriod how long a paused session survives before being closed
 * @param artifactExport optional explicit file publication settings shared by sessions
 */
class DockerSandboxSessionManager @JvmOverloads constructor(
    private val maxSessionsPerOwner: Int = 5,
    private val maxTotalSessions: Int = 20,
    private val pauseGracePeriod: Duration = 1.hours,
    private val artifactExport: ArtifactExportConfig? = null,
) : SandboxSessionManager {

    /** Java-friendly opt-in constructor for artifact export. */
    constructor(artifactExport: ArtifactExportConfig) : this(5, 20, 1.hours, artifactExport)

    private val logger = LoggerFactory.getLogger(DockerSandboxSessionManager::class.java)

    private val sessions = ConcurrentHashMap<String, DockerSandboxSession>()
    private val creationLock = Any()
    private val pendingStarts = mutableMapOf<String?, Int>()
    private var generation = 0L

    override fun create(
        label: String,
        config: SandboxConfig,
        owner: String?,
        ttl: Duration,
        metadata: Map<String, String>,
    ): SandboxSession {
        val reservedGeneration = reserveSlot(owner) ?: run {
            // Eviction may call Docker, so it must also run outside the admission lock.
            evictExpired()
            reserveSlot(owner) ?: throw IllegalStateException(
                "Maximum total sessions ($maxTotalSessions) reached. Close existing sessions first."
            )
        }
        var session: DockerSandboxSession? = null
        var registered = false
        try {
            val started = DockerSandboxSession(
                label = label,
                config = config,
                owner = owner,
                ttl = ttl,
                metadata = metadata,
                artifactExport = artifactExport,
            ).also { session = it }
            synchronized(creationLock) {
                check(reservedGeneration == generation) { "Session creation cancelled by closeAll" }
                sessions[started.id] = started
                releaseSlot(owner)
                registered = true
            }
            logger.info("Created session '{}' (id={}, owner={}, ttl={})", label, started.id, owner ?: "none", ttl)
            return started
        } finally {
            if (!registered) {
                try {
                    session?.close()
                } finally {
                    synchronized(creationLock) { releaseSlot(owner) }
                }
            }
        }
    }

    private fun reserveSlot(owner: String?): Long? = synchronized(creationLock) {
        sessions.entries.removeIf { it.value.state == SandboxSession.SessionState.CLOSED }
        if (maxTotalSessions > 0 && sessions.size + pendingStarts.values.sum() >= maxTotalSessions) {
            return@synchronized null
        }
        if (owner != null && maxSessionsPerOwner > 0) {
            val ownerCount = sessions.values.count {
                it.owner == owner && it.state != SandboxSession.SessionState.CLOSED
            } + pendingStarts.getOrDefault(owner, 0)
            check(ownerCount < maxSessionsPerOwner) {
                "Maximum sessions per owner ($maxSessionsPerOwner) reached for '$owner'."
            }
        }
        pendingStarts[owner] = pendingStarts.getOrDefault(owner, 0) + 1
        generation
    }

    private fun releaseSlot(owner: String?) {
        val remaining = pendingStarts.getValue(owner) - 1
        if (remaining == 0) pendingStarts.remove(owner) else pendingStarts[owner] = remaining
    }

    override fun get(id: String): SandboxSession? {
        val session = sessions[id] ?: return null
        if (session.state == SandboxSession.SessionState.CLOSED) {
            sessions.remove(id)
            return null
        }
        return session
    }

    override fun list(owner: String?): List<SandboxSession> {
        return sessions.values
            .filter { it.state != SandboxSession.SessionState.CLOSED }
            .filter { owner == null || it.owner == owner }
            .sortedByDescending { it.lastActiveAt }
    }

    override fun evictExpired() {
        for ((id, session) in sessions) {
            if (session.evictIfIdle(pauseGracePeriod)) {
                sessions.remove(id, session)
            }
        }
    }

    override fun closeAll() {
        val closing = synchronized(creationLock) {
            // In-flight starts keep their reservations until they can close and release them.
            generation++
            sessions.values.toList()
        }
        logger.info("Closing all {} sandbox sessions", closing.size)
        for (session in closing) {
            try {
                session.close()
            } catch (e: Exception) {
                logger.warn("Failed to close session '{}': {}", session.id, e.message)
            } finally {
                sessions.remove(session.id, session)
            }
        }
    }
}
