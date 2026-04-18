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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * End-to-end integration test for parent/child AgentProcess cost aggregation
 * with a real LLM call (gpt-4.1-mini, ~$0.001 per run).
 *
 * Since the aggregation logic in [com.embabel.agent.core.support.AbstractAgentProcess]
 * is provider-agnostic, a single real-LLM IT on one provider is enough to validate
 * the full pipeline (Spring wiring, YAML pricing, AgentPlatform, asSubProcess,
 * InMemoryAgentProcessRepository, cost aggregation).
 *
 * Requires the `OPENAI_API_KEY` environment variable.
 */
@SpringBootTest(
    properties = [
        "embabel.models.cheapest=gpt-4.1-mini",
        "embabel.models.best=gpt-4.1-mini",
        "embabel.models.default-llm=gpt-4.1-mini",
        "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("thinking")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
@ComponentScan(
    basePackages = ["com.embabel.agent", "com.embabel.example"],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = [".*GlobalExceptionHandler.*"]
        )
    ]
)
@Import(AgentOpenAiAutoConfiguration::class)
@ImportAutoConfiguration(AgentPlatformAutoConfiguration::class)
class AgentProcessCostAggregationRealLlmIT {

    private val logger = LoggerFactory.getLogger(AgentProcessCostAggregationRealLlmIT::class.java)

    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    data class FirstJoke(val text: String)
    data class SecondJoke(val text: String)
    data class TwoJokes(val first: FirstJoke, val second: SecondJoke)

    @Agent(
        name = "FirstJokeSubAgent",
        description = "Subagent that generates the first joke via real LLM",
        scan = false,
    )
    class FirstJokeSubAgent {

        @Action
        @AchievesGoal(description = "First joke generated")
        fun generateJoke(input: UserInput, context: OperationContext): FirstJoke =
            context.ai()
                .withDefaultLlm()
                .createObject(
                    """
                    Generate a one-line joke about: ${input.content}.
                    Return JSON with a single field "text" containing the joke.
                    Keep it under 20 words.
                    """.trimIndent(),
                    FirstJoke::class.java,
                )
    }

    @Agent(
        name = "SecondJokeSubAgent",
        description = "Subagent that generates the second joke via real LLM",
        scan = false,
    )
    class SecondJokeSubAgent {

        @Action
        @AchievesGoal(description = "Second joke generated")
        fun generateJoke(input: UserInput, context: OperationContext): SecondJoke =
            context.ai()
                .withDefaultLlm()
                .createObject(
                    """
                    Generate a DIFFERENT one-line joke about: ${input.content}.
                    Return JSON with a single field "text" containing the joke.
                    Keep it under 20 words.
                    """.trimIndent(),
                    SecondJoke::class.java,
                )
    }

    @Agent(
        name = "JokeParentAgent",
        description = "Parent that delegates joke generation to two subagents",
        scan = false,
    )
    class JokeParentAgent {

        @Action
        fun delegateToFirstSubagent(input: UserInput, context: ActionContext): FirstJoke {
            val subAgent = AgentMetadataReader()
                .createAgentMetadata(FirstJokeSubAgent()) as com.embabel.agent.core.Agent
            return context.asSubProcess(FirstJoke::class.java, subAgent)
        }

        @Action
        fun delegateToSecondSubagent(input: UserInput, context: ActionContext): SecondJoke {
            val subAgent = AgentMetadataReader()
                .createAgentMetadata(SecondJokeSubAgent()) as com.embabel.agent.core.Agent
            return context.asSubProcess(SecondJoke::class.java, subAgent)
        }

        @Action
        @AchievesGoal(description = "Both jokes delivered from subagents")
        fun done(first: FirstJoke, second: SecondJoke): TwoJokes = TwoJokes(first, second)
    }

    @Test
    fun `parent cost aggregates child cost with real OpenAI call`() {
        val parentAgentDef = AgentMetadataReader()
            .createAgentMetadata(JokeParentAgent()) as com.embabel.agent.core.Agent

        val result = agentPlatform.runAgentFrom(
            parentAgentDef,
            ProcessOptions(),
            mapOf("it" to UserInput("cats")),
        )
        val parent = result as AgentProcess

        val children = agentPlatform.platformServices.agentProcessRepository.findByParentId(parent.id)
        assertEquals(
            2, children.size,
            "Expected 2 child processes (one per delegate action), got ${children.size}"
        )

        val childrenCost = children.sumOf { it.cost() }
        assertTrue(
            childrenCost > 0.0,
            "Children should have incurred real LLM cost (got \$$childrenCost). " +
                    "If 0, pricing may not be wired for gpt-4.1-mini."
        )

        val parentOwnCost = parent.ownCost()
        val expectedTotal = parentOwnCost + childrenCost

        logger.info(
            "parent.cost()={}, parent own={}, children={}, expected={}",
            parent.cost(), parentOwnCost, childrenCost, expectedTotal,
        )

        assertEquals(
            expectedTotal, parent.cost(), 1e-9,
            "parent.cost() must aggregate child process costs. " +
                    "Expected \$$expectedTotal (own \$$parentOwnCost + children \$$childrenCost), " +
                    "got \$${parent.cost()}."
        )

        val parentOwnPromptTokens = parent.ownUsage().promptTokens ?: 0
        val childrenPromptTokens = children.sumOf { it.usage().promptTokens ?: 0 }
        assertEquals(
            parentOwnPromptTokens + childrenPromptTokens,
            parent.usage().promptTokens,
            "parent.usage().promptTokens must aggregate children."
        )
    }
}
