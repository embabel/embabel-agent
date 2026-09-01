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
import com.embabel.agent.core.AgentProcessCallback
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.persistence.AgentProcessPersistenceException
import com.embabel.agent.core.support.AbstractAgentProcess
import com.embabel.agent.core.support.ConcurrentAgentProcess
import com.embabel.agent.core.support.InternalAgentStateApi
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.spi.PlannerFactory
import com.embabel.agent.spi.support.DefaultPlannerFactory

/**
 * Rebuilds an [AgentProcess] from a structured snapshot.
 *
 * Restore is owned by persistence support code: core process constructors remain
 * persistence-agnostic, and runtime state mutation goes through the opt-in
 * internal process-state API.
 */
@OptIn(InternalAgentStateApi::class)
internal class AgentProcessSnapshotRestorer(
    private val blackboardSnapshotter: InMemoryBlackboardSnapshotter,
    private val processRestorerFactories: List<AgentProcessRestorerFactory> = listOf(
        SimpleAgentProcessRestorerFactory(),
        ConcurrentAgentProcessRestorerFactory(),
    ),
    private val plannerFactory: PlannerFactory = DefaultPlannerFactory,
) {

    fun restore(
        snapshot: AgentProcessSnapshot,
        agents: Collection<Agent>,
        platformServices: PlatformServices,
    ): AgentProcess {
        // Snapshots store the stable agent name, not a serialized Agent object.
        // Restore must bind the process to the currently registered definition.
        val agent = agents.firstOrNull { it.name == snapshot.agentName }
            ?: throw AgentProcessPersistenceException(
                "Cannot restore agent process [${snapshot.processId}]: agent [${snapshot.agentName}] not found"
            )

        // Process constructors require a blackboard, so rebuild it before
        // selecting and invoking the process implementation restorer.
        val blackboard = blackboardSnapshotter.restore(
            snapshot = snapshot.blackboard,
            processId = snapshot.processId,
        )

        // Different AgentProcess implementations have different constructors.
        // The snapshot records the original implementation class so we can pick
        // the matching internal factory and preserve the process shape.
        val process = processRestorerFactories
            .firstOrNull { it.supports(snapshot.processImplementationClassName) }
            ?.restore(
                snapshot = snapshot,
                agent = agent,
                blackboard = blackboard,
                platformServices = platformServices,
                plannerFactory = plannerFactory,
            )
            ?: throw AgentProcessPersistenceException(
                "Cannot restore agent process [${snapshot.processId}]: no restorer for " +
                        "process implementation [${snapshot.processImplementationClassName}]"
            )

        process.replaceRuntimeState(
            status = snapshot.status,
            history = snapshot.history,
        )
        return process
    }
}

internal interface AgentProcessRestorerFactory {

    fun supports(processImplementationClassName: String): Boolean

    fun restore(
        snapshot: AgentProcessSnapshot,
        agent: Agent,
        blackboard: Blackboard,
        platformServices: PlatformServices,
        plannerFactory: PlannerFactory,
    ): AbstractAgentProcess
}

internal class SimpleAgentProcessRestorerFactory : AgentProcessRestorerFactory {

    override fun supports(processImplementationClassName: String): Boolean =
        processImplementationClassName == SimpleAgentProcess::class.java.name

    override fun restore(
        snapshot: AgentProcessSnapshot,
        agent: Agent,
        blackboard: Blackboard,
        platformServices: PlatformServices,
        plannerFactory: PlannerFactory,
    ): AbstractAgentProcess =
        SimpleAgentProcess(
            id = snapshot.processId,
            parentId = snapshot.parentId,
            agent = agent,
            processOptions = snapshot.processOptions.toProcessOptions(),
            blackboard = blackboard,
            platformServices = platformServices,
            plannerFactory = plannerFactory,
            timestamp = snapshot.timestamp,
        )
}

internal class ConcurrentAgentProcessRestorerFactory(
    // TODO: Production/autoconfiguration wiring should supply the same
    // AgentProcessCallback providers used by newly created processes.
    private val callbacks: () -> List<AgentProcessCallback> = { emptyList() },
) : AgentProcessRestorerFactory {

    override fun supports(processImplementationClassName: String): Boolean =
        processImplementationClassName == ConcurrentAgentProcess::class.java.name

    override fun restore(
        snapshot: AgentProcessSnapshot,
        agent: Agent,
        blackboard: Blackboard,
        platformServices: PlatformServices,
        plannerFactory: PlannerFactory,
    ): AbstractAgentProcess =
        ConcurrentAgentProcess(
            id = snapshot.processId,
            parentId = snapshot.parentId,
            agent = agent,
            processOptions = snapshot.processOptions.toProcessOptions(),
            blackboard = blackboard,
            platformServices = platformServices,
            plannerFactory = plannerFactory,
            timestamp = snapshot.timestamp,
            callbacks = callbacks(),
        )
}
