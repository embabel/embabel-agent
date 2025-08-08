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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.a2a.server.AgentCardHandler
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Goal
import com.embabel.common.core.types.Semver
import com.embabel.common.util.indent
import io.a2a.spec.AgentCapabilities
import io.a2a.spec.AgentCard
import io.a2a.spec.AgentProvider
import org.slf4j.LoggerFactory

typealias GoalFilter = (Goal) -> Boolean

const val DEFAULT_A2A_PATH = "a2a"

/**
 * Expose one agent card for the whole server.
 * @param path Relative path of the endpoint (under the root)
 */
class EmbabelServerGoalsAgentCardHandler(
    override val path: String = DEFAULT_A2A_PATH,
    private val agentPlatform: AgentPlatform,
    private val a2ARequestHandler: A2ARequestHandler,
    private val goalFilter: GoalFilter,
) : AgentCardHandler, A2ARequestHandler by a2ARequestHandler {

    private val logger = LoggerFactory.getLogger(EmbabelServerGoalsAgentCardHandler::class.java)

    override fun agentCard(
        scheme: String,
        host: String,
        port: Int,
    ): AgentCard {
        val hostingUrl = "$scheme://$host:$port/$path"
        val agentCard = AgentCard.Builder()
            .name(agentPlatform.name)
            .description(agentPlatform.description)
            .url(hostingUrl)
            .provider(AgentProvider("Embabel", "https://embabel.com"))
            .version(Semver.Companion.DEFAULT_VERSION)
            .documentationUrl("https://embabel.com/docs")
            .capabilities(
                AgentCapabilities.Builder()
                    .streaming(false) // TODO are they planning to support streaming?
                    .pushNotifications(false)
                    .stateTransitionHistory(false)
                    .extensions(emptyList())
                    .build()
            )
            .defaultInputModes(listOf("application/json", "text/plain"))
            .defaultOutputModes(listOf("application/json", "text/plain"))
            .skills(
                FromGoalsAgentSkillFactory(
                    goals = agentPlatform.goals.filter { goalFilter.invoke(it) }.toSet(),
                ).skills(agentPlatform.name)
            )
            .supportsAuthenticatedExtendedCard(false)
            .protocolVersion("0.2.5")
            .build()
        logger.info("Returning agent card: {}", agentCard)
        return agentCard
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "${javaClass.simpleName}(path='$path', agentPlatform=${agentPlatform.name})".indent(indent)
}
