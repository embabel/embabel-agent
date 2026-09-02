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

import com.embabel.agent.core.ActionInvocation
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.Delay
import com.embabel.agent.core.ProcessControl
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.core.persistence.SerializedBlackboardValue
import com.embabel.agent.api.common.PlannerType
import java.time.Instant

/**
 * Structured representation of an agent process checkpoint before serialization.
 *
 * This is support-layer model, not application API. It captures framework-owned
 * state needed to rebuild a parked process from durable value state.
 */
internal data class AgentProcessSnapshot(
    val processId: String,
    val parentId: String?,
    val agentName: String,
    /**
     * Internal discriminator used to choose the process restorer.
     *
     * Existing snapshots depend on this JVM class name. Moving or renaming a
     * process implementation requires migration support for stored snapshots.
     */
    val processImplementationClassName: String,
    val status: AgentProcessStatusCode,
    val version: Long,
    val timestamp: Instant,
    val processOptions: ProcessOptionsSnapshot,
    val blackboard: BlackboardSnapshot,
    val pendingAwaitableId: String?,
    val history: List<ActionInvocation> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Value-style subset of [ProcessOptions] safe to include in a durable snapshot.
 *
 * Runtime collaborators such as listeners, output channels, blackboard instances,
 * and early termination strategy objects are intentionally excluded. Restore
 * should rebuild those from the current application context and platform defaults.
 */
internal data class ProcessOptionsSnapshot(
    val contextId: String?,
    val verbosity: Verbosity,
    val budget: Budget,
    val toolDelay: Delay,
    val operationDelay: Delay,
    val prune: Boolean,
    val ephemeral: Boolean,
    val plannerType: PlannerType,
) {

    companion object {

        fun from(processOptions: ProcessOptions): ProcessOptionsSnapshot =
            ProcessOptionsSnapshot(
                contextId = processOptions.contextId?.value,
                verbosity = processOptions.verbosity,
                budget = processOptions.budget,
                toolDelay = processOptions.processControl.toolDelay,
                operationDelay = processOptions.processControl.operationDelay,
                prune = processOptions.prune,
                ephemeral = processOptions.ephemeral,
                plannerType = processOptions.plannerType,
            )
    }

    fun toProcessOptions(): ProcessOptions =
        ProcessOptions(
            contextId = contextId?.let(::ContextId),
            verbosity = verbosity,
            budget = budget,
            processControl = ProcessControl(
                toolDelay = toolDelay,
                operationDelay = operationDelay,
                earlyTerminationPolicy = budget.earlyTerminationPolicy(),
            ),
            prune = prune,
            ephemeral = ephemeral,
            plannerType = plannerType,
        )
}

/**
 * Structured blackboard checkpoint preserving visible entries and named bindings.
 *
 * The snapshot keeps ordered entries separate from named bindings because a
 * blackboard can contain unnamed objects, named values, and repeated values of
 * the same type.
 */
internal data class BlackboardSnapshot(
    val blackboardId: String,
    val entries: List<BlackboardEntrySnapshot>,
    val bindings: Map<String, BlackboardBindingSnapshot>,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Ordered blackboard entry.
 *
 * Stable identifiers and sequence numbers are included now so future audit can
 * describe blackboard changes between checkpoints without changing this shape.
 */
internal data class BlackboardEntrySnapshot(
    val entryId: String,
    val sequence: Long,
    val value: SerializedBlackboardValue,
    val sourceActionName: String? = null,
    val timestamp: Instant? = null,
    val hidden: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Named binding in the blackboard expression model.
 *
 * [entryId] links the binding to an ordered entry when the binding was created
 * by adding that same value to the blackboard entries list.
 */
internal data class BlackboardBindingSnapshot(
    val key: String,
    val value: SerializedBlackboardValue,
    val entryId: String? = null,
    val protected: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)
