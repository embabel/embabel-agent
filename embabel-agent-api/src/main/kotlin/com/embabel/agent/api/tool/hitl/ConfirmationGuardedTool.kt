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
import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.function.Function

/**
 * Tool decorator that requires the human's confirmation before the delegate runs,
 * and lets the LLM in the loop collect and record that confirmation.
 *
 * The guard's [definition] never changes. It is paired with a [verdictTool]
 * (`confirm_<toolName>`) through which the LLM records the user's yes or no.
 * Pass both to the prompt runner, see [tools].
 *
 * All state lives on the blackboard as [ToolCallProposal], [ToolCallVerdict] and
 * [ToolCallOutcome] records keyed by tool name, so tool instances stay stateless and
 * several guarded tools can coexist in one loop.
 *
 * **Lifecycle** for a call with arguments `A`:
 *
 * 1. No open proposal for this tool: record a proposal for `A`. In
 *    [ConfirmationMode.ASK_VIA_LLM] return an instruction telling the LLM to ask the
 *    user and then call the verdict tool. In [ConfirmationMode.PAUSE_PROCESS] throw
 *    [AwaitableResponseException] with a [ToolCallConfirmationRequest].
 * 2. Open proposal for `A`, no verdict yet: return the same instruction. Never pause again.
 * 3. Open proposal for different arguments: supersede it with a new proposal for `A`.
 * 4. Accepted verdict, same arguments, not yet consumed: run the delegate with the
 *    *confirmed* arguments, record the outcome, return the result.
 * 5. Accepted verdict but different arguments: treat as a new proposal. Arguments the user
 *    did not see are never executed.
 * 6. Declined verdict: record the outcome and report the decline. A later identical call
 *    starts a fresh proposal.
 *
 * A confirmation is single use. Once consumed, the same call needs a new confirmation.
 * If the delegate throws, no outcome is recorded and the accepted proposal remains
 * executable on the next identical call.
 *
 * @param delegate The tool to guard
 * @param messageProvider Builds the human-facing confirmation message from the raw input
 * @param mode How the proposal reaches the human
 * @param options Wording and naming knobs, see [ConfirmationGuardOptions]
 */
