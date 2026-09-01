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

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.hitl.Awaitable
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.support.InMemoryBlackboard

/**
 * Creates structured snapshots from agent processes at supported persistence boundaries.
 *
 * This factory supports [AgentProcessStatusCode.WAITING] processes, used for
 * durable HITL resume, and finished processes, used to advance durable state
 * after resumed work reaches a terminal lifecycle state. The repository
 * checkpoint policy decides when this factory is invoked.
 */
internal class AgentProcessSnapshotFactory(
    private val blackboardSnapshotter: InMemoryBlackboardSnapshotter,
) {

    fun snapshot(
        agentProcess: AgentProcess,
        version: Long,
    ): AgentProcessSnapshot {
        require(agentProcess.status == AgentProcessStatusCode.WAITING || agentProcess.finished) {
            "Only WAITING or finished agent processes can be snapshotted by the persistence factory: " +
                    "process [${agentProcess.id}] is [${agentProcess.status}]"
        }
        val blackboard = agentProcess.blackboard as? InMemoryBlackboard
            ?: throw AgentProcessPersistenceException(
                "Only InMemoryBlackboard is supported by the persistence factory: " +
                        "process [${agentProcess.id}] uses [${agentProcess.blackboard.javaClass.name}]"
            )
        val pendingAwaitableId =
            when (agentProcess.status) {
                AgentProcessStatusCode.WAITING ->
                    blackboard.objects
                        .filterIsInstance<Awaitable<*, *>>()
                        .lastOrNull()
                        ?.id
                        ?: throw AgentProcessPersistenceException(
                            "Cannot create snapshot for WAITING agent process [${agentProcess.id}]: " +
                                    "no pending Awaitable found"
                        )

                else -> null
            }

        return AgentProcessSnapshot(
            processId = agentProcess.id,
            parentId = agentProcess.parentId,
            agentName = agentProcess.agent.name,
            processImplementationClassName = agentProcess.javaClass.name,
            status = agentProcess.status,
            version = version,
            timestamp = agentProcess.timestamp,
            processOptions = ProcessOptionsSnapshot.from(agentProcess.processOptions),
            blackboard = blackboardSnapshotter.snapshot(
                blackboard = blackboard,
                processId = agentProcess.id,
            ),
            pendingAwaitableId = pendingAwaitableId,
            history = agentProcess.history,
        )
    }
}
