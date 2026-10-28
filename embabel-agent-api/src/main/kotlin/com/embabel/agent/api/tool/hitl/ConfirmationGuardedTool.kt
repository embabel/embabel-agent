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
package com.embabel.agent.api.tool.hitl

import com.embabel.agent.api.tool.DelegatingTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.hitl.AwaitableResponseException
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.function.Function

/**
 * Tool decorator that requires the human's confirmation before the delegate runs,
 * and lets the LLM in the loop collect and record that confirmation.
 *
 * The guard's [definition] never changes. It is paired with a [verdictTool]
 * (`confirm_<toolName>`, see [ConfirmationVerdictTool]) through which the LLM records
 * the user's yes or no. Pass both to the prompt runner, see [tools].
 *
 * All state lives on the blackboard as [ToolCallProposal], [ToolCallVerdict] and
 * [ToolCallOutcome] records keyed by tool name, so tool instances stay stateless and
 * several guarded tools can coexist in one loop.
 *
 * **What happens on a call.** The guard looks up the latest proposal recorded for this
 * tool and compares the call's arguments with it.
 *
 * No proposal yet, or the latest one is already closed:
 * - Record a new proposal and ask the user. In [ConfirmationMode.ASK_VIA_LLM] that means
 *   returning an instruction telling the LLM to ask the user and then call the verdict
 *   tool. In [ConfirmationMode.PAUSE_PROCESS] it means throwing
 *   [AwaitableResponseException] with a [ToolCallConfirmationRequest].
 *
 * The latest proposal is still waiting for the user's answer:
 * - Same arguments: the LLM called again before the user answered. Repeat the
 *   instruction. No new proposal, and in pause mode no second pause.
 * - Different arguments: the LLM changed the call before the user answered, for example
 *   after the user corrected a detail. Abandon the waiting proposal (closed, not
 *   executed), record a new one for the new arguments and ask the user about those.
 *
 * The user accepted the latest proposal:
 * - Same arguments: run the delegate with the confirmed arguments, close the proposal,
 *   return the result.
 * - Different arguments: abandon the accepted proposal (closed, not executed) and record
 *   a new one. Arguments the user did not see are never executed.
 *
 * The user declined the latest proposal:
 * - Close it and tell the LLM the user declined. A later call, same arguments or not,
 *   is a new proposal.
 *
 * A confirmation is single use. Once consumed, the same call needs a new confirmation.
 * If the delegate throws or returns a [Tool.Result.Error], no outcome is recorded and the
 * accepted proposal remains executable, either through the verdict tool again or through
 * the next identical call.
 *
 * Kotlin callers typically use [withLlmConfirmation]; Java callers use [of].
 *
 * @param delegate The tool to guard
 * @param messageProvider Builds the human-facing confirmation message from the raw input
 * @param mode How the proposal reaches the human
 * @param options Wording and naming knobs, see [ConfirmationGuardOptions]
 */
