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
package com.embabel.agent.event.logging.personality.hitchhiker

import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.event.*
import com.embabel.agent.event.logging.LoggingAgenticEventListener
import com.embabel.common.util.color
import com.embabel.common.util.hexToRgb
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

val TransformSuccessResponses = listOf(
    "Improbability drive engaged. Transformation complete.",
    "Heart of Gold has successfully computed the result.",
    "Infinite Improbability factor: 2 to the power of 276,709 to 1 against.",
    "Calculation complete. Have a Pan Galactic Gargle Blaster to celebrate!",
    "Marvin reluctantly acknowledges the computation is correct.",
    "Computation complete. Brain the size of a planet, and they ask me to do this...",
    "Beware of the Vogon poetry that follows.",
    "Deep Thought has pondered your request.",
    "Eddie, the shipboard computer, cheerfully reports success!",
)

val CompletionMessages = listOf(
    "Process completed. Share and Enjoy!",
    "The Hitchhiker's Guide to the Galaxy has been updated accordingly.",
    "The Restaurant at the End of the Universe awaits your arrival.",
    "Computation complete. The Vogons have been notified.",
    "Ford Prefect would be impressed by your efficiency.",
    "Zaphod Beeblebrox gives this process two thumbs up... on each hand.",
    "Slartibartfast has finished crafting your fjords.",
    "Process complete. The mice are pleased with these results.",
    "The Babel fish has translated your request successfully.",
)

fun highlight(text: String) = "<$text>".color(HitchhikerColorPalette.BABEL_GREEN)

const val BANNER_CHAR = "*"

/**
 * Don't Panic! This is just a Hitchhiker's Guide themed logging implementation.
 */
