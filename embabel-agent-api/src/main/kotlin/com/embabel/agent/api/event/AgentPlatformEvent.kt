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
package com.embabel.agent.api.event

import com.embabel.agent.api.ranking.Ranking
import com.embabel.agent.api.ranking.Rankings
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant

/**
 * System event such as deployment
 */
interface AgentPlatformEvent : AgenticEvent {

    @get:JsonIgnore
    val agentPlatform: AgentPlatform
}

data class AgentDeploymentEvent(
    override val agentPlatform: AgentPlatform,
    @get:JsonIgnore
    val agent: Agent,
) : AgentPlatformEvent {
    override val timestamp: Instant = Instant.now()
}

abstract class RankingEvent<T>(
    override val agentPlatform: AgentPlatform,
    val type: Class<T>,
    val basis: Any,
    val choices: Collection<T>,
) : AgentPlatformEvent where T : Named, T : Described {

    override val timestamp: Instant = Instant.now()
}

class RankingChoiceRequestEvent<T>(
    agentPlatform: AgentPlatform,
    type: Class<T>,
    basis: Any,
    choices: Collection<T>,
) : RankingEvent<T>(agentPlatform, type, basis, choices) where T : Named, T : Described {

    fun determinationEvent(
        choice: Ranking<T>,
        rankings: Rankings<T>,
    ): RankingChoiceMadeEvent<T> {
        return RankingChoiceMadeEvent(
            agentPlatform = agentPlatform,
            type = type,
            choice = choice,
            rankings = rankings,
            basis = basis,
            choices = choices,
        )
    }

    fun noDeterminationEvent(
        rankings: Rankings<T>,
        confidenceCutoff: ZeroToOne,
    ): RankingChoiceCouldNotBeMadeEvent<T> {
        return RankingChoiceCouldNotBeMadeEvent(
            agentPlatform = agentPlatform,
            type = type,
            rankings = rankings,
            confidenceCutOff = confidenceCutoff,
            basis = basis,
            choices = choices,
        )
    }
}

/**
 * @param basis why we chose this ranked
 */
class RankingChoiceMadeEvent<T>(
    agentPlatform: AgentPlatform,
    type: Class<T>,
    val choice: Ranking<T>,
    val rankings: Rankings<T>,
    basis: Any,
    choices: Collection<T>,
) : RankingEvent<T>(agentPlatform, type, basis, choices) where T : Named, T : Described

class RankingChoiceCouldNotBeMadeEvent<T>(
    agentPlatform: AgentPlatform,
    type: Class<T>,
    val rankings: Rankings<T>,
    val confidenceCutOff: ZeroToOne,
    basis: Any,
    choices: Collection<T>,
    override val timestamp: Instant = Instant.now(),
) : RankingEvent<T>(agentPlatform, type, basis, choices) where T : Named, T : Described

/**
 * Emitted when we've created an agent for a specific task
 * @param basis why we chose this agent
 */
data class DynamicAgentCreationEvent(
    override val agentPlatform: AgentPlatform,
    @get:JsonIgnore
    val agent: Agent,
    val basis: Any,
    override val timestamp: Instant = Instant.now(),
) : AgentPlatformEvent