@ApiStatus.Experimental
class ConfirmationGuardedTool @JvmOverloads constructor(
    override val delegate: Tool,
    private val messageProvider: (String) -> String,
    val mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
    val options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
) : DelegatingTool {

    private val logger = LoggerFactory.getLogger(ConfirmationGuardedTool::class.java)

    override val definition: Tool.Definition = Tool.Definition(
        name = delegate.definition.name,
        description = listOf(delegate.definition.description, options.confirmationNote)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" "),
        inputSchema = delegate.definition.inputSchema,
        metadata = delegate.definition.metadata,
    )

    override val metadata: Tool.Metadata = delegate.metadata

    /**
     * Tool through which the LLM records the user's verdict. Must be made available
     * to the LLM alongside this guard.
     */
    val verdictTool: Tool = ConfirmationVerdictTool(this)

    /**
     * This guard and its [verdictTool], for `PromptRunner.withTools(List)`.
     */
    fun tools(): List<Tool> = listOf(this, verdictTool)

    override fun call(input: String, context: ToolCallContext): Tool.Result {
        val agentProcess = requireAgentProcess()
        val blackboard = agentProcess.blackboard
        val state = ConfirmationState.of(blackboard, definition.name)
        val arguments = ToolArgumentsCanonicalizer.canonicalize(input)
        val proposal = state.proposal
        val same = proposal != null && proposal.arguments == arguments

        return when {
            // Nothing open for this tool: start a proposal
            proposal == null || state.outcome != null -> propose(blackboard, input, arguments)

            // Waiting for the user
            state.verdict == null ->
                if (same) pending(proposal) else supersede(blackboard, proposal, input, arguments)

            // User said yes: only the arguments they saw may run
            state.verdict.accepted ->
                if (same) execute(blackboard, proposal, context) else supersede(blackboard, proposal, input, arguments)

            // User said no
            else -> {
                val declined = decline(blackboard, proposal)
                if (same) declined else propose(blackboard, input, arguments)
            }
        }
    }

    /**
     * Record the LLM's verdict on the latest proposal and act on it.
     * Called by [ConfirmationVerdictTool].
     *
     * A proposal the user already accepted but which has no outcome yet, because the
     * delegate failed, is still actionable: accepting again retries the delegate without
     * recording a second verdict, declining cancels it. A declined verdict is final here
     * so the LLM cannot override a decline the user gave in a UI.
     */
    internal fun recordVerdict(
        accepted: Boolean,
        note: String?,
        context: ToolCallContext,
    ): Tool.Result {
        val blackboard = requireAgentProcess().blackboard
        val state = ConfirmationState.of(blackboard, definition.name)
        val proposal = state.proposal
        if (proposal == null || state.outcome != null) {
            return Tool.Result.error(
                "No confirmation is pending for '${definition.name}'. Call '${definition.name}' first to propose the action."
            )
        }
        val previous = state.verdict
        if (previous != null && !previous.accepted) {
            return Tool.Result.error(
                "The user already declined '${definition.name}'. Call '${definition.name}' again to propose a new action."
            )
        }
        if (previous != null && accepted) {
            logger.debug("Tool '{}': retrying accepted proposal {} after a failed execution", definition.name, proposal.id)
            return execute(blackboard, proposal, context)
        }
        blackboard += ToolCallVerdict(
            proposalId = proposal.id,
            accepted = accepted,
            source = VerdictSource.LLM,
            note = note,
        )
        logger.debug("Tool '{}': LLM recorded verdict accepted={} for proposal {}", definition.name, accepted, proposal.id)
        return if (accepted) execute(blackboard, proposal, context) else decline(blackboard, proposal)
    }

    /**
     * Close [proposal] unexecuted, then open a new one for [arguments].
     * Used when the LLM calls with different arguments while [proposal] is still open.
     */
    private fun supersede(
        blackboard: Blackboard,
        proposal: ToolCallProposal,
        input: String,
        arguments: String,
    ): Tool.Result {
        logger.debug("Tool '{}': proposal {} superseded by a call with different arguments", definition.name, proposal.id)
        blackboard += ToolCallOutcome(proposalId = proposal.id, executed = false)
        return propose(blackboard, input, arguments)
    }

    /**
     * Record a new proposal for [arguments] and hand it to the user according to [mode].
     * [input] is the raw tool input, passed to the message provider as the LLM sent it.
     */
    private fun propose(
        blackboard: Blackboard,
        input: String,
        arguments: String,
    ): Tool.Result {
        val proposal = ToolCallProposal(
            toolName = definition.name,
            arguments = arguments,
            message = messageProvider(input),
        )
        blackboard += proposal
        logger.debug("Tool '{}': proposal {} awaiting confirmation ({})", definition.name, proposal.id, mode)
        return when (mode) {
            ConfirmationMode.PAUSE_PROCESS -> throw AwaitableResponseException(ToolCallConfirmationRequest(proposal))
            ConfirmationMode.ASK_VIA_LLM -> pending(proposal)
        }
    }

    /**
     * The result the LLM sees while [proposal] awaits the user's answer.
     * Tells it to ask the user and then call the verdict tool.
     */
    private fun pending(proposal: ToolCallProposal): Tool.Result =
        Tool.Result.text(
            """
            Confirmation required before '${definition.name}' can run.
            Ask the user to confirm: ${proposal.message}
            Proposed arguments: ${proposal.arguments}
            When the user has answered, call '${verdictTool.definition.name}' with accepted=true or accepted=false.
            Do not call '${definition.name}' again for this request.
            """.trimIndent()
        )

    /**
     * Run the delegate with the confirmed arguments of [proposal] and close the proposal.
     * If the delegate throws or returns an error result, no outcome is recorded and the
     * proposal stays executable.
     */
    private fun execute(
        blackboard: Blackboard,
        proposal: ToolCallProposal,
        context: ToolCallContext,
    ): Tool.Result {
        logger.debug("Tool '{}': executing confirmed proposal {}", definition.name, proposal.id)
        val result = delegate.call(proposal.arguments, context)
        if (result is Tool.Result.Error) {
            logger.debug("Tool '{}': delegate failed for proposal {}, it stays executable", definition.name, proposal.id)
            return result
        }
        blackboard += ToolCallOutcome(proposalId = proposal.id, executed = true)
        return result
    }

    /**
     * Close [proposal] without running the delegate and tell the LLM not to retry.
     */
    private fun decline(
        blackboard: Blackboard,
        proposal: ToolCallProposal,
    ): Tool.Result {
        logger.debug("Tool '{}': proposal {} declined by the user", definition.name, proposal.id)
        blackboard += ToolCallOutcome(proposalId = proposal.id, executed = false)
        return Tool.Result.text(
            "The user declined '${definition.name}' with arguments ${proposal.arguments}. It was not executed. " +
                    "Do not retry with the same arguments unless the user asks."
        )
    }

    private fun requireAgentProcess(): AgentProcess =
        AgentProcess.get()
            ?: throw IllegalStateException("No AgentProcess available for ConfirmationGuardedTool '${definition.name}'")

    /**
     * Where one tool's confirmation stands: its latest proposal and, if any,
     * the verdict and outcome recorded for that proposal.
     */
    private data class ConfirmationState(
        val proposal: ToolCallProposal?,
        val verdict: ToolCallVerdict?,
        val outcome: ToolCallOutcome?,
    ) {
        companion object {
            /**
             * Read the state for [toolName] from the blackboard.
             * The blackboard is append-only, so the last record of each kind is the current one.
             */
            fun of(
                blackboard: Blackboard,
                toolName: String,
            ): ConfirmationState {
                // Only the newest proposal for this tool matters. Older ones were
                // executed, declined or superseded, and each of those has an outcome.
                val proposal = blackboard.objectsOfType(ToolCallProposal::class.java)
                    .lastOrNull { it.toolName == toolName }
                    ?: return ConfirmationState(null, null, null)
                // Verdict and outcome are matched to that proposal by id, so records
                // for other tools or earlier proposals are never picked up
                return ConfirmationState(
                    proposal = proposal,
                    verdict = blackboard.objectsOfType(ToolCallVerdict::class.java)
                        .lastOrNull { it.proposalId == proposal.id },
                    outcome = blackboard.objectsOfType(ToolCallOutcome::class.java)
                        .lastOrNull { it.proposalId == proposal.id },
                )
            }
        }
    }

    companion object {

        /**
         * Guard [tool] with a static confirmation [message]. Java-friendly entry point;
         * Kotlin callers typically use [withLlmConfirmation].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            tool: Tool,
            message: String,
            mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
            options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
        ): ConfirmationGuardedTool = ConfirmationGuardedTool(tool, { message }, mode, options)

        /**
         * Guard [tool], building the confirmation message from the raw input with [messageProvider].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            tool: Tool,
            messageProvider: Function<String, String>,
            mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
            options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
        ): ConfirmationGuardedTool = ConfirmationGuardedTool(tool, { messageProvider.apply(it) }, mode, options)
    }
}
