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
package com.embabel.agent.api.common.workflow.loop

import com.embabel.agent.api.common.InputActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.SupplierAction
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant


/**
 * Mutable object. We only bind this once
 */
data class ResultHistory<RESULT : Any>(
    private val _results: MutableList<RESULT> = mutableListOf(),
    override val timestamp: Instant = Instant.now(),
) : Timestamped, Timed {

    fun attemptCount(): Int = _results.size

    override val runningTime: Duration
        get() = Duration.between(timestamp, Instant.now())

    fun results(): List<RESULT> = _results.toList()

    fun lastResult(): RESULT? = _results.lastOrNull()

    internal fun recordResult(result: RESULT) {
        _results += result
    }
}


/**
 * Primitive for building repeat until workflows.
 */
data class RepeatUntil(
    val maxIterations: Int = 3,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    inline fun <reified RESULT : Any> build(
        noinline task: (TransformationActionContext<ResultHistory<RESULT>, RESULT>) -> RESULT,
        noinline acceptanceCriteria: (InputActionContext<ResultHistory<RESULT>>) -> Boolean,
        inputClasses: List<Class<Any>> = emptyList(),
    ): AgentScopeBuilder<RESULT> =
        build(
            task = task,
            accept = acceptanceCriteria,
            resultClass = RESULT::class.java,
            inputClasses = inputClasses,
        )


    fun <RESULT : Any> build(
        task: (TransformationActionContext<ResultHistory<RESULT>, RESULT>) -> RESULT,
        accept: (InputActionContext<ResultHistory<RESULT>>) -> Boolean,
        resultClass: Class<RESULT>,
        inputClasses: List<Class<out Any>> = emptyList(),
    ): AgentScopeBuilder<RESULT> {

        fun findOrBindResultHistory(context: OperationContext): ResultHistory<RESULT> {
            return context.last<ResultHistory<RESULT>>()
                ?: run {
                    val resultHistory = ResultHistory<RESULT>()
                    context += resultHistory
                    logger.info("Bound new ResultHistory")
                    resultHistory
                }
        }

        val taskAction = SupplierAction(
            name = "=>${resultClass.name}",
            description = "Generate $resultClass",
            post = listOf(RESULT_WAS_BOUND_LAST_CONDITION, ACCEPTABLE_CONDITION),
            cost = 0.0,
            value = 0.0,
            pre = inputClasses.map { IoBinding(type = it).value },
            canRerun = true,
            outputClass = resultClass,
            toolGroups = emptySet(),
        ) { context ->
            val resultHistory = findOrBindResultHistory(context)
            val tac = (context as TransformationActionContext<ResultHistory<RESULT>, RESULT>).copy(
                input = resultHistory,
            )
            val result = task.invoke(tac)
            // Allow the evaluator to access the last result
            resultHistory.recordResult(result)
            logger.info(
                "Generated result {}: {}",
                resultHistory.results().size + 1,
                result,
            )
            result
        }

        val resultWasBoundLastCondition = ComputedBooleanCondition(
            name = RESULT_WAS_BOUND_LAST_CONDITION,
            evaluator = { context, _ ->
                val result = context.lastResult()
                result != null && result::class.java == resultClass
            },
        )

        val acceptableCondition = ComputedBooleanCondition(
            name = ACCEPTABLE_CONDITION,
            evaluator = { context, _ ->
                val resultHistory = context.last<ResultHistory<RESULT>>()
                if (resultHistory?.lastResult() == null) {
                    false
                } else if (resultHistory.results().size >= maxIterations) {
                    logger.info(
                        "Condition '{}': Giving up after {} iterations",
                        ACCEPTABLE_CONDITION,
                        resultHistory.results().size,
                    )
                    true
                } else {
                    val tac = TransformationActionContext<ResultHistory<RESULT>, Boolean>(
                        input = resultHistory,
                        outputClass = Boolean::class.java,
                        processContext = context.processContext,
                        action = taskAction,
                        inputClass = ResultHistory::class.java as Class<ResultHistory<RESULT>>,
                    )
                    val isAcceptable = accept(tac)
                    logger.info(
                        "Condition '{}', iterations={}, acceptable={}",
                        ACCEPTABLE_CONDITION,
                        resultHistory.results().size,
                        isAcceptable,
                    )
                    isAcceptable
                }
            }
        )

        val consolidateAction: Action = TransformationAction(
            name = "consolidate-${resultClass.name}",
            description = "Consolidate results and feedback",
            pre = listOf(ACCEPTABLE_CONDITION, RESULT_WAS_BOUND_LAST_CONDITION),
            cost = 0.0,
            value = 0.0,
            toolGroups = emptySet(),
            inputClass = ResultHistory::class.java,
            outputClass = resultClass,
        ) { context ->
            val finalResult: RESULT = (context.input.lastResult() as? RESULT)
                ?: throw IllegalStateException("No result available in ResultHistory")
            logger.info("Consolidating results, final (best) result: {}", finalResult)
            finalResult
        }

        val resultGoal = Goal(
            "final-${resultClass.name}",
            "Satisfied with the final ${resultClass.name}",
            satisfiedBy = resultClass,
        ).withPreconditions(
            ACCEPTABLE_CONDITION,
            // TODO why is this needed? Should not the satisfiedBy condition be enough?
            RESULT_WAS_BOUND_LAST_CONDITION,
        )
        logger.info("Created goal: {}", resultGoal.infoString(verbose = true, indent = 2))

        return AgentScopeBuilder(
            name = MobyNameGenerator.generateName(),
            actions = listOf(
                taskAction,
                consolidateAction,
            ),
            conditions = setOf(acceptableCondition, resultWasBoundLastCondition),
            goals = setOf(resultGoal)
        )
    }

    private companion object {
        private val ACCEPTABLE_CONDITION = "${RepeatUntil::class.simpleName}_acceptable"
        private val RESULT_WAS_BOUND_LAST_CONDITION = "${RepeatUntil::class.simpleName}_resultWasBoundLast"
    }

}
