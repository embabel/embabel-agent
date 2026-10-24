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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.common.ranking.Ranker
import com.embabel.agent.api.common.ranking.Ranking
import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.RankingProperties.Companion.PREFIX
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.byName
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Properties for the ranking service. Optional LLM selection, plus normal retry properties.
 * @param llm name of the LLM to use for ranking, or null to use auto selection
 * @param maxAttempts maximum number of attempts to retry ranking
 * @param backoffMillis initial backoff time in milliseconds
 * @param backoffMultiplier multiplier for backoff time
 * @param backoffMaxInterval maximum backoff time in milliseconds
 */
@ConfigurationProperties(PREFIX)
data class RankingProperties(
    val llm: String? = null,
    override val maxAttempts: Int = 5,
    override val backoffMillis: Long = 100L,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 180000L,
    override val propertyPrefix: String = PREFIX,
) : RetryProperties {
    companion object {
        const val PREFIX: String  = "embabel.agent.platform.ranking"
    }
}

/**
 * Use an LLM to rank things
 */
internal class LlmRanker(
    private val llmOperations: LlmOperations,
    private val rankingProperties: RankingProperties,
) : Ranker {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val objectMapper = jacksonObjectMapper()

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
        rankables: Collection<T>,
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

            Available choices as JSON objects with separate name and description fields:
            ${objectMapper.writeValueAsString(rankables.map { mapOf("name" to it.name, "description" to it.description) })}

            Return only a JSON object containing a rankings array, with a name and confidence score from 0-1 for each choice.
            Example response structure (choose the appropriate name and confidence for the user input):
            ${objectMapper.writeValueAsString(RankingsResponse(listOf(RankedChoiceResponse(rankables.first().name, 0.9))))}
            Do not return plain text, field labels such as "Name:", Markdown, or code fences.
            IMPORTANT: Copy only the exact name field into each response's name field.
            Do not append the description, explanatory text, or any other characters to the name.
            Descriptions help you choose; they are not part of the name.
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
    @get:JsonPropertyDescription("Exact Name field of an available choice, without its description or any additional text")
    val name: String,
    @get:JsonPropertyDescription("confidence score from 0-1")
    val confidence: Double,
)
