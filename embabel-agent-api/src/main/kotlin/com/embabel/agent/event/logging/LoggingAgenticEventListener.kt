/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.event.logging

import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.event.*
import com.embabel.agent.event.logging.personality.ColorPalette
import com.embabel.agent.event.logging.personality.DefaultColorPalette
import com.embabel.agent.event.logging.personality.severance.LumonColorPalette
import com.embabel.agent.spi.support.springai.ChatModelCallEvent
import com.embabel.common.util.AnsiColor
import com.embabel.common.util.color
import com.embabel.common.util.trim
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.prompt.Prompt

interface LoggingPersonality {

    /**
     * The color palette to use for this personality
     */
    val colorPalette: ColorPalette

    /**
     * The logger to use for this personality
     */
    val logger: Logger

    val bannerWidth: Int get() = BANNER_WIDTH

    fun lineSeparator(text: String, bannerChar: String, glyph: String = " ⇩  "): String =
        Companion.lineSeparator(text, bannerChar, glyph)

    companion object {
        const val BANNER_WIDTH = 100

        /**
         * A line separator beginning with the text
         */
        private fun lineSeparator(text: String, bannerChar: String, glyph: String = " ⇩  "): String {
            if (text.isBlank()) {
                return bannerChar.repeat(BANNER_WIDTH)
            }
            return text + glyph + bannerChar.repeat(BANNER_WIDTH - text.length - glyph.length)
        }
    }
}

/**
 * Default implementation of the AgenticEventListener
 * with vanilla messages.
 * Subclasses can pass in format Strings for messages they wish to override
 * Messages must respect format variables
 * @param url url explaining this particular logger if appropriate
 */
