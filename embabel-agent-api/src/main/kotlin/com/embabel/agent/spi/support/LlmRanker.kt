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
package com.embabel.agent.spi.support

import com.embabel.agent.common.RetryProperties
import com.embabel.agent.spi.*
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byName
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Properties for the ranking service. Optional LLM selection, plus normal retry properties.
 * @param llm name of the LLM to use for ranking, or null to use auto selection
 * @param maxAttempts maximum number of attempts to retry ranking
 * @param backoffMillis initial backoff time in milliseconds
 * @param backoffMultiplier multiplier for backoff time
 * @param backoffMaxInterval maximum backoff time in milliseconds
 */
@ConfigurationProperties("embabel.agent.platform.ranking")
data class RankingProperties(
    val llm: String? = null,
    override val maxAttempts: Int = 5,
    override val backoffMillis: Long = 100L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
) : RetryProperties

/**
 * Use an LLM to rank things
 */
internal class LlmRanker(
    private val llmOperations: LlmOperations,
    private val rankingProperties: RankingProperties,
) : Ranker {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val llm = LlmOptions(
        criteria =
            if (rankingProperties.llm != null) {
                logger.info("Using LLM '{}' for ranking", rankingProperties.llm)
                byName(rankingProperties.llm)
            } else {
                logger.info("Using auto LLM for ranking")
                ModelSelectionCriteria.Auto
            }
    )

    override fun <T> rank(
        description: String,
        userInput: String,
        rankables: Collection<T>
    ): Rankings<T> where T : Named, T : Described {
        if (rankables.isEmpty()) {
            return Rankings(emptyList())
        }
        return rankingProperties.retryTemplate("ranker").execute<Rankings<T>, Exception> {
            rankThingsInternal(
                description = description,
                userInput = userInput,
                rankables = rankables,
            )
        }
    }

    private fun <T> rankThingsInternal(
        description: String,
        userInput: String,
        rankables: Collection<T>,
    ): Rankings<T> where T : Named, T : Described {
        val type = rankables.firstOrNull()?.javaClass?.simpleName
            ?: throw IllegalArgumentException("Rankables must not be empty")

        val prompt =
            """
            Your job is rank objects of type $type ($description) based on user input.
            Given the user input, choose the name that best reflects the user's intent.

            User input: <$userInput>

            Available choices, in format <name>: <description>:
            ${rankables.joinToString("\n") { "- ${it.name}: ${it.description}" }}

            Return the name of the chosen $type and the confidence score from 0-1.
            IMPORTANT: The fully qualified name must be exactly the same as in the list.
            """.trimIndent()
        logger.debug("{} ranking prompt: {}", type, prompt)
        val rankingResponse = llmOperations.doTransform(
            prompt = prompt,
            interaction = LlmInteraction(
                id = InteractionId("rank-${type}s"),
                llm = llm,
            ),
            outputClass = RankingsResponse::class.java,
            llmRequestEvent = null,
        )
        logger.debug("{} ranking response: {}", type, rankingResponse)

        val thingNames = rankables.map { it.name }
        val bogusRanking =
            rankingResponse.rankings.find { rankedChoiceResponse -> thingNames.none { rankedChoiceResponse.name == it } }
        if (bogusRanking != null) {
            throw IllegalStateException(
                "Ranker returned choice '$bogusRanking' not in the list of available ${type}s: ${
                    thingNames.map { "'$it'" }
                }, raw=$rankingResponse"
            )
            return Rankings(emptyList())
        }

        return Rankings(
            rankings = rankingResponse.rankings.map {
                Ranking(
                    match = rankables.single { thing -> thing.name == it.name },
                    score = it.confidence,
                )
            }.sortedBy { it.score }.reversed()
        )
    }
}

@JsonClassDescription("List of ranked choices")
internal data class RankingsResponse(
    val rankings: List<RankedChoiceResponse>,
)

internal data class RankedChoiceResponse(
    @get:JsonPropertyDescription("name of what we're ranking")
    val name: String,
    @get:JsonPropertyDescription("confidence score from 0-1")
    val confidence: Double,
)
