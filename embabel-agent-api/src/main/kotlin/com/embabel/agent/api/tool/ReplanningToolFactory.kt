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
package com.embabel.agent.api.tool

/**
 * Factory interface for creating replanning tool decorators.
 * Extended by [Tool.Companion] to provide [Tool.replanAlways], [Tool.replanWhen], etc.
 */
interface ReplanningToolFactory {

    /**
     * Make this tool always replan after execution, adding the artifact to the blackboard.
     */
    fun replanAlways(tool: Tool): Tool {
        return ConditionalReplanningTool(tool) { context ->
            ReplanDecision("${tool.definition.name} replans") { bb ->
                context.artifact?.let { bb.addObject(it) }
            }
        }
    }

    /**
     * When the decider returns a [ReplanDecision], replan after execution, adding the artifact
     * to the blackboard along with any additional updates from the decision.
     * The decider receives the artifact cast to type T and the replan context.
     * If the artifact is null or cannot be cast to T, the decider is not called.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> conditionalReplan(
        tool: Tool,
        decider: (t: T, replanContext: ReplanContext) -> ReplanDecision?,
    ): DelegatingTool {
        return ConditionalReplanningTool(tool) { replanContext ->
            val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
            try {
                val decision = decider(artifact as T, replanContext)
                    ?: return@ConditionalReplanningTool null
                ReplanDecision(decision.reason) { bb ->
                    bb.addObject(artifact)
                    decision.blackboardUpdater.accept(bb)
                }
            } catch (_: ClassCastException) {
                null
            }
        }
    }

    /**
     * When the predicate matches the tool result artifact, replan, adding the artifact to the blackboard.
     * The predicate receives the artifact cast to type T.
     * If the artifact is null or cannot be cast to T, returns normally.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> replanWhen(
        tool: Tool,
        predicate: (t: T) -> Boolean,
    ): DelegatingTool {
        return ConditionalReplanningTool(tool) { replanContext ->
            val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
            try {
                if (predicate(artifact as T)) {
                    ReplanDecision("${tool.definition.name} replans based on result") { bb ->
                        bb.addObject(artifact)
                    }
                } else {
                    null
                }
            } catch (_: ClassCastException) {
                null
            }
        }
    }

    /**
     * Replan and add the object returned by the valueComputer to the blackboard.
     * @param tool The tool to wrap
     * @param valueComputer Function that takes the artifact of type T and returns an object
     *        to add to the blackboard, or null to not replan
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> replanAndAdd(
        tool: Tool,
        valueComputer: (t: T) -> Any?,
    ): DelegatingTool {
        return ConditionalReplanningTool(tool) { replanContext ->
            val artifact = replanContext.artifact ?: return@ConditionalReplanningTool null
            try {
                val toAdd = valueComputer(artifact as T)
                if (toAdd != null) {
                    ReplanDecision("${tool.definition.name} replans based on result") { bb ->
                        bb.addObject(toAdd)
                    }
                } else {
                    null
                }
            } catch (_: ClassCastException) {
                null
            }
        }
    }
}
