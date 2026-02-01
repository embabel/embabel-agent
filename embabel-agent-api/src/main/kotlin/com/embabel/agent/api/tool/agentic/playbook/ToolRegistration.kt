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
import kotlin.reflect.KClass

/**
 * Intermediate registration object returned by [PlaybookTool.withTool].
 *
 * Supports both Kotlin curried syntax and Java fluent API:
 *
 * ```kotlin
 * // Kotlin curried syntax
 * .withTool(analyzeTool)(searchTool)
 *
 * // Kotlin/Java fluent syntax
 * .withTool(analyzeTool).unlockedBy(searchTool)
 * ```
 */
class ToolRegistration internal constructor(
    private val tool: Tool,
    private val playbook: PlaybookTool,
) {
    /**
     * Kotlin currying operator - unlock after specified prerequisite tool(s).
     * When multiple tools are specified, ALL must be called (AND logic).
     *
     * Example:
     * ```kotlin
     * .withTool(analyzeTool)(searchTool)              // single prerequisite
     * .withTool(reportTool)(searchTool, analyzeTool)  // ALL must be called
     * ```
     */
    operator fun invoke(vararg prerequisites: Tool): PlaybookTool =
        if (prerequisites.size == 1) {
            unlockedBy(prerequisites[0])
        } else {
            unlockedByAll(*prerequisites)
        }

    /**
     * Kotlin currying operator - unlock when artifact type is produced.
     *
     * Example:
     * ```kotlin
     * .withTool(summarizeTool)(Document::class)
     * ```
     */
    operator fun invoke(artifactType: KClass<*>): PlaybookTool =
        unlockedByArtifact(artifactType.java)

    /**
     * Unlock after a single prerequisite tool has been called.
     *
     * Example:
     * ```java
     * .withTool(analyzeTool).unlockedBy(searchTool)
     * ```
     */
    fun unlockedBy(prerequisite: Tool): PlaybookTool {
        val condition = UnlockCondition.AfterTools(prerequisite.definition.name)
        return playbook.addLockedTool(tool, condition)
    }

    /**
     * Unlock after ALL specified prerequisite tools have been called (AND logic).
     *
     * Example:
     * ```java
     * .withTool(reportTool).unlockedByAll(searchTool, analyzeTool)
     * ```
     */
    fun unlockedByAll(vararg prerequisites: Tool): PlaybookTool {
        val condition = UnlockCondition.AfterTools(
            prerequisites.map { it.definition.name }
        )
        return playbook.addLockedTool(tool, condition)
    }

    /**
     * Unlock after ANY of the specified prerequisite tools has been called (OR logic).
     *
     * Example:
     * ```java
     * .withTool(processTool).unlockedByAny(searchTool, fetchTool)
     * ```
     */
    fun unlockedByAny(vararg prerequisites: Tool): PlaybookTool {
        val conditions = prerequisites.map {
            UnlockCondition.AfterTools(it.definition.name)
        }
        val condition = UnlockCondition.AnyOf(conditions)
        return playbook.addLockedTool(tool, condition)
    }

    /**
     * Unlock when an artifact of the specified type is produced.
     *
     * Example:
     * ```java
     * .withTool(summarizeTool).unlockedByArtifact(Document.class)
     * ```
     */
    fun unlockedByArtifact(artifactType: Class<*>): PlaybookTool {
        val condition = UnlockCondition.OnArtifact(artifactType)
        return playbook.addLockedTool(tool, condition)
    }

    /**
     * Unlock when any artifact matches the given predicate.
     *
     * Example:
     * ```java
     * .withTool(processTool).unlockedByArtifactMatching(a -> a instanceof Document && ((Document) a).isValid())
     * ```
     */
    fun unlockedByArtifactMatching(predicate: java.util.function.Predicate<Any>): PlaybookTool {
        val condition = UnlockCondition.WhenPredicate { ctx ->
            ctx.artifacts.any { predicate.test(it) }
        }
        return playbook.addLockedTool(tool, condition)
    }

    /**
     * Unlock when a custom condition is met.
     *
     * Example:
     * ```java
     * .withTool(actionTool).unlockedWhen(ctx -> ctx.getIterationCount() > 2)
     * ```
     */
    fun unlockedWhen(condition: UnlockCondition): PlaybookTool =
        playbook.addLockedTool(tool, condition)

    /**
     * Unlock when a predicate returns true.
     *
     * Example:
     * ```kotlin
     * .withTool(actionTool).unlockedWhen { it.calledToolNames.size >= 2 }
     * ```
     */
    fun unlockedWhen(predicate: (PlaybookContext) -> Boolean): PlaybookTool =
        playbook.addLockedTool(tool, UnlockCondition.WhenPredicate(predicate))

    /**
     * Unlock when an object of the specified type exists on the blackboard.
     *
     * Example:
     * ```java
     * .withTool(processTool).unlockedByBlackboard(Document.class)
     * ```
     */
    fun unlockedByBlackboard(type: Class<*>): PlaybookTool {
        val condition = UnlockCondition.WhenPredicate { ctx ->
            ctx.blackboard.objects.any { type.isInstance(it) }
        }
        return playbook.addLockedTool(tool, condition)
    }

    /**
     * Unlock when the blackboard matches a predicate.
     *
     * Example:
     * ```java
     * .withTool(actionTool).unlockedByBlackboardMatching(bb -> bb.get("ready") != null)
     * ```
     */
    fun unlockedByBlackboardMatching(predicate: java.util.function.Predicate<com.embabel.agent.core.Blackboard>): PlaybookTool {
        val condition = UnlockCondition.WhenPredicate { ctx ->
            predicate.test(ctx.blackboard)
        }
        return playbook.addLockedTool(tool, condition)
    }
}
