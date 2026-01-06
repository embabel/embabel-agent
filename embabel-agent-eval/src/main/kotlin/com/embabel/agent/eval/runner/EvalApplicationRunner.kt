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
package com.embabel.agent.eval.runner

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.eval.assert.AssertionEvaluator
import com.embabel.agent.eval.client.AgentChatClient
import com.embabel.agent.eval.support.DefaultEvaluationResultScorer
import com.embabel.agent.eval.support.EvaluationJob
import com.embabel.agent.eval.support.EvaluationOptions
import com.embabel.agent.eval.support.EvaluationRunner
import com.embabel.common.textio.template.TemplateRenderer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Command line to run evaluation of a conversation.
 */
@Component
class EvalApplicationRunner(
    private val evaluatorChatModel: ChatModel,
    private val templateRenderer: TemplateRenderer,
    private val assertionEvaluator: AssertionEvaluator,
    private val agentChatClient: AgentChatClient,
) : ApplicationRunner {

    private val logger: Logger = LoggerFactory.getLogger(EvalApplicationRunner::class.java)

    @Value("\${verbose:false}")
    private var verbose: Boolean = false

    @Value("\${file}")
    private var file: String = "data/eval/agent.yml"

    @Value("\${model}")
    private var model: String = OpenAiModels.GPT_41_MINI

    private val evaluationRunner: EvaluationRunner = DefaultEvaluationRunner(
        evaluatorChatModel = evaluatorChatModel,
        templateRenderer = templateRenderer,
        setupRunner = assertionEvaluator,
        assertionEvaluator = assertionEvaluator,
        agentChatClient = agentChatClient,
    )

    private val scorer = DefaultEvaluationResultScorer()

    override fun run(args: ApplicationArguments) {
        val file = "file:${System.getProperty("user.dir")}/$file"
        val evaluations = evaluationRunner.evaluateConversation(
            evaluationJob = EvaluationJob.fromYml(file),
            options = EvaluationOptions(
                verbose = verbose,
                model = model,
            )
        )
//        println(evaluations.report())

        val fullScores = evaluations.results.map { scorer.score(it) }

        val averageScore = fullScores.map { it.totalScore }.average()
        val roundedAverageScore = BigDecimal(averageScore).setScale(2, RoundingMode.HALF_EVEN).toDouble()

        logger.debug(fullScores.joinToString("\n"))

        println("****** Average score: $roundedAverageScore ******")

    }
}
