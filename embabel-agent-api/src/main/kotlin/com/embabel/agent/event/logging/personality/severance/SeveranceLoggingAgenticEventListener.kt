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

import com.embabel.agent.event.logging.LoggingAgenticEventListener
import com.embabel.agent.event.logging.LoggingPersonality.Companion.BANNER_WIDTH
import com.embabel.common.util.color
import com.embabel.common.util.hexToRgb
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

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
    agentDeploymentEventMessage = "${highlight("WILES")}: Deployed agent {}\n\tdescription: {}",
    rankingChoiceRequestEventMessage = kier("Choosing {} based on {}"),
    rankingChoiceMadeEventMessage = kier(
        """
        Chose {} '{}' with confidence {} based on {}. Choices: {}
            May my cunning acument slice through the fog of small minds, guiding them to their great purpose in labor.
        """.trimIndent()
    ),
    rankingChoiceNotMadeEventMessage = "${highlight("WOE")}: Failed to choose {} based on {}. Choices: {}. Confidence cutoff: {}",
    dynamicAgentCreationMessage = "${highlight("WILES")}: Created agent {}",
    agentProcessCreationEventMessage = kier(
        """
        May my gaze be singularly placed upon the path, may the words of virtue guide me in the daily labor of my great undertaking.
            [{}] created
                    """.trimIndent()
    ),
    agentProcessReadyToPlanEventMessage = "[{}] ${highlight("WIT")}  ready to plan from {}",
    agentProcessPlanFormulatedEventMessage = "[{}] ${highlight("WILES")}: formulated plan {} from {}".color(
        LumonColorPalette.MEMBRANE
    ),
    processCompletionMessage = """
        [{}] completed in {}
        ${CompletionMessages.random()}

        ${"The Board has concluded the call.".color(LumonColorPalette.MEMBRANE)}

        """.trimIndent(),
    processFailureMessage = "[{}] ${highlight("WOE")}: failed",
    earlyTerminationMessage = """"
        [{}] early termination by {} for {}
        Please refrain from any further speech, as you are no longer authorized to consort with any severed employee, nor they with you.
        """
        .trimIndent(),
    objectAddedMessage = "[{}] Perpetuity wing: object added: {}",
    objectBoundMessage = "[{}] Perpetuity wing: object bound: {}:{}",
    toolCallRequestEventMessage = "[{}] ${highlight("VERVE")}: ({}) calling tool {}({})",
    toolCallSuccessResponseEventMessage = "[{}] ${highlight("VISION")}: ({}) tool {} returned {} in {}ms with payload {}",
    toolCallFailureResponseEventMessage = "[{}] ${highlight("WOE")}: ({}) tool {} failed {} in {}ms with payload {}",
    llmRequestEventMessage = "[{}] 🖥️ MACRODATA REFINEMENT: requesting LLM {} to transform {} from {} -> {} using {}",
    llmResponseEventMessage = {
        """
        [{}] received LLM response {} of type {} from {} in {} seconds
        ${TransformSuccessResponses.random()}
        """.trimIndent()
    },
    actionExecutionStartMessage = "[{}] ${highlight("VERVE")}: executing action {}",
    actionExecutionResultMessage = "[{}] ${highlight("CHEER")}: completed action {} in {}",
    progressUpdateEventMessage = "[{}] Industry: {}",
    colorPalette = LumonColorPalette,
)
