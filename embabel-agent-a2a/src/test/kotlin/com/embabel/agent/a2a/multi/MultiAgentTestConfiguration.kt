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
package com.embabel.agent.a2a.multi

import com.embabel.agent.a2a.client.A2AHttpClientFactory
import com.embabel.agent.a2a.client.EmbabelA2AClient
import com.embabel.agent.a2a.client.api.A2AClient
import com.embabel.agent.a2a.client.spi.SpringRestClientA2AHttpClient
import com.embabel.agent.a2a.server.AgentCardHandler
import com.embabel.agent.a2a.server.support.AutonomyA2ARequestHandler
import com.embabel.agent.a2a.server.support.EmbabelServerGoalsAgentCardHandler
import com.embabel.agent.api.common.ranking.Ranking
import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.test.integration.FakeRanker
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.a2a.spec.TransportProtocol
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient

/**
 * Registers the A2A endpoint card handlers and the shared [A2AClient] for the multi-agent
 * integration tests.
 *
 * Each card handler exposes one agent as an A2A server endpoint. The [goalFilter] narrows
 * which goals appear in the agent card so the A2A router dispatches to the right agent.
 */
@TestConfiguration
@Profile("a2a-test")
class MultiAgentTestConfiguration {

    @Bean
    fun agentOneCardHandler(
        agentPlatform: AgentPlatform,
        handler: AutonomyA2ARequestHandler,
    ): AgentCardHandler = EmbabelServerGoalsAgentCardHandler(
        path = "agent-one",
        agentPlatform = agentPlatform,
        a2ARequestHandler = handler,
        goalFilter = { goal -> goal.description.contains("AgentOne") },
        preferredTransport = TransportProtocol.JSONRPC.asString(),
    )

    @Bean
    fun agentTwoCardHandler(
        agentPlatform: AgentPlatform,
        handler: AutonomyA2ARequestHandler,
    ): AgentCardHandler = EmbabelServerGoalsAgentCardHandler(
        path = "agent-two",
        agentPlatform = agentPlatform,
        a2ARequestHandler = handler,
        goalFilter = { goal -> goal.description.contains("AgentTwo") },
        preferredTransport = TransportProtocol.JSONRPC.asString(),
    )

    @Bean
    fun agentFourCardHandler(
        agentPlatform: AgentPlatform,
        handler: AutonomyA2ARequestHandler,
    ): AgentCardHandler = EmbabelServerGoalsAgentCardHandler(
        path = "agent-four",
        agentPlatform = agentPlatform,
        a2ARequestHandler = handler,
        goalFilter = { goal -> goal.description.contains("AgentFour") },
        preferredTransport = TransportProtocol.JSONRPC.asString(),
    )

    @Bean
    fun multiAgentA2AClient(objectMapperHolder: EmbabelObjectMapperHolder): A2AClient =
        EmbabelA2AClient(
            httpClientFactory = A2AHttpClientFactory { SpringRestClientA2AHttpClient(RestClient.create()) },
            objectMapperHolder = objectMapperHolder,
        )
}

/**
 * Overrides [com.embabel.agent.a2a.server.config.FakeRankerConfiguration] for the
 * multi-agent test. The default fake ranker returns a single fixed agent, which breaks
 * multi-agent dispatch. This ranker assigns a uniform 0.9 score to all candidates so
 * the orchestrator can route to any registered agent.
 */
@TestConfiguration
@Profile("a2a-multi-test")
class MultiAgentRankerConfiguration {

    @Bean
    @Primary
    fun fakeRanker() = object : FakeRanker {
        override fun <T> rank(
            description: String,
            userInput: String,
            rankables: Collection<T>,
        ): Rankings<T> where T : Named, T : Described =
            Rankings(rankables.map { Ranking(it, 0.9) })
    }
}
