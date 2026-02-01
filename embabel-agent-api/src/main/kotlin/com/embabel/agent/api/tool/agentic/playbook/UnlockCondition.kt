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
package com.embabel.agent.api.tool.agentic.playbook

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Blackboard

/**
 * Represents a condition that must be met for a tool to be unlocked.
 */
sealed interface UnlockCondition {

    /**
     * Evaluate whether this condition is met given the current context.
     */
    fun isSatisfied(context: PlaybookContext): Boolean

    /**
     * Tool unlocks after specified prerequisite tools have been called.
     */
    data class AfterTools(
        val prerequisites: List<String>,
    ) : UnlockCondition {
        constructor(vararg prerequisites: String) : this(prerequisites.toList())

        override fun isSatisfied(context: PlaybookContext): Boolean =
            prerequisites.all { it in context.calledToolNames }
    }

    /**
     * Tool unlocks when an artifact of the specified type has been produced.
     */
    data class OnArtifact(
        val artifactType: Class<*>,
    ) : UnlockCondition {
        override fun isSatisfied(context: PlaybookContext): Boolean =
            context.artifacts.any { artifactType.isInstance(it) }
    }

    /**
     * Tool unlocks when a custom predicate returns true.
     */
    data class WhenPredicate(
        val predicate: (PlaybookContext) -> Boolean,
    ) : UnlockCondition {
        override fun isSatisfied(context: PlaybookContext): Boolean =
            predicate(context)
    }

    /**
     * Tool unlocks when all conditions are met.
     */
    data class AllOf(
        val conditions: List<UnlockCondition>,
    ) : UnlockCondition {
        constructor(vararg conditions: UnlockCondition) : this(conditions.toList())

        override fun isSatisfied(context: PlaybookContext): Boolean =
            conditions.all { it.isSatisfied(context) }
    }

    /**
     * Tool unlocks when any condition is met.
     */
    data class AnyOf(
        val conditions: List<UnlockCondition>,
    ) : UnlockCondition {
        constructor(vararg conditions: UnlockCondition) : this(conditions.toList())

        override fun isSatisfied(context: PlaybookContext): Boolean =
            conditions.any { it.isSatisfied(context) }
    }

    companion object {
        /**
         * Create a condition that unlocks after the specified tools have been called.
         */
        @JvmStatic
        fun afterTools(vararg toolNames: String): UnlockCondition =
            AfterTools(toolNames.toList())

        /**
         * Create a condition that unlocks after the specified tools have been called.
         */
        @JvmStatic
        fun afterTools(tools: List<Tool>): UnlockCondition =
            AfterTools(tools.map { it.definition.name })

        /**
         * Create a condition that unlocks when an artifact of the specified type is produced.
         */
        @JvmStatic
        fun onArtifact(artifactType: Class<*>): UnlockCondition =
            OnArtifact(artifactType)

        /**
         * Create a condition that unlocks when a custom predicate returns true.
         */
        @JvmStatic
        fun whenPredicate(predicate: (PlaybookContext) -> Boolean): UnlockCondition =
            WhenPredicate(predicate)
    }
}

/**
 * Context available for evaluating unlock conditions.
 */
data class PlaybookContext(
    /**
     * Names of tools that have been called so far.
     */
    val calledToolNames: Set<String>,

    /**
     * Artifacts produced by tools so far.
     */
    val artifacts: List<Any>,

    /**
     * Number of iterations completed.
     */
    val iterationCount: Int,

    /**
     * The process blackboard, for conditions that depend on process state.
     */
    val blackboard: Blackboard,
)
