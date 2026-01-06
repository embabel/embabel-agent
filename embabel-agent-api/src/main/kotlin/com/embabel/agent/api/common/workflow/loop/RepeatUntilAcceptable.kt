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
package com.embabel.agent.api.common.workflow.loop

import com.embabel.agent.api.common.InputActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.dsl.TypedAgentScopeBuilder
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.*
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.Timed
import com.embabel.common.core.types.Timestamped
import com.embabel.common.core.types.ZeroToOne
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

data class Attempt<RESULT : Any, FEEDBACK : Feedback>(
    val result: RESULT,
    val feedback: FEEDBACK,
) : Timestamped {

    override val timestamp: Instant = Instant.now()
}

/**
 * Mutable object. We only bind this once
 */
data class AttemptHistory<INPUT, RESULT : Any, FEEDBACK : Feedback>(
    val input: INPUT,
    private val _attempts: MutableList<Attempt<RESULT, FEEDBACK>> = mutableListOf(),
    private var lastResult: RESULT? = null,
    override val timestamp: Instant = Instant.now(),
) : Timestamped, Timed {

    fun attemptCount(): Int = _attempts.size

    override val runningTime: Duration
        get() = Duration.between(timestamp, Instant.now())

    fun attempts(): List<Attempt<RESULT, FEEDBACK>> = _attempts.toList()

    fun lastAttempt(): Attempt<RESULT, FEEDBACK>? = _attempts.lastOrNull()

    /**
     * Evaluator can use this to access the last result.
     */
    fun resultToEvaluate(): RESULT? = lastResult

    fun lastFeedback(): FEEDBACK? = lastAttempt()?.feedback

    fun bestSoFar(): Attempt<RESULT, FEEDBACK>? = _attempts.maxByOrNull { it.feedback.score }

    internal fun recordResult(
        result: RESULT,
    ) {
        lastResult = result
    }

    internal fun recordAttempt(
        result: RESULT,
        feedback: FEEDBACK,
    ): Attempt<RESULT, FEEDBACK> {
        val attempt = Attempt(result, feedback)
        _attempts.add(attempt)
        return attempt
    }

}


/**
 * Primitive for building repeat until acceptable workflows.
 * See https://www.anthropic.com/engineering/building-effective-agents
 * This is the Evaluator Optimizer pattern
 */
