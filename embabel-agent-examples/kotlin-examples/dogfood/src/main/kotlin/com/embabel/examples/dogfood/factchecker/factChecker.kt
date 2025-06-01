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
package com.embabel.examples.dogfood.factchecker

import com.embabel.agent.api.common.createObject
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.dsl.aggregate
import com.embabel.agent.api.dsl.parallelMap
import com.embabel.agent.config.models.AnthropicModels
import com.embabel.agent.core.Agent
import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.InternetResource
import com.embabel.agent.domain.library.InternetResources
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.core.types.ZeroToOne
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

data class FactualAssertion(val standaloneAssertion: String)

data class FactualAssertions(val factualAssertions: List<FactualAssertion>)

data class RationalizedFactualAssertions(
    val factualAssertions: List<FactualAssertion>,
    @JsonPropertyDescription("factual assertions that were merged")
    val numberMerged: Int,
)

data class AssertionCheck(
    val assertion: String,
    val isTrue: Boolean,
    @JsonPropertyDescription("confidence in your judgment as to whether the assertion true or false. From 0-1")
    val confidence: ZeroToOne,
    @JsonPropertyDescription("reasoning for your scoring")
    val reasoning: String,
    override val links: List<InternetResource>,
) : InternetResources

data class FactCheck(
    val checks: List<AssertionCheck>,
)

@ConfigurationProperties("embabel.fact-checker")
data class FactCheckerProperties(
    val reasoningWordCount: Int = 30,
    val trustedSources: List<String> = listOf(
        "Wikipedia",
        "Wikidata",
        "Britannica",
        "BBC",
        "Reuters",
        "ABC Australia",
    ),
    val untrustedSources: List<String> = listOf(
        "Reddit",
        "4chan",
        "Twitter",
    )
)

@Configuration
@Profile("!test")
class FactCheckerAgentConfiguration {
    @Bean
    fun factChecker(factCheckerProperties: FactCheckerProperties): Agent {
        return factCheckerAgent(
            llms = listOf(
                LlmOptions(AnthropicModels.CLAUDE_35_HAIKU).withTemperature(.3),
                LlmOptions(AnthropicModels.CLAUDE_35_HAIKU).withTemperature(.0),
            ),
            properties = factCheckerProperties,
        )
    }
}


/**
 * Naming agent that generates names for a company or project.
 */
fun factCheckerAgent(
    llms: List<LlmOptions>,
    properties: FactCheckerProperties,
) = agent(
    name = "FactChecker",
    description = "Check content for factual accuracy",
) {

    flow {

//        referencedAgentAction<UserInput, ResearchReport>(agentName = Researcher::class.java.name)

        aggregate<UserInput, FactualAssertions, RationalizedFactualAssertions>(
            transforms = llms.map { llm ->
                { context ->
                    context.promptRunner(llm = llm, toolGroups = setOf(CoreToolGroups.WEB)).createObject(
                        """
            Given the following content, identify any factual assertions.
            Phrase them as standalone assertions.

            # Content
            ${context.input.content}
            """.trimIndent()
                    )
                }
            },
            merge = { list, context ->
                context.promptRunner().createObject<RationalizedFactualAssertions>(
                    """
                    Given the following factual assertions, merge them into a single list if
                    any are the same. Condense into one assertion if one assertion negates another.
                    Count the number you merged.

                    # Assertions
                    ${list.flatMap { it.factualAssertions }.joinToString("\n") { "- " + it.standaloneAssertion }}
                    """.trimIndent()
                )
            },
        ).parallelize()
    }

    transformation<RationalizedFactualAssertions, FactCheck> { operationContext ->
        val promptRunner = operationContext.promptRunner(
            LlmOptions(ModelSelectionCriteria.Auto),
            toolGroups = setOf(CoreToolGroups.WEB, CoreToolGroups.BROWSER_AUTOMATION),
        )
        val checks = operationContext.input.factualAssertions.parallelMap(operationContext) { assertion ->
            promptRunner.createObject<AssertionCheck>(
                """
                Given the following assertion, check if it is true or false and explain why in ${properties.reasoningWordCount} words
                Express your confidence in your determination as a number between 0 and 1.
                Use web tools.

                Be guided by the following regarding sources:
                - Trusted sources: ${properties.trustedSources.joinToString(", ")}
                - Untrusted sources: ${properties.untrustedSources.joinToString(", ")}
                Assertion: <${assertion.standaloneAssertion}>
                """
            )
        }
        FactCheck(checks)
    }

    goal(
        name = "factCheckingDone",
        description = "Content was fact checked",
        satisfiedBy = FactCheck::class,
    )

}
