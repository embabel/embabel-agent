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
package com.embabel.agent.event.logging.personality.severance

import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.event.*
import com.embabel.agent.event.logging.LoggingAgenticEventListener
import com.embabel.agent.event.logging.LoggingPersonality.Companion.BANNER_WIDTH
import com.embabel.common.util.color
import com.embabel.common.util.hexToRgb
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

val LumonDepartments = listOf(
    "MDR",
    "Lumon",
    "Choreography and Merriment",
    "Mammalians Nurturable",
    "Optics And Design",
    "Perpetuity Wing",
    "Macrodata Refinement",
    "The Board",
    "Wellness",
    "Testing Floor",
)

val MdrFiles = listOf(
    "Allentown", "Dranesville", "Wellington",
    "Tumwater", "Lucknow", "Sopchoppy", "Astoria",
    "Loveland", "St Pierre", "Zurich", "Cairns",
)

val TransformSuccessResponses = MdrFiles.map {
    "$it 100% Complete"
} +
        kier("I knew you could do it. Even in your darkest moments I could see you arriving here")

val CompletionMessages = listOf(
    """
        🧔🏼‍♂️ PRAISE KIER: Cold Harbor 100% complete.
        In refining your macrodata file, you have brought glory to this company, and to me, Kier Eagan.
    """.trimIndent(),
    "May I introduce choreography and merriment",
    "The Founder wished to witness the historic completion of your 25th file.",
    "Goodly splendors await upon your victory. Love, Mr. Milchick",
    "It's truly special to host a man so illustrious, so sapient, so magnanimous...",
    "See you at the Equator",
    "The barrier is holding. She feels nothing. It's beautiful.",
    "Mammalians Nurturable brings an offering.",
    """
        In completing your 25th Macrodata file, you have drawn my grand agendum nearer to fulfillment,
        thus making you one of the most important people in history.
    """.trimIndent()
)

fun highlight(text: String) = "<$text>".color(LumonColorPalette.MEMBRANE)

const val BANNER_CHAR = "."

/**
 * Thanks to Kier
 */
