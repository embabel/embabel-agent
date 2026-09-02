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
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import tools.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import java.time.Clock
import java.time.Instant

/**
 * JSON serializer for structured agent process snapshots.
 *
 * Uses the caller-provided Embabel [ObjectMapper]. The returned
 * [SerializedAgentProcessSnapshot] keeps lookup and concurrency metadata beside
 * the JSON payload so stores can index by process id, parent id, status, agent
 * name, and version without parsing the snapshot body.
 */
internal class JacksonAgentProcessStateSerializer(
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun serialize(snapshot: AgentProcessSnapshot): SerializedAgentProcessSnapshot =
        SerializedAgentProcessSnapshot(
            processId = snapshot.processId,
            parentId = snapshot.parentId,
            agentName = snapshot.agentName,
            status = snapshot.status,
            version = snapshot.version,
            contentType = MediaType.APPLICATION_JSON,
            payload = objectMapper.writeValueAsBytes(snapshot),
            createdAt = snapshot.timestamp,
            updatedAt = Instant.now(clock),
            metadata = snapshot.metadata,
        )

    fun deserialize(snapshot: SerializedAgentProcessSnapshot): AgentProcessSnapshot {
        if (!snapshot.contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            throw AgentProcessPersistenceException(
                "Unsupported agent process snapshot content type [${snapshot.contentType}]"
            )
        }
        return objectMapper.readValue(snapshot.payload, AgentProcessSnapshot::class.java)
    }
}
