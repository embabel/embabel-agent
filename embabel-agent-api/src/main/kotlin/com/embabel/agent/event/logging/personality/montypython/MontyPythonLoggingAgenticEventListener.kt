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
package com.embabel.agent.event.logging.personality.montypython

import com.embabel.agent.event.*
import com.embabel.agent.event.logging.LoggingAgenticEventListener
import com.embabel.common.util.color
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * And now for something completely different
 */
@Component
@Profile("montypython")
class MontyPythonLoggingAgenticEventListener : LoggingAgenticEventListener(
    logger = LoggerFactory.getLogger("FlyingCircus"),
    welcomeMessage = """

         /\                                                 /\
        /  \      NOBODY EXPECTS THE SPANISH INQUISITION!  /  \
       /    \                                             /    \
      /      \                                           /      \
     /        \                                         /        \
    /          \                                       /          \
    |    ||    |                                       |    ||    |
    |    ||    |                                       |    ||    |
    |    ||    |                                       |    ||    |
    |    ||    |                                       |    ||    |
    |    ||    |                                       |    ||    |
    |    \\___ |                                       | ___//    |
    |          |                                       |          |
    |  MONTY   |                                       |  PYTHON  |
    |  FLYING  |                                       |  CIRCUS  |
    |__________|                                       |__________|

    """.trimIndent().color(MontyPythonColorPalette.BRIGHT_RED),
    colorPalette = MontyPythonColorPalette,
) {
    override fun getAgentDeploymentEventMessage(e: AgentDeploymentEvent): String =
        "Found a parrot: ${e.agent.name}\n\tdescription: ${e.agent.description}"

    override fun getRankingChoiceMadeEventMessage(e: RankingChoiceMadeEvent<*>): String =
        "You don't vote for kings. We have chosen ${e.type.simpleName} with ${e.choice.score} certainty based on ${e.basis}"

    override fun getDynamicAgentCreationMessage(e: DynamicAgentCreationEvent): String =
        "It's not dead yet: Created agent ${e.agent.infoString()}"

    override fun getAgentProcessCreationEventMessage(e: AgentProcessCreationEvent): String =
        "And now for something completely different: ${e.processId}"

    override fun getAgentProcessReadyToPlanEventMessage(e: AgentProcessReadyToPlanEvent): String =
        "[${e.processId}] My brain hurts! Ready to plan from: ${e.worldState.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)}"

    override fun getAgentProcessPlanFormulatedEventMessage(e: AgentProcessPlanFormulatedEvent): String =
        "[${e.processId}] We've found a witch! Formulated plan: ${e.plan.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)}> from ${e.worldState.infoString()}"

    override fun getProcessCompletionMessage(e: AgentProcessFinishedEvent): String =
        "[${e.processId}] Tis but a scratch: process completed in ${e.agentProcess.runningTime}"

    override fun getProcessFailureMessage(e: AgentProcessFinishedEvent): String =
        "[${e.processId}] It's just a flesh wound: Process failed"

    override fun getObjectAddedEventMessage(e: ObjectAddedEvent): String =
        "Bring out your dead! Object added: ${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName} to process ${e.processId}"

    override fun getLlmRequestEventMessage(e: LlmRequestEvent<*>): String =
        "[${e.processId}] Strange women lying in ponds: Requesting LLM ${e.llm.name} transform from ${e.outputClass.simpleName} -> ${e.interaction.llm}"

    override fun getActionExecutionStartMessage(e: ActionExecutionStartEvent): String =
        "[${e.processId}] Run away! Run away! executing action ${e.action.name}"

    override fun getActionExecutionResultMessage(e: ActionExecutionResultEvent): String =
        "[${e.processId}] I fart in your general direction! Executed action ${e.action.name} in ${e.actionStatus.runningTime}"

    override fun getObjectBoundEventMessage(e: ObjectBoundEvent): String =
        "[${e.processId}] Object saved! Nudge nudge, wink wink, say no more! ${e.name}:${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName}"
}