class ConfirmationGuardedTool @JvmOverloads constructor(
    override val delegate: Tool,
    private val messageProvider: (String) -> String,
    val mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
    val options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
) : DelegatingTool {

    private val logger = LoggerFactory.getLogger(ConfirmationGuardedTool::class.java)

    override val definition: Tool.Definition = Tool.Definition(
        name = delegate.definition.name,
        description = if (options.confirmationNote.isBlank()) {
            delegate.definition.description
        } else {
            delegate.definition.description.trimEnd() + " " + options.confirmationNote.trim()
        },
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
            proposal == null || state.outcome != null -> propose(blackboard, input, arguments)

            state.verdict == null ->
                if (same) pending(proposal) else propose(blackboard, input, arguments)

            state.verdict.accepted ->
                if (same) execute(blackboard, proposal, context) else propose(blackboard, input, arguments)

            else -> {
                val declined = decline(blackboard, proposal)
                if (same) declined else propose(blackboard, input, arguments)
            }
        }
    }

    /**
     * Record the LLM's verdict on the open proposal and act on it.
     * Called by [ConfirmationVerdictTool].
     */
    internal fun recordVerdict(
        accepted: Boolean,
        note: String?,
        context: ToolCallContext,
    ): Tool.Result {
        val blackboard = requireAgentProcess().blackboard
        val state = ConfirmationState.of(blackboard, definition.name)
        val proposal = state.proposal
        if (proposal == null || state.verdict != null || state.outcome != null) {
            return Tool.Result.error(
                "No confirmation is pending for '${definition.name}'. Call '${definition.name}' first to propose the action."
            )
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

    private fun execute(
        blackboard: Blackboard,
        proposal: ToolCallProposal,
        context: ToolCallContext,
    ): Tool.Result {
        logger.debug("Tool '{}': executing confirmed proposal {}", definition.name, proposal.id)
        val result = delegate.call(proposal.arguments, context)
        blackboard += ToolCallOutcome(proposalId = proposal.id, executed = true)
        return result
    }

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
     * Snapshot of the confirmation records relevant to one tool: the latest proposal
     * and, if any, its verdict and outcome.
     */
    private data class ConfirmationState(
        val proposal: ToolCallProposal?,
        val verdict: ToolCallVerdict?,
        val outcome: ToolCallOutcome?,
    ) {
        companion object {
            fun of(
                blackboard: Blackboard,
                toolName: String,
            ): ConfirmationState {
                val proposal = blackboard.objectsOfType(ToolCallProposal::class.java)
                    .lastOrNull { it.toolName == toolName }
                    ?: return ConfirmationState(null, null, null)
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

}

/**
 * Wording and naming options for [ConfirmationGuardedTool].
 * Immutable; derive variants with [withConfirmationNote] and [withVerdictToolPrefix] or `copy`.
 *
 * @param confirmationNote Sentence appended to the guarded tool's description. Blank means
 * the delegate's description is left untouched.
 * @param verdictToolPrefix Prefix for the verdict tool's name, followed by the guarded tool's
 * name. Must not be blank, or the two tools would share a name.
 */
data class ConfirmationGuardOptions @JvmOverloads constructor(
    val confirmationNote: String = DEFAULT_CONFIRMATION_NOTE,
    val verdictToolPrefix: String = DEFAULT_VERDICT_TOOL_PREFIX,
) {

    init {
        require(verdictToolPrefix.isNotBlank()) { "verdictToolPrefix must not be blank" }
    }

    fun withConfirmationNote(confirmationNote: String): ConfirmationGuardOptions =
        copy(confirmationNote = confirmationNote)

    fun withVerdictToolPrefix(verdictToolPrefix: String): ConfirmationGuardOptions =
        copy(verdictToolPrefix = verdictToolPrefix)

    companion object {
        const val DEFAULT_CONFIRMATION_NOTE = "Requires the user's confirmation before it takes effect."
        const val DEFAULT_VERDICT_TOOL_PREFIX = "confirm_"

        @JvmField
        val DEFAULT = ConfirmationGuardOptions()
    }
}

/**
 * Records the user's verdict on the open proposal of a [ConfirmationGuardedTool].
 * On acceptance it runs the guarded tool with the confirmed arguments and returns its result.
 */
class ConfirmationVerdictTool internal constructor(
    private val guard: ConfirmationGuardedTool,
) : Tool {

    private val objectMapper = ObjectMapper()

    override val definition: Tool.Definition = Tool.Definition(
        name = guard.options.verdictToolPrefix + guard.definition.name,
        description = "Record the user's answer to the pending confirmation for '${guard.definition.name}'. " +
                "Call this only after the user has explicitly confirmed or declined in the conversation. " +
                "accepted=true runs '${guard.definition.name}' exactly as it was proposed; accepted=false cancels it.",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter(
                name = "accepted",
                type = Tool.ParameterType.BOOLEAN,
                description = "true if the user confirmed, false if the user declined",
                required = true,
            ),
            Tool.Parameter(
                name = "note",
                type = Tool.ParameterType.STRING,
                description = "Optional: what the user said, in their own words",
                required = false,
            ),
        ),
    )

    override fun call(input: String): Tool.Result = call(input, ToolCallContext.EMPTY)

    override fun call(input: String, context: ToolCallContext): Tool.Result {
        val node = try {
            objectMapper.readTree(input)
        } catch (e: JacksonException) {
            return Tool.Result.error("Invalid input for '${definition.name}': ${e.message}", e)
        }
        val acceptedNode = node?.get("accepted")
        if (acceptedNode == null || !acceptedNode.isBoolean) {
            return Tool.Result.error("'${definition.name}' requires a boolean 'accepted' argument.")
        }
        val note = node.get("note")?.takeIf { it.isTextual }?.asString()
        return guard.recordVerdict(acceptedNode.asBoolean(), note, context)
    }
}

/**
 * Wrap this tool so the LLM must obtain the user's confirmation before it runs.
 * Remember to expose the guard's [ConfirmationGuardedTool.verdictTool] too.
 *
 * @param message Static confirmation message shown to the user
 * @param mode How the proposal reaches the human
 * @param options Wording and naming knobs
 */
@JvmOverloads
fun Tool.withLlmConfirmation(
    message: String,
    mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
    options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
): ConfirmationGuardedTool = ConfirmationGuardedTool(this, { message }, mode, options)

/**
 * Wrap this tool so the LLM must obtain the user's confirmation before it runs.
 * Remember to expose the guard's [ConfirmationGuardedTool.verdictTool] too.
 *
 * @param mode How the proposal reaches the human
 * @param options Wording and naming knobs
 * @param messageProvider Builds the confirmation message from the raw tool input
 */
fun Tool.withLlmConfirmation(
    mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
    options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
    messageProvider: (String) -> String,
): ConfirmationGuardedTool = ConfirmationGuardedTool(this, messageProvider, mode, options)

/**
 * Java entry point for [ConfirmationGuardedTool].
 */
object LlmConfirmation {

    @JvmStatic
    @JvmOverloads
    fun guard(
        tool: Tool,
        message: String,
        mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
        options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
    ): ConfirmationGuardedTool = ConfirmationGuardedTool(tool, { message }, mode, options)

    @JvmStatic
    @JvmOverloads
    fun guard(
        tool: Tool,
        messageProvider: Function<String, String>,
        mode: ConfirmationMode = ConfirmationMode.ASK_VIA_LLM,
        options: ConfirmationGuardOptions = ConfirmationGuardOptions.DEFAULT,
    ): ConfirmationGuardedTool = ConfirmationGuardedTool(tool, { messageProvider.apply(it) }, mode, options)
}
