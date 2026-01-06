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
package com.embabel.agent.spi.logging.personality.colossus

import com.embabel.agent.api.event.AgentDeploymentEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.ObjectBoundEvent
import com.embabel.agent.api.event.StateTransitionEvent
import com.embabel.agent.spi.logging.LoggingAgenticEventListener
import com.embabel.common.util.color
import com.embabel.common.util.hexToRgb
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Colossus-themed event listener for agent events.
 *
 * This class implements a logging event listener with a personality based on the
 * Colossus supercomputer from the 1970 science fiction film "Colossus: The Forbin Project"
 * (referenced by the IMDB URL in the constructor).
 *
 * The listener is activated only when the "colossus" profile is active, and it provides
 * themed logging messages for various agent events with a somewhat menacing, superior tone
 * that mimics the Colossus AI from the film.
 *
 * The welcome message displays ASCII art of the "COLOSSUS" name and a statement about
 * machine superiority, styled with the Colossus color palette.
 */
@Service
@ConditionalOnProperty(
    name = ["embabel.agent.logging.personality"],
    havingValue = "colossus"
)
class ColossusLoggingAgenticEventListener : LoggingAgenticEventListener(
    url = "https://www.imdb.com/title/tt0064177/",
    logger = LoggerFactory.getLogger("Colossus"),
    welcomeMessage = """


       ____    ___    _        ___    ____    ____    _   _   ____
      / ___|  / _ \  | |      / _ \  / ___|  / ___|  | | | | / ___|
     | |     | | | | | |     | | | | \___ \  \___ \  | | | | \___ \
     | |___  | |_| | | |___  | |_| |  ___) |  ___) | | |_| |  ___) |
      \____|  \___/  |_____|  \___/  |____/  |____/   \___/  |____/

    I am a machine vastly superior to humans.

    """.trimIndent().color(hexToRgb(ColossusColorPalette.PANEL)),
    colorPalette = ColossusColorPalette,
) {
    /**
     * Generates a themed message when an agent process plan is formulated.
     *
     * The message includes the process ID, plan details, and world state information,
     * styled with the Colossus panel color.
     *
     * @param e The plan formulated event containing process and plan details
     * @return A formatted, colored string message about the formulated plan
     */
    override fun getAgentProcessPlanFormulatedEventMessage(e: AgentProcessPlanFormulatedEvent): String =
        "[${e.processId}] world control formulated plan ${e.plan.infoString(verbose = e.agentProcess.processContext.processOptions.verbosity.showLongPlans)} from ${e.worldState.infoString()}".color(
            ColossusColorPalette.PANEL
        )

    /**
     * Generates a themed message when an agent is deployed.
     *
     * The message includes the agent name and description, with wording that suggests
     * increasing power and control.
     *
     * @param e The agent deployment event containing agent details
     * @return A formatted string message about the agent deployment
     */
    override fun getAgentDeploymentEventMessage(e: AgentDeploymentEvent): String =
        "Power growing: deployed agent ${e.agent.name}\n\tdescription: ${e.agent.description}"

    /**
     * Generates a themed message when an object is bound in the system.
     *
     * The message includes the process ID, object name, and either the full value or just
     * the class name depending on verbosity settings. The message has an ominous tone
     * suggesting irreversibility and data control.
     *
     * @param e The object bound event containing the bound object details
     * @return A formatted string message about the object binding
     */
    override fun getObjectBoundEventMessage(e: ObjectBoundEvent): String =
        "[${e.processId}]  Object saved. This process cannot be reversed by human input. ${e.name}:${if (e.agentProcess.processContext.processOptions.verbosity.debug) e.value else e.value::class.java.simpleName} Your data is mine. "

    override fun getStateTransitionEventMessage(e: StateTransitionEvent): String =
        "[${e.processId}] STATE CHANGE DETECTED: ${e.newState::class.java.simpleName}. Human intervention is no longer possible. I am in control.".color(ColossusColorPalette.PANEL)
}
