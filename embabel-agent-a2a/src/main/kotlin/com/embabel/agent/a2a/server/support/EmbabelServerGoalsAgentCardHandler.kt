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
package com.embabel.agent.a2a.server.support

import com.embabel.agent.a2a.server.A2ARequestHandler
import com.embabel.agent.a2a.server.AgentCardHandler
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Goal
import com.embabel.common.core.types.Semver
import com.embabel.common.util.indent
import com.embabel.agent.a2a.server.A2AProtocolVersion
import org.a2aproject.sdk.spec.AgentCapabilities
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.AgentProvider
import org.a2aproject.sdk.spec.Compat03Fields
import org.a2aproject.sdk.spec.TransportProtocol
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
        // One JSON-RPC interface per A2A version, at the same URL: the A2A-Version header picks the version
        val interfaces = A2AProtocolVersion.entries.map {
            AgentInterface(TransportProtocol.JSONRPC.asString(), hostingUrl, null, it.version)
        }
        val builder = AgentCard.builder()
            .name(agentPlatform.name)
            .description(agentPlatform.description)
            .supportedInterfaces(interfaces)
            .provider(AgentProvider("Embabel", "https://embabel.com"))
            .version(Semver.DEFAULT_VERSION)
            .documentationUrl("https://embabel.com/docs")
            .capabilities(
                AgentCapabilities.builder()
                    .streaming(true)
                    .pushNotifications(false)
                    .extendedAgentCard(false)
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
        // The fields A2A 0.3 clients read the endpoint from, such as url
        Compat03Fields.addCompat03FieldsIfAvailable(
            builder,
            interfaces.take(1),
            hostingUrl,
            TransportProtocol.JSONRPC.asString(),
        )
        val agentCard = builder.build()
        logger.info("Returning agent card: {}", agentCard)
        return agentCard
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "${javaClass.simpleName}(path='$path', agentPlatform=${agentPlatform.name})".indent(indent)
}