open class LoggingAgenticEventListener(
    url: String? = null,
    welcomeMessage: String? = null,
    override val logger: Logger = LoggerFactory.getLogger("Embabel"),
    override val colorPalette: ColorPalette = DefaultColorPalette(),
) : AgenticEventListener, LoggingPersonality {

    init {
        welcomeMessage?.let {
            logger.info(it)
        }
        url?.let {
            logger.info("${url.color(AnsiColor.BLUE)}\n")
        }
    }

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    protected open fun getAgentDeploymentEventMessage(e: AgentDeploymentEvent): String =
        "Deployed agent ${e.agent.name}\n\tdescription: ${e.agent.description}"

    protected open fun getRankingChoiceRequestEventMessage(e: RankingChoiceRequestEvent<*>): String =
        "Choosing ${e.type.simpleName} based on ${e.basis}"

    protected open fun getRankingChoiceMadeEventMessage(e: RankingChoiceMadeEvent<*>): String =
        "Chose ${e.type.simpleName} '${e.choice.match.name}' with confidence ${e.choice.score} based on ${e.basis}. Choices: ${e.rankings.infoString()}"

    protected open fun getRankingChoiceNotMadeEventMessage(e: RankingChoiceCouldNotBeMadeEvent<*>): String =
        "Failed to choose ${e.type.simpleName} based on ${e.basis}. Choices: ${e.rankings.infoString()}. Confidence cutoff: ${e.confidenceCutOff}"

    protected open fun getDynamicAgentCreationMessage(e: DynamicAgentCreationEvent): String =
        "Created agent ${e.agent.infoString()}"

    override fun onPlatformEvent(event: AgentPlatformEvent) {
        when (event) {
            is AgentDeploymentEvent -> {
                logger.info(getAgentDeploymentEventMessage(event))
            }

            is RankingChoiceRequestEvent<*> -> {
                logger.info(getRankingChoiceRequestEventMessage(event))
            }

            is RankingChoiceMadeEvent<*> -> {
                logger.info(getRankingChoiceMadeEventMessage(event))
            }

            is RankingChoiceCouldNotBeMadeEvent<*> -> {
                logger.info(getRankingChoiceNotMadeEventMessage(event))
            }

            is DynamicAgentCreationEvent -> {
                logger.info(getDynamicAgentCreationMessage(event))
            }

            else -> {
                // Do nothing
            }
        }
    }

    protected open fun getAgentProcessCreationEventMessage(e: AgentProcessCreationEvent): String =
        "[${e.processId}] created"

    protected open fun getAgentProcessReadyToPlanEventMessage(e: AgentProcessReadyToPlanEvent): String =
        "[${e.processId}] ready to plan from ${e.worldState.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)}"

    protected open fun getAgentProcessPlanFormulatedEventMessage(e: AgentProcessPlanFormulatedEvent): String =
        "[${e.processId}] formulated plan ${e.plan.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)} from ${e.worldState.infoString()}"

    protected open fun getEarlyTerminationMessage(e: EarlyTermination): String =
        "[${e.processId}] early termination by ${e.policy} for ${e.reason}"

    protected open fun getGoalAchievedEventMessage(e: GoalAchievedEvent): String =
        "[${e.processId}] goal ${e.goal.name} achieved in ${e.agentProcess.runningTime}"

    protected open fun getToolCallRequestEventMessage(e: ToolCallRequestEvent): String =
        "[${e.processId}] (${e.action?.shortName()}) calling tool ${e.tool}(${e.toolInput})"

    protected open fun getToolCallSuccessResponseEventMessage(e: ToolCallResponseEvent, resultToShow: String): String =
        "[${e.processId}] (${e.action?.shortName()}) tool ${e.tool} returned $resultToShow in ${e.runningTime.toMillis()}ms with payload ${e.toolInput}"

    protected open fun getToolCallFailureResponseEventMessage(e: ToolCallResponseEvent, throwable: Throwable?): String =
        "[${e.processId}] (${e.action?.shortName()}) failed tool ${e.tool} -> ${throwable} in ${e.runningTime.toMillis()}ms with payload ${e.toolInput}"

    protected open fun getProcessCompletionMessage(e: AgentProcessFinishedEvent): String =
        "[${e.processId}] completed in ${e.agentProcess.runningTime}"

    protected open fun getProcessFailureMessage(e: AgentProcessFinishedEvent): String =
        "[${e.processId}] failed"

    protected open fun getAgentProcessWaitingEventMessage(e: AgentProcessWaitingEvent): String =
        "[${e.processId}] waiting"

    protected open fun getAgentProcessStuckEventMessage(e: AgentProcessStuckEvent): String =
        "[${e.processId}] stuck at ${e.agentProcess.lastWorldState}"

    protected open fun getObjectAddedEventMessage(e: ObjectAddedEvent): String =
        "[${e.processId}] object added: ${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"

    protected open fun getObjectBoundEventMessage(e: ObjectBoundEvent): String =
        "[${e.processId}] object bound ${e.name}:${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"

    protected open fun getLlmRequestEventMessage(e: LlmRequestEvent<*>): String =
        "[${e.processId}] requesting LLM ${e.llm.name} to transform ${e.interaction.id.value} from ${e.outputClass.simpleName} -> ${e.interaction.llm}"

    protected open fun getChatModelCallEventMessage(e: ChatModelCallEvent<*>): String {
        val promptInfo = "using ${e.llm.name.color(colorPalette.highlight)}\n${
            e.springAiPrompt.toInfoString().color(AnsiColor.GREEN)
        }\nprompt id: '${e.interaction.id}'\ntools: [${
            e.interaction.toolCallbacks.joinToString { it.toolDefinition.name() }
                .color(AnsiColor.BRIGHT_MAGENTA)
        }]"
        return "${e.processId} Spring AI ChatModel call:\n${promptInfo}"
    }

    protected open fun getLlmResponseEventMessage(e: LlmResponseEvent<*>): String {
        var message =
            "[${e.processId}] received LLM response ${e.interaction.id.value} of type ${e.response?.let { it::class.java.simpleName } ?: "null"} from ${e.interaction.llm.criteria} in ${e.runningTime.seconds} seconds"

        if (e.agentProcess.processContext.processOptions.verbosity.showLlmResponses) {
            message += "\nResponse from prompt ${e.interaction.id}:\n${
                (objectMapper.writeValueAsString(e.response)).color(
                    color = AnsiColor.YELLOW
                )
            }"
        }

        return message
    }

    protected open fun getActionExecutionStartMessage(e: ActionExecutionStartEvent): String =
        "[${e.processId}] executing action ${e.action.name}"

    protected open fun getActionExecutionResultMessage(e: ActionExecutionResultEvent): String =
        "[${e.processId}] executed action ${e.action.name} in ${e.actionStatus.runningTime}"

    protected open fun getProgressUpdateEventMessage(e: ProgressUpdateEvent): String =
        "[${e.processId}] progress: ${e.createProgressBar(length = 50).color(LumonColorPalette.MEMBRANE)}"

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {

            is AgentProcessCreationEvent -> {
                logger.info(getAgentProcessCreationEventMessage(event))
            }

            is AgentProcessReadyToPlanEvent -> {
                logger.info(getAgentProcessReadyToPlanEventMessage(event))
            }

            is AgentProcessPlanFormulatedEvent -> {
                logger.info(getAgentProcessPlanFormulatedEventMessage(event))
            }

            is EarlyTermination -> {
                logger.info(getEarlyTerminationMessage(event))
            }

            is GoalAchievedEvent -> {
                logger.info(getGoalAchievedEventMessage(event))
            }

            is ToolCallRequestEvent -> {
                logger.info(getToolCallRequestEventMessage(event))
            }

            is ToolCallResponseEvent -> {
                when (event.result.isSuccess) {
                    true -> {
                        val raw = event.result.getOrThrow()
                        val resultToShow =
                            if (event.agentProcess.processContext.processOptions.verbosity.showPrompts) {
                                raw
                            } else {
                                trim(s = raw, max = 80, keepRight = 5)
                            }
                        logger.info(getToolCallSuccessResponseEventMessage(event, resultToShow ?: "null"))
                    }

                    false -> {
                        val throwable = event.result.exceptionOrNull()
                        logger.info(getToolCallFailureResponseEventMessage(event, throwable))
                        throwable?.let {
                            logger.debug(
                                "Error in function call {}",
                                event.processId,
                                it,
                            )
                        }
                    }
                }
            }

            is AgentProcessFinishedEvent -> {
                when (event.agentProcess.status) {
                    AgentProcessStatusCode.COMPLETED -> {
                        logger.info(getProcessCompletionMessage(event))
                    }

                    AgentProcessStatusCode.FAILED -> {
                        logger.info(getProcessFailureMessage(event))
                    }

                    else -> {
                        // Do nothing
                    }
                }
            }

            is AgentProcessWaitingEvent -> {
                logger.info(getAgentProcessWaitingEventMessage(event))
            }

            is AgentProcessStuckEvent -> {
                logger.info(getAgentProcessStuckEventMessage(event))
            }

            is ObjectAddedEvent -> {
                logger.info(getObjectAddedEventMessage(event))
            }

            is ObjectBoundEvent -> {
                logger.info(getObjectBoundEventMessage(event))
            }

            is LlmRequestEvent<*> -> {
                logger.info(getLlmRequestEventMessage(event))
            }

            // Only show this at all if verbose
            is ChatModelCallEvent<*> -> {
                if (event.agentProcess.processContext.processOptions.verbosity.showPrompts) {
                    logger.info(getChatModelCallEventMessage(event))
                }
            }

            is LlmResponseEvent<*> -> {
                logger.info(getLlmResponseEventMessage(event))
            }

            is ActionExecutionStartEvent -> {
                logger.info(getActionExecutionStartMessage(event))
            }

            is ActionExecutionResultEvent -> {
                logger.info(getActionExecutionResultMessage(event))
            }

            is ProgressUpdateEvent -> {
                logger.info(getProgressUpdateEventMessage(event))
            }

            is ProcessKilledEvent -> {
                logger.info("[${event.processId}] process killed")
            }

            else -> {
                // Do nothing
            }
        }
    }

    fun Prompt.toInfoString(): String {
        val bannerChar = "."
        return """|${lineSeparator("Messages ", bannerChar)}
           |${
            this.instructions.joinToString(
                "\n${
                    lineSeparator(
                        "",
                        bannerChar
                    )
                }\n"
            ) { "${it.messageType} <${it.text}>" }
        }
           |${lineSeparator("Options", bannerChar)}
           |${this.options}
           |""".trimMargin()
    }

}
