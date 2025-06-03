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
import com.embabel.common.util.AnsiColor
import com.embabel.common.util.color
import com.embabel.common.util.trim
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
    private val agentDeploymentEventMessage: String = "Deployed agent {}\n\tdescription: {}",
    private val rankingChoiceRequestEventMessage: String = "Choosing {} based on {}",
    private val rankingChoiceMadeEventMessage: String = "Chose {} '{}' with confidence {} based on {}. Choices: {}",
    private val rankingChoiceNotMadeEventMessage: String = "Failed to choose {} based on {}. Choices: {}. Confidence cutoff: {}",
    private val dynamicAgentCreationMessage: String = "Created agent {}",
    private val agentProcessCreationEventMessage: String = "[{}] created",
    private val agentProcessReadyToPlanEventMessage: String = "[{}] ready to plan from {}",
    private val agentProcessPlanFormulatedEventMessage: String = "[{}] formulated plan {} from {}",
    private val processCompletionMessage: String = "[{}] completed in {}",
    private val processFailureMessage: String = "[{}] failed",
    private val earlyTerminationMessage: String = "[{}] early termination by {} for {}",
    private val objectAddedMessage: String = "[{}] object added: {}",
    private val objectBoundMessage: String = "[{}] object bound {}:{}",
    private val toolCallRequestEventMessage: String = "[{}] ({}) calling tool {}({})",
    private val toolCallSuccessResponseEventMessage: String = "[{}] ({}) tool {} returned {} in {}ms with payload {}",
    private val toolCallFailureResponseEventMessage: String = "[{}] ({}) failed tool {} -> {} in {}ms with payload {}",
    private val llmRequestEventMessage: String = "[{}] requesting LLM {} to transform {} from {} -> {} using {}",
    private val llmResponseEventMessage: () -> String = { "[{}] received LLM response {} of type {} from {} in {} seconds" },
    private val actionExecutionStartMessage: String = "[{}] executing action {}",
    private val actionExecutionResultMessage: String = "[{}] executed action {} in {}",
    private val progressUpdateEventMessage: String = "[{}] progress: {}",
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

    override fun onPlatformEvent(event: AgentPlatformEvent) {
        when (event) {
            is AgentDeploymentEvent -> {
                logger.info(agentDeploymentEventMessage, event.agent.name, event.agent.description)
            }

            is RankingChoiceRequestEvent<*> -> {
                logger.info(
                    rankingChoiceRequestEventMessage,
                    event.type.simpleName,
                    event.basis,
                )
            }

            is RankingChoiceMadeEvent<*> -> {
                logger.info(
                    rankingChoiceMadeEventMessage,
                    event.type.simpleName,
                    event.choice.match.name,
                    event.choice.score,
                    event.basis,
                    event.rankings.infoString(),
                )
            }

            is RankingChoiceCouldNotBeMadeEvent<*> -> {
                logger.info(
                    rankingChoiceNotMadeEventMessage,
                    event.type.simpleName,
                    event.basis,
                    event.rankings.infoString(),
                    event.confidenceCutOff,
                )
            }

            is DynamicAgentCreationEvent -> {
                logger.info(dynamicAgentCreationMessage, event.agent.infoString())
            }

            else -> {
                // Do nothing
            }
        }
    }

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {

            is AgentProcessCreationEvent -> {
                logger.info(
                    agentProcessCreationEventMessage,
                    event.processId,
                )
            }

            is AgentProcessReadyToPlanEvent -> {
                logger.info(
                    agentProcessReadyToPlanEventMessage,
                    event.processId,
                    event.worldState.infoString(verbose = event.agentProcess.processContext.processOptions.verbosity.showLongPlans),
                )
            }

            is AgentProcessPlanFormulatedEvent -> {
                logger.info(
                    agentProcessPlanFormulatedEventMessage,
                    event.processId,
                    event.plan.infoString(verbose = event.agentProcess.processContext.processOptions.verbosity.showLongPlans),
                    event.worldState.infoString(),
                )
            }

            is EarlyTermination -> {
                logger.info(
                    earlyTerminationMessage,
                    event.processId,
                    event.policy,
                    event.reason,
                )
            }

            is GoalAchievedEvent -> {
                logger.info(
                    "[{}] goal {} achieved in {}",
                    event.processId,
                    event.goal.name,
                    event.agentProcess.runningTime,
                )
            }

            is ToolCallRequestEvent -> {
                logger.info(
                    toolCallRequestEventMessage,
                    event.processId,
                    event.action?.shortName(),
                    event.function,
                    event.toolInput,
                )
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
                        logger.info(
                            toolCallSuccessResponseEventMessage,
                            event.processId,
                            event.action?.shortName(),
                            event.function,
                            resultToShow,
                            event.runningTime.toMillis(),
                            event.toolInput,
                        )
                    }

                    false -> {
                        val throwable = event.result.exceptionOrNull()
                        logger.info(
                            toolCallFailureResponseEventMessage,
                            event.processId,
                            event.action?.shortName(),
                            event.function,
                            throwable,
                            event.runningTime.toMillis(),
                            event.toolInput,
                        )
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
                        logger.info(
                            processCompletionMessage,
                            event.processId,
                            event.agentProcess.runningTime,
                        )
                    }

                    AgentProcessStatusCode.FAILED -> {
                        logger.info(processFailureMessage, event.agentProcess.id)
                    }

                    else -> {
                        // Do nothing
                    }
                }
            }

            is AgentProcessWaitingEvent -> {
                logger.info(
                    "[{}] waiting",
                    event.processId,
                )
            }

            is AgentProcessStuckEvent -> {
                logger.info(
                    "[{}] stuck at {}",
                    event.processId,
                    event.agentProcess.lastWorldState
                )
            }

            is ObjectAddedEvent -> {
                logger.info(
                    objectAddedMessage,
                    event.processId,
                    if (event.agentProcess.processContext.processOptions.verbosity.debug)
                        event.value else event.value::class.java.simpleName,
                )
            }

            is ObjectBoundEvent -> {
                logger.info(
                    objectBoundMessage,
                    event.processId,
                    event.name,
                    if (event.agentProcess.processContext.processOptions.verbosity.debug)
                        event.value else event.value::class.java.simpleName,
                )
            }

            is LlmRequestEvent<*> -> {
                var message = llmRequestEventMessage
                logger.info(
                    message,
                    event.processId,
                    event.interaction.llm.criteria,
                    event.interaction.id.value,
                    event.outputClass.simpleName,
                    event.interaction.llm,
                )
            }

            // Only show this at all if verbose
            is ChatModelCallEvent<*> -> {
                if (event.agentProcess.processContext.processOptions.verbosity.showPrompts) {
                    val promptInfo = "using ${event.interaction.llm.criteria.toString().color(AnsiColor.GREEN)}\n${
                        event.springAiPrompt.toInfoString().color(AnsiColor.GREEN)
                    }\nprompt id: '${event.interaction.id}'\ntools: [${
                        event.interaction.toolCallbacks.joinToString { it.toolDefinition.name() }
                            .color(AnsiColor.BRIGHT_MAGENTA)
                    }]"
                    logger.info(
                        "{} Spring AI ChatModel call:\n{}",
                        event.processId,
                        promptInfo,
                    )
                }
            }

            is LlmResponseEvent<*> -> {
                var message = llmResponseEventMessage()
                if (event.agentProcess.processContext.processOptions.verbosity.showLlmResponses) {
                    message += "\nResponse from prompt ${event.interaction.id}:\n${
                        (jacksonObjectMapper().writeValueAsString(event.response)).color(
                            color = AnsiColor.YELLOW
                        )
                    }"
                }
                logger.info(
                    message,
                    event.processId,
                    event.interaction.id.value,
                    event.response?.let { it::class.java.simpleName } ?: "null",
                    event.interaction.llm.criteria,
                    event.runningTime.seconds,
                )
            }

            is ActionExecutionStartEvent -> {
                logger.info(actionExecutionStartMessage, event.processId, event.action.name)
            }

            is ActionExecutionResultEvent -> {
                logger.info(
                    actionExecutionResultMessage,
                    event.processId,
                    event.action.name,
                    event.actionStatus.runningTime,
                )
            }

            is ProgressUpdateEvent -> {
                logger.info(
                    progressUpdateEventMessage,
                    event.processId,
                    event.createProgressBar(length = 50).color(LumonColorPalette.MEMBRANE),
                )
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