data class RepeatUntilAcceptable(
    val maxIterations: Int = 3,
    val scoreThreshold: ZeroToOne = 0.9,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    inline fun <reified INPUT, reified RESULT : Any, reified FEEDBACK : Feedback> build(
        noinline task: (RepeatUntilAcceptableActionContext<INPUT, RESULT, FEEDBACK>) -> RESULT,
        noinline evaluator: (EvaluationActionContext<INPUT, RESULT, FEEDBACK>) -> FEEDBACK,
        noinline acceptanceCriteria: (AcceptanceActionContext<INPUT, RESULT, FEEDBACK>) -> Boolean = { it.feedback.score >= scoreThreshold },
        inputClass: Class<INPUT>,
    ): TypedAgentScopeBuilder<RESULT> =
        build(
            task = task,
            evaluator = evaluator,
            acceptanceCriteria = acceptanceCriteria,
            resultClass = RESULT::class.java,
            feedbackClass = FEEDBACK::class.java,
            inputClass = inputClass,
        )


    fun <INPUT, RESULT : Any, FEEDBACK : Feedback> build(
        task: (RepeatUntilAcceptableActionContext<INPUT, RESULT, FEEDBACK>) -> RESULT,
        evaluator: (EvaluationActionContext<INPUT, RESULT, FEEDBACK>) -> FEEDBACK,
        acceptanceCriteria: (AcceptanceActionContext<INPUT, RESULT, FEEDBACK>) -> Boolean,
        resultClass: Class<RESULT>,
        feedbackClass: Class<FEEDBACK>,
        inputClass: Class<out INPUT>,
    ): TypedAgentScopeBuilder<RESULT> {

        fun findOrBindAttemptHistory(
            context: OperationContext,
            input: INPUT,
        ): AttemptHistory<INPUT, RESULT, FEEDBACK> {
            return context.last<AttemptHistory<INPUT, RESULT, FEEDBACK>>()
                ?: run {
                    val ah = AttemptHistory<INPUT, RESULT, FEEDBACK>(input = input)
                    context += ah
                    logger.info("Bound new AttemptHistory")
                    ah
                }
        }

        val taskAction = TransformationAction(
            name = "=>${resultClass.name}",
            description = "Generate $resultClass",
            post = listOf(RESULT_WAS_BOUND_LAST_CONDITION),
            cost = { 0.0 },
            value = { 0.0 },
            pre = listOfNotNull(inputClass)
                .filterNot { it == Unit::class.java }
                .map { com.embabel.agent.core.IoBinding(type = it).value },
            canRerun = true,
            inputClass = inputClass,
            outputClass = resultClass,
            toolGroups = emptySet(),
        ) { context ->
            @Suppress("UNCHECKED_CAST")
            val input = context.input as INPUT
            val attemptHistory = findOrBindAttemptHistory(context, input)

            val tac = RepeatUntilAcceptableActionContext(
                input = input,
                processContext = context.processContext,
                action = context.action,
                inputClass = inputClass as Class<INPUT>,
                outputClass = resultClass,
                attemptHistory = attemptHistory,
            )
            val result = task.invoke(tac)
            // Allow the evaluator to access the last result
            attemptHistory.recordResult(result)
            logger.info(
                "Generated result {}: {}",
                attemptHistory.attempts().size + 1,
                result,
            )
            result
        }

        val evaluationAction = TransformationAction(
            name = "${resultClass.name}=>${feedbackClass.name}",
            description = "Evaluate $resultClass to $feedbackClass",
            pre = listOf(RESULT_WAS_BOUND_LAST_CONDITION),
            post = listOf(ACCEPTABLE_CONDITION),
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = true,
            inputClass = resultClass,
            outputClass = feedbackClass,
            toolGroups = emptySet(),
        ) { context ->
            // AttemptHistory was already bound by taskAction, so we retrieve it
            val attemptHistory = context.last<AttemptHistory<INPUT, RESULT, FEEDBACK>>()
                ?: error("AttemptHistory should have been bound by task action")

            @Suppress("UNCHECKED_CAST")
            val tac = EvaluationActionContext(
                input = attemptHistory.input,
                processContext = context.processContext,
                action = context.action,
                inputClass = inputClass as Class<INPUT>,
                outputClass = feedbackClass,
                attemptHistory = attemptHistory,
            )
            val feedback = evaluator(tac)
            val bestSoFar = attemptHistory.bestSoFar()
            if (bestSoFar == null) {
                logger.info(
                    "First feedback computed: {}",
                    feedback,
                )
            } else if (feedback.score > bestSoFar.feedback.score) {
                logger.info(
                    "New best feedback computed: {} (previously {})",
                    feedback,
                    bestSoFar,
                )
            } else {
                logger.info("Not better than we've seen: Feedback is {}", feedback)
            }
            attemptHistory.recordAttempt(context.input, feedback)
            logger.info("Recorded attempt: {} with feedback: {}", context.input, feedback)
            feedback
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
            evaluator = { context, action ->
                val attemptHistory = context.last<AttemptHistory<INPUT, RESULT, FEEDBACK>>()
                if (attemptHistory?.lastAttempt() == null) {
                    false
                } else if (attemptHistory.attempts().size >= maxIterations) {
                    logger.info(
                        "Condition '{}': Giving up after {} iterations",
                        ACCEPTABLE_CONDITION,
                        attemptHistory.attempts().size,
                    )
                    true
                } else {
                    val lastFeedback = attemptHistory.lastAttempt()!!.feedback
                    val acceptanceContext = AcceptanceActionContext(
                        input = attemptHistory.input,
                        attemptHistory = attemptHistory,
                        feedback = lastFeedback,
                    )
                    val isAcceptable = acceptanceCriteria(acceptanceContext)
                    logger.info(
                        "Condition '{}', iterations={}: Feedback acceptable={}: {}",
                        ACCEPTABLE_CONDITION,
                        attemptHistory.attempts().size,
                        isAcceptable,
                        lastFeedback,
                    )
                    isAcceptable
                }
            }
        )

        val consolidateAction: Action = TransformationAction(
            name = "consolidate-${resultClass.name}-${feedbackClass.name}",
            description = "Consolidate results and feedback",
            pre = listOf(ACCEPTABLE_CONDITION),
            cost = { 0.0 },
            value = { 0.0 },
            toolGroups = emptySet(),
            inputClass = AttemptHistory::class.java,
            outputClass = resultClass,
        ) { context ->
            val bestResult = context.input.bestSoFar()?.result as? RESULT
                ?: throw IllegalStateException("No result available in AttemptHistory")
            logger.info("Consolidating results, final (best) result: {}", bestResult)
            bestResult
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

        return TypedAgentScopeBuilder(
            name = MobyNameGenerator.generateName(),
            actions = listOf(
                taskAction,
                evaluationAction,
                consolidateAction,
            ),
            conditions = setOf(acceptableCondition, resultWasBoundLastCondition),
            goals = setOf(resultGoal),
            opaque = true,
        )
    }

    private companion object {
        private val ACCEPTABLE_CONDITION = "${RepeatUntilAcceptable::class.simpleName}_acceptable"
        private val RESULT_WAS_BOUND_LAST_CONDITION = "${RepeatUntilAcceptable::class.simpleName}_resultWasBoundLast"
    }

}

abstract class RepeatUntilAcceptableContext<INPUT, RESULT : Any, FEEDBACK : Feedback>(
    override val input: INPUT,
    override val processContext: ProcessContext,
    override val action: Action,
    val inputClass: Class<INPUT>,
    val outputClass: Class<*>,
    val attemptHistory: AttemptHistory<INPUT, RESULT, FEEDBACK>,
) : InputActionContext<INPUT?>, Blackboard by processContext.agentProcess,
    AgenticEventListener by processContext {

    /**
     * Get the last attempt if available.
     */
    fun lastAttempt(): Attempt<RESULT, FEEDBACK>? = attemptHistory.lastAttempt()

    /**
     * Convenience method to get result from last attempt or return default
     * Easy to embed in prompts
     */
    fun lastAttemptOr(defaultValue: String): String {
        return lastAttempt()?.result?.toString() ?: defaultValue
    }

    /**
     * Convenience method to get feedback from last attempt or return default
     */
    fun lastFeedbackOr(defaultValue: String): String {
        return lastAttempt()?.feedback?.toString() ?: defaultValue
    }

    override val toolGroups: Set<ToolGroupRequirement>
        get() = action.toolGroups

    override val operation = action

}

open class RepeatUntilAcceptableActionContext<INPUT, RESULT : Any, FEEDBACK : Feedback>(
    input: INPUT,
    processContext: ProcessContext,
    action: Action,
    inputClass: Class<INPUT>,
    outputClass: Class<*>,
    attemptHistory: AttemptHistory<INPUT, RESULT, FEEDBACK>,
) : RepeatUntilAcceptableContext<INPUT, RESULT, FEEDBACK>(
    input = input,
    processContext = processContext,
    action = action,
    inputClass = inputClass,
    outputClass = outputClass,
    attemptHistory = attemptHistory,
)


open class EvaluationActionContext<INPUT, RESULT : Any, FEEDBACK : Feedback>(
    input: INPUT,
    processContext: ProcessContext,
    action: Action,
    inputClass: Class<INPUT>,
    outputClass: Class<*>,
    attemptHistory: AttemptHistory<INPUT, RESULT, FEEDBACK>,
) : RepeatUntilAcceptableContext<INPUT, RESULT, FEEDBACK>(
    input = input,
    processContext = processContext,
    action = action,
    inputClass = inputClass,
    outputClass = outputClass,
    attemptHistory = attemptHistory,
) {
    val resultToEvaluate: RESULT = attemptHistory.resultToEvaluate() ?: error("No result available in AttemptHistory")

}


data class AcceptanceActionContext<INPUT, RESULT : Any, FEEDBACK : Feedback>(
    val input: INPUT,
    val attemptHistory: AttemptHistory<INPUT, RESULT, FEEDBACK>,
    val feedback: FEEDBACK,
) {
    /**
     * Get the last attempt if available.
     */
    fun lastAttempt(): Attempt<RESULT, FEEDBACK>? = attemptHistory.lastAttempt()
}
