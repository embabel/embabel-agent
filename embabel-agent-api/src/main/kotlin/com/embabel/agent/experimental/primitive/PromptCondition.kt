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
package com.embabel.agent.experimental.primitive

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.Condition
import com.embabel.agent.spi.InteractionId
import com.embabel.agent.spi.LlmCall
import com.embabel.agent.spi.LlmInteraction
import com.embabel.chat.UserMessage
import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.goap.ConditionDetermination
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory


/**
 * Prompt an LLM to evaluate a condition.
 * Evaluating prompt conditions is expensive,
 * so we need to consider efficiency here.
 * @param name name of the condition
 * @param prompt the prompt to evaluate.
 * Typically created from Blackboard state from the ProcessContext.
 */
data class PromptCondition(
    override val name: String,
    val prompt: (context: OperationContext) -> String,
    val llm: LlmCall,
) : Condition {

    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * This is as expensive as it can get
     */
    override val cost: ZeroToOne = 1.0

    override fun evaluate(context: OperationContext): ConditionDetermination {
        val prompt =
            """
            Evaluate this condition: ${prompt(context)}
            Return "result": whether you think it is true, your confidence level from 0-1,
            and an explanation of what you base this on.
            """.trimIndent()
        logger.info("Condition {}: making LLM call to evaluate using {}...", name, llm)
        val interaction = LlmInteraction.from(
            llm = llm,
            id = InteractionId("condition-$name")
        )
        val determination = context.processContext.platformServices.llmOperations.createObject(
            messages = listOf(UserMessage(prompt)),
            interaction = interaction,
            outputClass = Determination::class.java,
            agentProcess = context.processContext.agentProcess,
            action = null,
        )
        logger.info(
            "Condition {}: determination from {} was {}",
            name,
            interaction.llm.criteria,
            determination,
        )
        return ConditionDetermination(determination.result)
    }
}

@JsonClassDescription("Determination of a condition")
data class Determination(
    @JsonPropertyDescription("Result of the condition: true or false")
    val result: Boolean,

    @JsonPropertyDescription("Confidence level of the result, from 0-1")
    val confidence: ZeroToOne,

    @JsonPropertyDescription("Explanation of your determination")
    val explanation: String,
)
