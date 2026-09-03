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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import com.embabel.agent.spi.persistence.StoredSnapshotMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [AgentProcessSnapshotStore].
 *
 * Intended for tests and local development. This store has no TTL, eviction, or
 * size bound; production deployments should use a shared durable implementation.
 *
 * This implementation enforces the same optimistic version contract expected of
 * durable stores: create-only saves are allowed only when no snapshot exists,
 * while updates must pass the currently stored version.
 */
class InMemoryAgentProcessSnapshotStore : AgentProcessSnapshotStore {

    private val snapshots = ConcurrentHashMap<String, SerializedAgentProcessSnapshot>()

    override fun save(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long?,
    ): StoredSnapshotMetadata {
        snapshots.compute(snapshot.processId) { processId, existing ->
            when {
                expectedVersion == null && existing != null ->
                    throw AgentProcessPersistenceException(
                        "Cannot create snapshot for process [$processId]: snapshot already exists at " +
                                "version [${existing.version}]"
                    )

                expectedVersion != null && existing?.version != expectedVersion ->
                    throw AgentProcessPersistenceException(
                        "Cannot save snapshot for process [$processId]: expected version " +
                                "[$expectedVersion] but found [${existing?.version}]"
                    )
            }
            snapshot
        }
        return StoredSnapshotMetadata(
            processId = snapshot.processId,
            version = snapshot.version,
            updatedAt = snapshot.updatedAt,
        )
    }

    override fun findLatestByProcessId(processId: String): SerializedAgentProcessSnapshot? =
        snapshots[processId]

    override fun findByParentId(parentId: String): List<SerializedAgentProcessSnapshot> =
        snapshots.values.filter { it.parentId == parentId }

    override fun delete(processId: String) {
        snapshots.remove(processId)
    }

    fun clear() {
        snapshots.clear()
    }
}
