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
package com.embabel.agent.jdbc

import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import com.embabel.agent.spi.persistence.StoredSnapshotMetadata
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * JDBC-backed [AgentProcessSnapshotStore].
 *
 * Persists agent process snapshots in a relational database using the
 * `agent_process_snapshots` table. Schema DDL is in
 * `com/embabel/agent/jdbc/schema.sql` — apply it before use.
 *
 * Optimistic concurrency: a null `expectedVersion` on [save] is an insert-only call;
 * a non-null `expectedVersion` is a compare-and-set update that fails if the stored
 * version has changed.
 *
 * Each [JdbcTemplate] operation runs with the connection's default transaction
 * context. Wrap in a transaction if insert+update atomicity across multiple
 * processes is required.
 */
class JdbcAgentProcessSnapshotStore(
    private val jdbcTemplate: JdbcTemplate,
) : AgentProcessSnapshotStore {

    private val logger = LoggerFactory.getLogger(JdbcAgentProcessSnapshotStore::class.java)

    override fun save(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long?,
    ): StoredSnapshotMetadata {
        val updated = if (expectedVersion == null) {
            insert(snapshot)
        } else {
            update(snapshot, expectedVersion)
        }
        if (updated != 1) {
            val found = findLatestByProcessId(snapshot.processId)?.version
            throw AgentProcessPersistenceException(
                "Cannot save snapshot for process [${snapshot.processId}]: expected version " +
                    "[$expectedVersion] but found [$found]"
            )
        }
        logger.debug(
            "Snapshot saved processId={} status={} version={} payloadBytes={}",
            snapshot.processId,
            snapshot.status,
            snapshot.version,
            snapshot.payload.size,
        )
        return StoredSnapshotMetadata(
            processId = snapshot.processId,
            version = snapshot.version,
            updatedAt = snapshot.updatedAt,
        )
    }

    override fun findLatestByProcessId(processId: String): SerializedAgentProcessSnapshot? {
        val snapshot = jdbcTemplate.query(
            """
            select process_id, parent_id, agent_name, status, version, content_type, payload, created_at, updated_at
            from agent_process_snapshots
            where process_id = ?
            """.trimIndent(),
            { rs, _ -> mapRow(rs) },
            processId,
        ).firstOrNull()
        logger.trace(
            "Snapshot lookup processId={} result={}",
            processId,
            if (snapshot == null) "MISS" else "HIT",
        )
        return snapshot
    }

    override fun findByParentId(parentId: String): List<SerializedAgentProcessSnapshot> =
        jdbcTemplate.query(
            """
            select process_id, parent_id, agent_name, status, version, content_type, payload, created_at, updated_at
            from agent_process_snapshots
            where parent_id = ?
            order by process_id
            """.trimIndent(),
            { rs, _ -> mapRow(rs) },
            parentId,
        )

    override fun delete(processId: String) {
        val deleted = jdbcTemplate.update(
            "delete from agent_process_snapshots where process_id = ?",
            processId,
        )
        logger.debug("Snapshot deleted processId={} rowsAffected={}", processId, deleted)
    }

    private fun insert(snapshot: SerializedAgentProcessSnapshot): Int =
        try {
            jdbcTemplate.update(
                """
                insert into agent_process_snapshots (
                    process_id, parent_id, agent_name, status, version, content_type, payload, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                snapshot.processId,
                snapshot.parentId,
                snapshot.agentName,
                snapshot.status.name,
                snapshot.version,
                snapshot.contentType.toString(),
                snapshot.payload,
                Timestamp.from(snapshot.createdAt),
                Timestamp.from(snapshot.updatedAt),
            )
        } catch (ex: org.springframework.dao.DuplicateKeyException) {
            throw AgentProcessPersistenceException(
                "Cannot create snapshot for process [${snapshot.processId}]: snapshot already exists",
                ex,
            )
        }

    private fun update(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long,
    ): Int =
        jdbcTemplate.update(
            """
            update agent_process_snapshots
            set parent_id = ?, agent_name = ?, status = ?, version = ?, content_type = ?,
                payload = ?, created_at = ?, updated_at = ?
            where process_id = ? and version = ?
            """.trimIndent(),
            snapshot.parentId,
            snapshot.agentName,
            snapshot.status.name,
            snapshot.version,
            snapshot.contentType.toString(),
            snapshot.payload,
            Timestamp.from(snapshot.createdAt),
            Timestamp.from(snapshot.updatedAt),
            snapshot.processId,
            expectedVersion,
        )

    private fun mapRow(rs: ResultSet): SerializedAgentProcessSnapshot =
        SerializedAgentProcessSnapshot(
            processId = rs.getString("process_id"),
            parentId = rs.getString("parent_id"),
            agentName = rs.getString("agent_name"),
            status = AgentProcessStatusCode.valueOf(rs.getString("status")),
            version = rs.getLong("version"),
            contentType = MediaType.parseMediaType(rs.getString("content_type")),
            payload = rs.getBytes("payload"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}
