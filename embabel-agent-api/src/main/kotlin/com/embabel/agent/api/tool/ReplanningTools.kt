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

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.BlackboardUpdater
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.support.DelegatingTool

/**
 * Callback to update the blackboard with tool result content.
 * Defined as a fun interface for Java interoperability.
 */
fun interface ReplanningToolBlackboardUpdater {
    /**
     * Update the blackboard with the tool result content.
     *
     * @param blackboard The blackboard to update
     * @param resultContent The text content from the tool result
     */
    fun update(blackboard: Blackboard, resultContent: String)
}

/**
 * Tool decorator that executes the wrapped tool, adds its result to the blackboard,
 * then throws [ReplanRequestedException] to terminate the tool loop and
 * trigger replanning.
 *
 * This enables patterns like:
 * - Chat routing: A routing tool classifies intent and triggers replan to switch handlers
 * - Discovery: A tool discovers information that requires a different plan
 *
 * Note: This tool accesses [AgentProcess] via thread-local at call time, which is set
 * by the decorator chain.
 *
 * @param delegate The tool to wrap
 * @param reason Human-readable explanation of why replan is needed
 * @param blackboardUpdater Callback to update the blackboard before replanning.
 *        Receives the result content and can add objects to the blackboard.
 *        Defaults to adding the result content as a string.
 */
class ReplanningTool @JvmOverloads constructor(
    override val delegate: Tool,
    private val reason: String,
    private val blackboardUpdater: ReplanningToolBlackboardUpdater = ReplanningToolBlackboardUpdater { bb, content -> bb.addObject(content) },
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val result = delegate.call(input)
        val resultContent = result.content

        throw ReplanRequestedException(
            reason = reason,
            blackboardUpdater = { bb -> blackboardUpdater.update(bb, resultContent) },
        )
    }
}

/**
 * Decision returned by [ReplanDecider] to indicate whether replanning is needed.
 *
 * @param reason Human-readable explanation of why replan is needed
 * @param blackboardUpdater Callback to update the blackboard before replanning
 */
data class ReplanDecision @JvmOverloads constructor(
    val reason: String,
    val blackboardUpdater: BlackboardUpdater = BlackboardUpdater {},
)

/**
 * Context provided to [ReplanDecider] for making replanning decisions.
 *
 * @param result The full result returned by the tool
 * @param agentProcess The current agent process
 * @param tool Info about the tool that was called
 */
data class ReplanContext(
    val result: Tool.Result,
    val agentProcess: AgentProcess,
    val tool: ToolInfo,
) {
    /** The text content of the result */
    val resultContent: String
        get() = result.content

    /** The artifact if this is a [Tool.Result.WithArtifact], null otherwise */
    val artifact: Any?
        get() = (result as? Tool.Result.WithArtifact)?.artifact

    /** Get the artifact cast to a specific type, or null if not present or wrong type */
    inline fun <reified T> artifactAs(): T? = artifact as? T
}

/**
 * Functional interface for deciding whether to trigger replanning based on tool results.
 *
 * Implementations inspect the [ReplanContext] and return either:
 * - A [ReplanDecision] to trigger replanning with the specified reason and blackboard updates
 * - `null` to continue normally and return the tool result
 */
fun interface ReplanDecider {
    /**
     * Evaluate whether replanning is needed based on the tool result context.
     *
     * @param context The context containing result, agent process, and tool metadata
     * @return A [ReplanDecision] to trigger replanning, or null to continue normally
     */
    fun evaluate(context: ReplanContext): ReplanDecision?
}

/**
 * Tool decorator that executes the wrapped tool, then conditionally triggers replanning
 * based on the result.
 *
 * Unlike [ReplanningTool] which always triggers replanning, this tool allows the [ReplanDecider]
 * to inspect the result and decide whether to replan.
 *
 * Note: This tool accesses [AgentProcess] via thread-local at call time, which is set
 * by the decorator chain.
 *
 * @param delegate The tool to wrap
 * @param decider Decider that inspects the result context and determines whether to replan
 */
class ConditionalReplanningTool(
    override val delegate: Tool,
    private val decider: ReplanDecider,
) : DelegatingTool {

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        val result = delegate.call(input)

        val agentProcess = AgentProcess.get()
            ?: throw IllegalStateException("No AgentProcess available for ConditionalReplanningTool")

        val context = ReplanContext(
            result = result,
            agentProcess = agentProcess,
            tool = delegate,
        )

        val decision = decider.evaluate(context)
        if (decision != null) {
            throw ReplanRequestedException(
                reason = decision.reason,
                blackboardUpdater = { bb -> decision.blackboardUpdater.accept(bb) },
            )
        }

        return result
    }
}

/**
 * Extension to get the content string from any Tool.Result variant.
 */
private val Tool.Result.content: String
    get() = when (this) {
        is Tool.Result.Text -> content
        is Tool.Result.WithArtifact -> content
        is Tool.Result.Error -> message
    }