@Service
@Profile("severance")
class SeveranceLoggingAgenticEventListener : LoggingAgenticEventListener(
    url = "https://www.imdb.com/title/tt11280740/",
    logger = LoggerFactory.getLogger("MDR"),
    welcomeMessage = """


        ${BANNER_CHAR.repeat(BANNER_WIDTH)}
        Kier, chosen one, Kier.
        Kier, brilliant one, Kier.
        Brings the bounty to the plain through the torment, through the rains,
        Progress, knowledge show no fear,
        Kier, chosen one, Kier.
        ${BANNER_CHAR.repeat(BANNER_WIDTH)}

        ▗▖   ▗▖ ▗▖▗▖  ▗▖ ▗▄▖ ▗▖  ▗▖
        ▐▌   ▐▌ ▐▌▐▛▚▞▜▌▐▌ ▐▌▐▛▚▖▐▌
        ▐▌   ▐▌ ▐▌▐▌  ▐▌▐▌ ▐▌▐▌ ▝▜▌
        ▐▙▄▄▖▝▚▄▞▘▐▌  ▐▌▝▚▄▞▘▐▌  ▐▌

    """.trimIndent().color(hexToRgb(LumonColorPalette.MEMBRANE)),
) {

    override fun getAgentDeploymentEventMessage(e: AgentDeploymentEvent): String =
        "${highlight("WILES")}: Deployed agent ${e.agent.name}\n\tdescription: ${e.agent.description}"

    override fun getRankingChoiceRequestEventMessage(e: RankingChoiceRequestEvent<*>): String =
        kier("Choosing ${e.type.simpleName} based on ${e.basis}")

    override fun getRankingChoiceMadeEventMessage(e: RankingChoiceMadeEvent<*>): String =
        kier(
            """
        Chose ${e.type.simpleName} '${e.choice.match.name}' with confidence ${e.choice.score} based on ${e.basis}. Choices: ${e.rankings.infoString()}
            May my cunning acument slice through the fog of small minds, guiding them to their great purpose in labor.
        """.trimIndent()
        )

    override fun getRankingChoiceNotMadeEventMessage(e: RankingChoiceCouldNotBeMadeEvent<*>): String =
        "${highlight("WOE")}: Failed to choose ${e.type.simpleName} based on ${e.basis}. Choices: ${e.rankings.infoString()}. Confidence cutoff: ${e.confidenceCutOff}"

    override fun getDynamicAgentCreationMessage(e: DynamicAgentCreationEvent): String =
        "${highlight("WILES")}: Created agent ${e.agent.infoString(indent = 1)}"

    override fun getAgentProcessCreationEventMessage(e: AgentProcessCreationEvent): String =
        kier(
            """
        May my gaze be singularly placed upon the path, may the words of virtue guide me in the daily labor of my great undertaking.
            [${e.processId}] created
        """.trimIndent()
        )

    override fun getAgentProcessReadyToPlanEventMessage(e: AgentProcessReadyToPlanEvent): String =
        "[${e.processId}] ${highlight("WIT")}  ready to plan from ${e.worldState.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)}"

    override fun getAgentProcessPlanFormulatedEventMessage(e: AgentProcessPlanFormulatedEvent): String =
        "[${e.processId}] ${highlight("WILES")}: formulated plan ${e.plan.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)} from ${e.worldState.infoString()}".color(
            LumonColorPalette.MEMBRANE
        )

    override fun getProcessCompletionMessage(e: AgentProcessFinishedEvent): String =
        """
        [${e.processId}] completed in ${e.agentProcess.runningTime}
        ${CompletionMessages.random()}

        ${"The Board has concluded the call.".color(LumonColorPalette.MEMBRANE)}
        """.trimIndent()

    override fun getProcessFailureMessage(e: AgentProcessFinishedEvent): String =
        "[${e.processId}] ${highlight("WOE")}: failed"

    override fun getEarlyTerminationMessage(e: EarlyTermination): String =
        """
        [${e.processId}] early termination by ${e.policy} for ${e.reason}
        Please refrain from any further speech, as you are no longer authorized to consort with any severed employee, nor they with you.
        """
            .trimIndent()

    override fun getObjectAddedEventMessage(e: ObjectAddedEvent): String =
        "[${e.processId}] Perpetuity wing: object added: ${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"

    override fun getObjectBoundEventMessage(e: ObjectBoundEvent): String =
        "[${e.processId}] Perpetuity wing: object bound: ${e.name}:${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"

    override fun getToolCallRequestEventMessage(e: ToolCallRequestEvent): String =
        "[${e.processId}] ${highlight("VERVE")}: (${e.action?.shortName()}) calling tool ${e.tool}(${e.toolInput})"

    override fun getToolCallSuccessResponseEventMessage(e: ToolCallResponseEvent, resultToShow: String): String =
        "[${e.processId}] ${highlight("VISION")}: (${e.request.action?.shortName()}) tool ${e.request.tool} returned $resultToShow in ${e.runningTime.toMillis()}ms with payload ${e.request.toolInput}"

    override fun getToolCallFailureResponseEventMessage(e: ToolCallResponseEvent, throwable: Throwable?): String =
        "[${e.processId}] ${highlight("WOE")}: (${e.request.action?.shortName()}) tool ${e.request.tool} failed $throwable in ${e.runningTime.toMillis()}ms with payload ${e.request.toolInput}"

    override fun getLlmRequestEventMessage(e: LlmRequestEvent<*>): String =
        "[${e.processId}] \uD83D\uDDA5\uFE0F MACRODATA REFINEMENT: requesting LLM ${e.llm.name} to transform ${e.interaction.id.value} from ${e.outputClass.simpleName} -> ${e.interaction.llm} using ${e.interaction.toolCallbacks.joinToString { it.toolDefinition.name() }}"

    override fun getLlmResponseEventMessage(e: LlmResponseEvent<*>): String =
        """
        [${e.processId}] received LLM response ${e.request.interaction.id.value} of type ${e.response?.let { it::class.java.simpleName } ?: "null"} from ${e.request.interaction.llm.criteria} in ${e.runningTime.seconds} seconds
        ${TransformSuccessResponses.random()}
        """.trimIndent()

    override fun getActionExecutionStartMessage(e: ActionExecutionStartEvent): String =
        "[${e.processId}] ${highlight("VERVE")}: executing action ${e.action.name}"

    override fun getActionExecutionResultMessage(e: ActionExecutionResultEvent): String =
        "[${e.processId}] ${highlight("CHEER")}: completed action ${e.action.name} in ${e.actionStatus.runningTime}"

    override fun getProgressUpdateEventMessage(e: ProgressUpdateEvent): String =
        "[${e.processId}] Industry: ${e.createProgressBar(length = 50).color(LumonColorPalette.MEMBRANE)}"

}
