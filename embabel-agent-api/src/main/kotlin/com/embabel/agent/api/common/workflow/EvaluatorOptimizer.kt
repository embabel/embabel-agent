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
package com.embabel.agent.api.common.workflow

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.SupplierAction
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.Action
import com.embabel.agent.core.ComputedBooleanCondition
import com.embabel.agent.core.Goal
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.Timestamped
import com.embabel.common.core.types.ZeroToOne
import org.slf4j.LoggerFactory
import java.time.Instant

interface Feedback {
    val score: ZeroToOne
}

data class SimpleFeedback(
    override val score: ZeroToOne,
    val feedback: String,
) : Feedback

data class ScoredResult<RESULT, FEEDBACK>(
    val result: RESULT,
    val feedback: FEEDBACK,
    val iterations: Int,
) : Timestamped {

    override val timestamp: Instant = Instant.now()

}

/**
 * See https://www.anthropic.com/engineering/building-effective-agents
 */
object EvaluatorOptimizer {

    private val logger = LoggerFactory.getLogger(EvaluatorOptimizer::class.java)

    inline fun <reified RESULT : Any, reified FEEDBACK : Feedback> generateUntilAcceptable(
        noinline generator: (TransformationActionContext<FEEDBACK?, RESULT>) -> RESULT,
        noinline evaluator: (TransformationActionContext<RESULT, FEEDBACK>) -> FEEDBACK,
        maxIterations: Int,
        noinline acceptanceCriteria: (FEEDBACK) -> Boolean = { it.score >= 0.98 },
    ): AgentScopeBuilder<ScoredResult<RESULT, FEEDBACK>> =
        generateUntilAcceptable(
            generator = generator,
            evaluator = evaluator,
            acceptanceCriteria = acceptanceCriteria,
            maxIterations = maxIterations,
            resultClass = RESULT::class.java,
            feedbackClass = FEEDBACK::class.java,
        )

    @JvmStatic
    fun <RESULT : Any, FEEDBACK : Feedback> generateUntilAcceptable(
        generator: (TransformationActionContext<FEEDBACK?, RESULT>) -> RESULT,
        evaluator: (TransformationActionContext<RESULT, FEEDBACK>) -> FEEDBACK,
        acceptanceCriteria: (FEEDBACK) -> Boolean,
        maxIterations: Int,
        resultClass: Class<RESULT>,
        feedbackClass: Class<FEEDBACK>,
    ): AgentScopeBuilder<ScoredResult<RESULT, FEEDBACK>> {

        val generationAction = SupplierAction(
            name = "=>${resultClass.name}",
            description = "Generate $resultClass",
            post = listOf("reportWasLastAction"),
            cost = 0.0,
            value = 0.0,
            canRerun = true,
            outputClass = resultClass,
            toolGroups = emptySet(),
        ) {
            val tac = (it as TransformationActionContext<FEEDBACK?, RESULT>).copy(
                input = it.last(feedbackClass)
            )
            val report = generator.invoke(tac)
            logger.info("Generated report: {}", report)
            report
        }

        val evaluationAction = TransformationAction(
            name = "${resultClass.name}=>${feedbackClass.name}",
            description = "Evaluate $resultClass to $feedbackClass",
            pre = listOf(REPORT_WAS_LAST_ACTION_CONDITION),
            post = listOf(ACCEPTABLE_CONDITION),
            cost = 0.0,
            value = 0.0,
            canRerun = true,
            inputClass = resultClass,
            outputClass = feedbackClass,
            toolGroups = emptySet(),
        ) { context ->
            val feedback = evaluator(context)
            val bestSoFar = context[BEST_FEEDBACK_BINDING] as FEEDBACK?
            if (bestSoFar == null || feedback.score > bestSoFar.score) {
                logger.info(
                    "New best feedback found: {} (was {})",
                    feedback,
                    bestSoFar ?: "none",
                )
                context[BEST_RESULT_BINDING] = context.input
                context[BEST_FEEDBACK_BINDING] = feedback
            } else {
                logger.debug("Not better than we've seen: Feedback is {}", feedback)
            }
            feedback
        }
        val reportWasLastActionCondition = ComputedBooleanCondition(
            name = REPORT_WAS_LAST_ACTION_CONDITION,
            evaluator = { context, _ ->
                val result = context.lastResult()
                result != null && result::class.java == resultClass
            },
        )

        fun countIterations(context: OperationContext) =
            context.objects.filterIsInstance(resultClass).distinct().count()

        val acceptableCondition = ComputedBooleanCondition(
            name = ACCEPTABLE_CONDITION,
            evaluator = { context, _ ->
                val iterations = countIterations(context)
                if (iterations >= maxIterations) {
                    logger.info(
                        "Condition '{}': Giving up after {} iterations",
                        ACCEPTABLE_CONDITION,
                        iterations,
                    )
                    true
                } else {
                    val feedback = context.last(feedbackClass)
                    if (feedback == null) {
                        logger.debug(
                            "Condition '{}', iterations={}: No feedback to evaluate",
                            ACCEPTABLE_CONDITION,
                            iterations
                        )
                        false
                    } else {
                        val isAcceptable = acceptanceCriteria(feedback)
                        logger.debug(
                            "Condition '{}', iterations={}: Feedback acceptable={}: {}",
                            ACCEPTABLE_CONDITION,
                            iterations,
                            isAcceptable,
                            feedback,
                        )
                        isAcceptable
                    }
                }
            }
        )
        val consolidateAction: Action = SupplierAction(
            name = "consolidate-${resultClass.name}-${feedbackClass.name}",
            description = "Consolidate results and feedback",
            pre = listOf(ACCEPTABLE_CONDITION),
            cost = 0.0,
            value = 0.0,
            toolGroups = emptySet(),
            outputClass = ScoredResult::class.java,
        ) {
            val bestFeedback = it[BEST_FEEDBACK_BINDING] as FEEDBACK
            val bestResult = it[BEST_RESULT_BINDING] as RESULT
            ScoredResult(
                result = bestResult,
                feedback = bestFeedback,
                iterations = countIterations(it),
            )
        }

        val resultGoal = Goal(
            "final-${resultClass.name}",
            "Satisfied with the final ${resultClass.name}",
            satisfiedBy = ScoredResult::class.java,
        )

        return AgentScopeBuilder(
            name = MobyNameGenerator.generateName(),
            actions = listOf(
                generationAction,
                evaluationAction,
                consolidateAction,
            ),
            conditions = setOf(acceptableCondition, reportWasLastActionCondition),
            goals = setOf(resultGoal)
        )
    }


    private const val ACCEPTABLE_CONDITION = "acceptable"
    private const val REPORT_WAS_LAST_ACTION_CONDITION = "reportWasLastAction"

    private const val BEST_FEEDBACK_BINDING = "bestFeedback"
    private const val BEST_RESULT_BINDING = "bestResult"


}
