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
package com.embabel.agent.spi.persistence

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import org.springframework.http.MediaType
import java.time.Instant

/**
 * Decides when an agent process should be checkpointed.
 *
 * Repository implementations call this policy after framework lifecycle events
 * such as save/update. Narrow policies may checkpoint only processes parked by
 * WaitFor, while broader policies may checkpoint after every action or at
 * terminal states.
 */
fun interface AgentProcessCheckpointPolicy {

    /**
     * Return true when the current process state should be serialized and stored.
     */
    fun shouldStoreSnapshot(agentProcess: AgentProcess): Boolean
}

/**
 * Storage SPI for serialized process checkpoints.
 *
 * Implementations may store the payload in memory, Redis, JDBC/Postgres, or
 * another backend. The payload is opaque to the store.
 *
 * A null `expectedVersion` means "create only": saving must fail if a snapshot
 * already exists for the process. A non-null `expectedVersion` means
 * compare-and-set update: saving must fail unless the stored snapshot currently
 * has that version.
 */
interface AgentProcessSnapshotStore {

    /**
     * Save a new or updated snapshot.
     *
     * @throws com.embabel.agent.core.persistence.AgentProcessPersistenceException
     * if the expected version does not match the stored version.
     */
    fun save(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long? = null,
    ): StoredSnapshotMetadata

    /**
     * Return the latest serialized snapshot for the process, if one exists.
     */
    fun findByProcessId(processId: String): SerializedAgentProcessSnapshot?

    /**
     * Return latest serialized snapshots for direct child processes.
     */
    fun findByParentId(parentId: String): List<SerializedAgentProcessSnapshot>

    /**
     * Delete the latest serialized snapshot for the process.
     */
    fun delete(processId: String)
}

/**
 * Backend-neutral envelope for a serialized agent process checkpoint.
 *
 * Stores lookup and concurrency metadata beside an opaque payload. Storage
 * backends should not inspect [payload]; serialization and restore are owned by
 * higher framework layers.
 *
 * @param processId stable id of the agent process represented by this snapshot
 * @param parentId parent process id for sub-agent processes, if any
 * @param agentName name of the agent definition needed to restore the process
 * @param status process status at the checkpoint boundary
 * @param version optimistic concurrency version for this process snapshot
 * @param contentType media type of [payload], for example [MediaType.APPLICATION_JSON]
 * @param payload serialized process snapshot bytes
 */
data class SerializedAgentProcessSnapshot(
    val processId: String,
    val parentId: String?,
    val agentName: String,
    val status: AgentProcessStatusCode,
    val version: Long,
    val contentType: MediaType,
    val payload: ByteArray,
    val createdAt: Instant,
    val updatedAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
) {

    override fun equals(other: Any?): Boolean =
        this === other ||
                other is SerializedAgentProcessSnapshot &&
                processId == other.processId &&
                parentId == other.parentId &&
                agentName == other.agentName &&
                status == other.status &&
                version == other.version &&
                contentType == other.contentType &&
                payload.contentEquals(other.payload) &&
                createdAt.compareTo(other.createdAt) == 0 &&
                updatedAt.compareTo(other.updatedAt) == 0 &&
                metadata == other.metadata

    override fun hashCode(): Int {
        var result = processId.hashCode()
        result = 31 * result + (parentId?.hashCode() ?: 0)
        result = 31 * result + agentName.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

/**
 * Result metadata from saving a snapshot.
 */
data class StoredSnapshotMetadata(
    val processId: String,
    val version: Long,
    val updatedAt: Instant,
)
