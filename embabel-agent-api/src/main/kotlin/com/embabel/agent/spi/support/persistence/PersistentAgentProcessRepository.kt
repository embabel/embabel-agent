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

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.AbstractAgentProcessRepository
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.spi.persistence.AgentProcessCheckpointPolicy
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore

/**
 * Repository decorator that keeps active processes in a runtime repository and
 * checkpoints durable process state through [AgentProcessSnapshotStore].
 *
 * The runtime repository preserves existing in-memory semantics for active
 * processes. The snapshot store is consulted when the runtime repository
 * misses, allowing another runtime to rebuild a process from durable state.
 *
 * This foundation keeps runtime save and snapshot save as separate operations.
 * The snapshot store's expected-version check prevents stale writes, but a
 * concurrent writer can still win between lookup and save, causing the store to
 * reject this checkpoint. Transactional multi-runtime semantics require a
 * persistent runtime repository and snapshot store that participate in the same
 * transaction boundary.
 */
internal class PersistentAgentProcessRepository(
    private val runtimeRepository: AgentProcessRepository,
    private val snapshotStore: AgentProcessSnapshotStore,
    private val checkpointPolicy: AgentProcessCheckpointPolicy,
    private val snapshotFactory: AgentProcessSnapshotFactory,
    private val snapshotSerializer: JacksonAgentProcessStateSerializer,
    private val snapshotRestorer: AgentProcessSnapshotRestorer,
    private val agents: () -> Collection<Agent>,
    private val platformServices: () -> PlatformServices,
) : AbstractAgentProcessRepository() {

    override fun findById(id: String): AgentProcess? =
        runtimeRepository.findById(id) ?: restore(snapshotStore.findByProcessId(id))

    override fun findByParentId(parentId: String): List<AgentProcess> {
        val runtimeChildren = runtimeRepository.findByParentId(parentId)
        val runtimeChildIds = runtimeChildren.mapTo(mutableSetOf()) { it.id }
        val restoredChildren = snapshotStore.findByParentId(parentId)
            .filterNot { it.processId in runtimeChildIds }
            .mapNotNull(::restore)
        return runtimeChildren + restoredChildren
    }

    override fun doSave(agentProcess: AgentProcess): AgentProcess {
        val saved = runtimeRepository.save(agentProcess)
        checkpointIfNeeded(saved)
        return saved
    }

    override fun doUpdate(agentProcess: AgentProcess) {
        runtimeRepository.update(agentProcess)
        checkpointIfNeeded(agentProcess)
    }

    override fun delete(agentProcess: AgentProcess) {
        runtimeRepository.delete(agentProcess)
        snapshotStore.delete(agentProcess.id)
    }

    private fun checkpointIfNeeded(agentProcess: AgentProcess) {
        if (!checkpointPolicy.shouldStoreSnapshot(agentProcess)) {
            return
        }
        val existing = snapshotStore.findByProcessId(agentProcess.id)
        val nextVersion = (existing?.version ?: 0) + 1
        val snapshot = snapshotFactory.snapshot(
            agentProcess = agentProcess,
            version = nextVersion,
        )
        snapshotStore.save(
            snapshot = snapshotSerializer.serialize(snapshot),
            expectedVersion = existing?.version,
        )
    }

    private fun restore(serialized: com.embabel.agent.spi.persistence.SerializedAgentProcessSnapshot?): AgentProcess? =
        serialized?.let {
            val restored = snapshotRestorer.restore(
                snapshot = snapshotSerializer.deserialize(it),
                agents = agents(),
                platformServices = platformServices(),
            )
            runtimeRepository.save(restored)
        }
}
