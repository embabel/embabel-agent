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
package com.embabel.agent.cache.support

import com.embabel.agent.cache.AgentCacheRegion
import com.embabel.agent.cache.CacheCapability
import com.embabel.agent.cache.CacheCapabilityException
import com.embabel.agent.cache.CachedValue
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore
import com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot
import com.embabel.agent.spi.persistence.StoredSnapshotMetadata
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import java.time.Instant

/**
 * [AgentProcessSnapshotStore] backed by any [AgentCacheRegion].
 *
 * This is written once and works over every provider, so integrating a backend
 * means implementing the cache SPI rather than reimplementing snapshot storage.
 * The region never sees agent types: the process payload is stored opaquely and
 * the snapshot header travels in [CachedValue.metadata], which backends with
 * native query support may index.
 *
 * @param region region to store snapshots in, normally
 * [com.embabel.agent.cache.CacheRegions.AGENT_PROCESS_SNAPSHOTS]
 */
class CacheBackedAgentProcessSnapshotStore(
    private val region: AgentCacheRegion,
) : AgentProcessSnapshotStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Index of parent process id to child process ids, backing [findByParentId].
     */
    private val parentIndex = region.index(PARENT_INDEX)
        ?: throw CacheCapabilityException(
            "Region [${region.name}] does not support ${CacheCapability.SECONDARY_INDEX}, " +
                    "which is required to resolve child processes. Use a provider that " +
                    "declares it, or request it via CacheRegionConfig.requiredCapabilities."
        )

    init {
        if (CacheCapability.ATOMIC_COMPARE_AND_SET !in region.capabilities) {
            logger.warn(
                """
                Region [{}] does not support {}.
                Snapshot writes fall back to read-modify-write, so two nodes checkpointing
                the same process concurrently can lose an update. Acceptable for a single
                writer per process; use a provider with atomic compare-and-set otherwise.
                """.trimIndent(),
                region.name,
                CacheCapability.ATOMIC_COMPARE_AND_SET,
            )
        }
    }

    override fun save(
        snapshot: SerializedAgentProcessSnapshot,
        expectedVersion: Long?,
    ): StoredSnapshotMetadata {
        val applied = region.replace(
            key = snapshot.processId,
            expectedVersion = expectedVersion,
            value = snapshot.toCachedValue(),
        )
        if (!applied) {
            throw AgentProcessPersistenceException(
                "Stale snapshot for process [${snapshot.processId}]: expected version " +
                        "$expectedVersion but the stored version differs. The process was " +
                        "checkpointed concurrently."
            )
        }
        snapshot.parentId?.let { parentIndex.add(it, snapshot.processId) }
        return StoredSnapshotMetadata(
            processId = snapshot.processId,
            version = snapshot.version,
            updatedAt = snapshot.updatedAt,
        )
    }

    override fun findByProcessId(processId: String): SerializedAgentProcessSnapshot? =
        region.get(processId)?.toSnapshot()

    override fun findByParentId(parentId: String): List<SerializedAgentProcessSnapshot> =
        parentIndex.members(parentId)
            .mapNotNull { findByProcessId(it) }

    override fun delete(processId: String) {
        // Read first so the parent index can be cleaned up. A missing entry is not
        // an error: delete is idempotent.
        region.get(processId)
            ?.metadata
            ?.get(PARENT_ID)
            ?.let { parentIndex.remove(it, processId) }
        region.remove(processId)
    }

    private fun SerializedAgentProcessSnapshot.toCachedValue(): CachedValue =
        CachedValue(
            payload = payload,
            version = version,
            contentType = contentType.toString(),
            metadata = buildMap {
                put(PROCESS_ID, processId)
                parentId?.let { put(PARENT_ID, it) }
                put(AGENT_NAME, agentName)
                put(STATUS, status.name)
                put(CREATED_AT, createdAt.toString())
                put(UPDATED_AT, updatedAt.toString())
                // Namespace caller metadata so it cannot collide with header keys.
                metadata.forEach { (key, value) -> put("$USER_PREFIX$key", value) }
            },
        )

    private fun CachedValue.toSnapshot(): SerializedAgentProcessSnapshot =
        SerializedAgentProcessSnapshot(
            processId = require(PROCESS_ID),
            parentId = metadata[PARENT_ID],
            agentName = require(AGENT_NAME),
            status = AgentProcessStatusCode.valueOf(require(STATUS)),
            version = version,
            contentType = MediaType.parseMediaType(contentType),
            payload = payload,
            createdAt = Instant.parse(require(CREATED_AT)),
            updatedAt = Instant.parse(require(UPDATED_AT)),
            metadata = metadata
                .filterKeys { it.startsWith(USER_PREFIX) }
                .mapKeys { (key, _) -> key.removePrefix(USER_PREFIX) },
        )

    private fun CachedValue.require(key: String): String =
        metadata[key]
            ?: throw AgentProcessPersistenceException(
                "Snapshot in region [${region.name}] is missing required metadata [$key]. " +
                        "The stored entry was not written by this store, or the payload format changed."
            )

    companion object {

        /**
         * Index name for parent-to-child process resolution.
         */
        const val PARENT_INDEX = "by-parent-id"

        private const val PROCESS_ID = "embabel.snapshot.processId"
        private const val PARENT_ID = "embabel.snapshot.parentId"
        private const val AGENT_NAME = "embabel.snapshot.agentName"
        private const val STATUS = "embabel.snapshot.status"
        private const val CREATED_AT = "embabel.snapshot.createdAt"
        private const val UPDATED_AT = "embabel.snapshot.updatedAt"
        private const val USER_PREFIX = "embabel.user."
    }
}
