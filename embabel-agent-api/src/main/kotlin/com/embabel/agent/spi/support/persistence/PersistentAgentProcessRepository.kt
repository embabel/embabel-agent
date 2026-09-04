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
 * The snapshot store is always written before the runtime repository. If the
 * snapshot save fails, the runtime repository is not updated and the exception
 * propagates to the caller. Concurrent checkpoint conflicts are caught by the
 * store's expected-version check. These two writes are not atomic.
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
        runtimeRepository.findById(id) ?: restore(snapshotStore.findLatestByProcessId(id))

    override fun findByParentId(parentId: String): List<AgentProcess> {
        val runtimeChildren = runtimeRepository.findByParentId(parentId)
        val runtimeChildIds = runtimeChildren.mapTo(mutableSetOf()) { it.id }
        val restoredChildren = snapshotStore.findByParentId(parentId)
            .filterNot { it.processId in runtimeChildIds }
            .mapNotNull(::restore)
        return runtimeChildren + restoredChildren
    }

    override fun doSave(agentProcess: AgentProcess): AgentProcess {
        checkpointIfNeeded(agentProcess)
        return runtimeRepository.save(agentProcess)
    }

    override fun doUpdate(agentProcess: AgentProcess) {
        checkpointIfNeeded(agentProcess)
        runtimeRepository.update(agentProcess)
    }

    /**
     * Durable state is removed first, mirroring [doSave], which checkpoints before
     * registering. Removing the runtime entry first would leave a snapshot behind
     * whenever the snapshot delete failed, and [findById] would restore from it:
     * a deleted process would come back to life.
     */
    override fun delete(agentProcess: AgentProcess) {
        snapshotStore.delete(agentProcess.id)
        runtimeRepository.delete(agentProcess)
    }

    private fun checkpointIfNeeded(agentProcess: AgentProcess) {
        if (!checkpointPolicy.shouldStoreSnapshot(agentProcess)) {
            return
        }
        val existing = snapshotStore.findLatestByProcessId(agentProcess.id)
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

    // After the first restore the process is cached in the runtime repository, so
    // subsequent findById calls will not reach here. Two concurrent misses can both
    // restore and save the same snapshot; the redundant save is harmless because the
    // runtime repository stores by process id and the restored state is identical.
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
