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
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.agent.event.AgenticEventListener
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

    inline fun <reified INPUT, reified RESULT : Any> build(
        noinline task: (RepeatUntilActionContext<INPUT, RESULT>) -> RESULT,
        noinline acceptanceCriteria: (RepeatUntilActionContext<INPUT, RESULT>) -> Boolean,
        inputClass: Class<INPUT>? = null,
    ): AgentScopeBuilder<RESULT> =
        build(
            task = task,
            accept = acceptanceCriteria,
            resultClass = RESULT::class.java,
            inputClass = inputClass,
        )


    fun <INPUT, RESULT : Any> build(
        task: (RepeatUntilActionContext<INPUT, RESULT>) -> RESULT,
        accept: (RepeatUntilActionContext<INPUT, RESULT>) -> Boolean,
        resultClass: Class<RESULT>,
        inputClass: Class<out INPUT>? = null,
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

        val taskAction = TransformationAction(
            name = "=>${resultClass.name}",
            description = "Generate $resultClass",
            post = listOf(RESULT_WAS_BOUND_LAST_CONDITION, ACCEPTABLE_CONDITION),
            cost = 0.0,
            value = 0.0,
            pre = listOfNotNull(inputClass).map { IoBinding(type = it).value },
            canRerun = true,
            outputClass = resultClass,
            inputClass = inputClass ?: Unit::class.java,
            toolGroups = emptySet(),
        ) { context ->
            val resultHistory = findOrBindResultHistory(context)

            @Suppress("UNCHECKED_CAST")
            val tac = RepeatUntilActionContext<INPUT, RESULT>(
                input = context.input as? INPUT,
                processContext = context.processContext,
                action = context.action,
                inputClass = inputClass as? Class<INPUT> ?: Unit::class.java as Class<INPUT>,
                outputClass = resultClass,
                history = resultHistory,
            )
            val result = task.invoke(tac)
            // Allow the evaluator to access the last result
            resultHistory.recordResult(result)
            logger.info(
                "Generated result {}: {}",
                resultHistory.results().size,
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
                    @Suppress("UNCHECKED_CAST")
                    val input: INPUT? = if (inputClass != null) {
                        context.last(inputClass) as? INPUT
                    } else {
                        null
                    }
                    val tac = RepeatUntilActionContext<INPUT, RESULT>(
                        input = input,
                        outputClass = resultClass,
                        processContext = context.processContext,
                        action = taskAction,
                        inputClass = inputClass as? Class<INPUT> ?: Unit::class.java as Class<INPUT>,
                        history = resultHistory,
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
            goals = setOf(resultGoal),
            opaque = true,
        )
    }

    private companion object {
        private val ACCEPTABLE_CONDITION = "${RepeatUntil::class.simpleName}_acceptable"
        private val RESULT_WAS_BOUND_LAST_CONDITION = "${RepeatUntil::class.simpleName}_resultWasBoundLast"
    }

}

data class RepeatUntilActionContext<INPUT, RESULT : Any>(
    override val input: INPUT?,
    override val processContext: ProcessContext,
    override val action: Action,
    val inputClass: Class<INPUT>,
    val outputClass: Class<RESULT>,
    val history: ResultHistory<RESULT>,
) : InputActionContext<INPUT?>, Blackboard by processContext.agentProcess,
    AgenticEventListener by processContext {

    override val toolGroups: Set<ToolGroupRequirement>
        get() = action.toolGroups

    override val operation = action

    /**
     * Get the AttemptHistory from the process context if available.
     * This is used by RepeatUntilAcceptable to access attempt history with feedback.
     */
    fun <R : Any, F : Feedback> getAttemptHistory(): AttemptHistory<INPUT, R, F>? {
        @Suppress("UNCHECKED_CAST")
        return processContext.agentProcess.last<AttemptHistory<INPUT, R, F>>()
    }
}