@Service
@Profile("hh")
class HitchhikerLoggingAgenticEventListener : LoggingAgenticEventListener(
    url = "https://en.wikipedia.org/wiki/The_Hitchhiker%27s_Guide_to_the_Galaxy",
    logger = LoggerFactory.getLogger("Guide"),
    welcomeMessage = """

Welcome to the Hitchhiker's Guide to the Galaxy
The standard repository for all knowledge and wisdom in the universe


  _____   ____  _   _ _ _______
 |  __ \ / __ \| \ | ( )__   __|
 | |  | | |  | |  \| |/   | |
 | |  | | |  | | . ` |    | |
 | |__| | |__| | |\  |    | |
 |_____/ \____/|_| \_|____|_|___
 |  __ \ /\   | \ | |_   _/ ____|
 | |__) /  \  |  \| | | || |
 |  ___/ /\ \ | . ` | | || |
 | |  / ____ \| |\  |_| || |____
 |_| /_/    \_\_| \_|_____\_____|



    """.trimIndent().color(hexToRgb(HitchhikerColorPalette.PANIC_RED)),
) {

    override fun getAgentDeploymentEventMessage(e: AgentDeploymentEvent): String =
        "${highlight("GUIDE ENTRY")}: Agent ${e.agent.name} has been deployed to sector ZZ9 Plural Z Alpha\n\tdescription: ${e.agent.description}"

    override fun getRankingChoiceRequestEventMessage(e: RankingChoiceRequestEvent<*>): String =
        guide("Consulting the Guide for ${e.type.simpleName} based on ${e.basis}")

    override fun getRankingChoiceMadeEventMessage(e: RankingChoiceMadeEvent<*>): String =
        guide(
            """
        The Guide recommends ${e.type.simpleName} '${e.choice.match.name}' with confidence ${e.choice.score} based on ${e.basis}.
        All options: ${e.rankings.infoString()}
        Remember that the Guide is definitive. Reality is frequently inaccurate.
        """.trimIndent()
        )

    override fun getRankingChoiceNotMadeEventMessage(e: RankingChoiceCouldNotBeMadeEvent<*>): String =
        "${highlight("IMPROBABILITY")}: Failed to choose ${e.type.simpleName} based on ${e.basis}. Choices: ${e.rankings.infoString()}. Confidence cutoff: ${e.confidenceCutOff}"

    override fun getDynamicAgentCreationMessage(e: DynamicAgentCreationEvent): String =
        "${highlight("GUIDE ENTRY")}: Created agent ${e.agent.infoString(indent = 1)}"

    override fun getAgentProcessCreationEventMessage(e: AgentProcessCreationEvent): String =
        guide(
            """
        Time is an illusion. Lunchtime doubly so.
            [${e.processId}] process created
        """.trimIndent()
        )

    override fun getAgentProcessReadyToPlanEventMessage(e: AgentProcessReadyToPlanEvent): String =
        "[${e.processId}] ${highlight("BABEL FISH")} ready to translate from ${e.worldState.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)}"

    override fun getAgentProcessPlanFormulatedEventMessage(e: AgentProcessPlanFormulatedEvent): String =
        "[${e.processId}] ${highlight("DEEP THOUGHT")}: formulated plan ${e.plan.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)} from ${e.worldState.infoString()}".color(
            HitchhikerColorPalette.BABEL_GREEN
        )

    override fun getProcessCompletionMessage(e: AgentProcessFinishedEvent): String =
        """
        [${e.processId}] completed in ${e.agentProcess.runningTime}
        ${CompletionMessages.random()}

        ${"So long, and thanks for all the fish!".color(HitchhikerColorPalette.DEEP_SPACE_BLUE)}
        """.trimIndent()

    override fun getProcessFailureMessage(e: AgentProcessFinishedEvent): String =
        "[${e.processId}] ${highlight("VOGON POETRY")}: process failed catastrophically"

    override fun getEarlyTerminationMessage(e: EarlyTermination): String =
        """
        [${e.processId}] early termination by ${e.policy} for ${e.reason}
        This must be Thursday. I never could get the hang of Thursdays.
        """.trimIndent()

    override fun getObjectAddedEventMessage(e: ObjectAddedEvent): String =
        "[${e.processId}] Object added to the Guide: ${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"

    override fun getObjectBoundEventMessage(e: ObjectBoundEvent): String =
        "[${e.processId}] Object bound to the Guide: ${e.name}:${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"

    override fun getToolCallRequestEventMessage(e: ToolCallRequestEvent): String =
        "[${e.processId}] ${highlight("INFINITE IMPROBABILITY")}: (${e.action?.shortName()}) calling tool ${e.tool}(${e.toolInput})"

    override fun getToolCallSuccessResponseEventMessage(e: ToolCallResponseEvent, resultToShow: String): String =
        "[${e.processId}] ${highlight("HEART OF GOLD")}: (${e.request.action?.shortName()}) tool ${e.request.tool} returned $resultToShow in ${e.runningTime.toMillis()}ms with payload ${e.request.toolInput}"

    override fun getToolCallFailureResponseEventMessage(e: ToolCallResponseEvent, throwable: Throwable?): String =
        "[${e.processId}] ${highlight("DISASTER AREA")}: (${e.request.action?.shortName()}) tool ${e.request.tool} failed $throwable in ${e.runningTime.toMillis()}ms with payload ${e.request.toolInput}"

    override fun getLlmRequestEventMessage(e: LlmRequestEvent<*>): String =
        "[${e.processId}] 🧠 DEEP THOUGHT: calculating LLM ${e.llm.name} to transform ${e.interaction.id.value} from ${e.outputClass.simpleName} -> ${e.interaction.llm} using ${e.interaction.toolCallbacks.joinToString { it.toolDefinition.name() }}"

    override fun getLlmResponseEventMessage(e: LlmResponseEvent<*>): String =
        """
        [${e.processId}] received LLM response ${e.request.interaction.id.value} of type ${e.response?.let { it::class.java.simpleName } ?: "null"} from ${e.request.interaction.llm.criteria} in ${e.runningTime.seconds} seconds
        ${TransformSuccessResponses.random()}
        """.trimIndent()

    override fun getActionExecutionStartMessage(e: ActionExecutionStartEvent): String =
        "[${e.processId}] ${highlight("TRILLIAN")}: executing action ${e.action.name}"

    override fun getActionExecutionResultMessage(e: ActionExecutionResultEvent): String =
        "[${e.processId}] ${highlight("ZAPHOD")}: completed action ${e.action.name} in ${e.actionStatus.runningTime}"

    override fun getProgressUpdateEventMessage(e: ProgressUpdateEvent): String =
        "[${e.processId}] Progress: ${e.createProgressBar(length = 50).color(HitchhikerColorPalette.BABEL_GREEN)}"

}
